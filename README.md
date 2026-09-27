# Alerting Service — take-home task

Users get notified by **email** and **Slack** (more channels pluggable) when something important
happens in the world — breaking news, market movements, natural disasters — with an **admin view**
to manage it all. Built with AI agents from a deliberately vague brief.

## The brief (verbatim)

> "We want users to be able to set up alerts so they get notified when something important happens
> in the world — like breaking news, market movements, natural disasters, that kind of thing. Should
> work for both email and Slack. Make it flexible enough that we can add more channels later. We need
> an admin view too."
>
> You may use any technology, language, or framework you choose. There is no further specification.
> No wireframes. No schema proposal. No agreement on what "something important" means, where the
> data comes from, or how events are detected.
>
> Using AI agents, take this brief from ambiguity to a working implementation. Before you start
> building, define your own plan of attack. […] We are not evaluating the solution itself. We are
> evaluating how you arrived at it.

## Read this first: the process

The evaluation is about *how* the solution was reached, so the process documents are the main
deliverable. The code is evidence.

| Document | What it contains |
|---|---|
| [`PLAN.md`](PLAN.md) | Interpretation of the brief, deliverables, data model, architecture, build order, how AI output was validated. |
| [`DECISIONS.md`](DECISIONS.md) | 50+ numbered decisions: interpretations, scope cuts, **rejected AI output**, **bugs caught** and how, course corrections. |
| [`PROMPTS.md`](PROMPTS.md) | The prompts used to direct the AI, in order, each with what was produced and how it was checked. |

Some highlights of the validation work, all in `DECISIONS.md`:
an H2 bug that would have broken every insert 30 minutes after startup (D20); a Spring transaction
trap that silently lost every notification (D33); the secret Slack webhook URL leaking into the
database via error messages (D41); XSS-hardening checked with a real headless browser (D46–D48);
mutation checks proving the tests actually guard what they claim (throughout).

## What was built

- **"Something important" = severity.** Every event gets `LOW`…`CRITICAL` from its source
  (earthquake magnitude, % price move, keywords in news titles). Users subscribe to a category with
  a minimum severity.
- **Real, key-free sources:** USGS earthquakes, BBC World RSS, CoinGecko prices — plus a **fake
  source** for demos and tests. Each can be switched on/off and has its own polling interval.
- **Channels:** email (SMTP) and Slack (incoming webhooks), behind one `NotificationChannel`
  interface (Strategy). Adding a channel = one class + one Liquibase seed row; a startup check
  refuses to start if the two don't match.
- **Delivery:** new events are fanned out to subscribers after they are safely stored (Observer),
  one notification per user × channel, recorded in a delivery log. Failures are retried with
  backoff (1m, 5m, 15m), then marked `FAILED_PERMANENTLY`. Events older than 10 minutes are stored
  but not notified (no burst of old news after a restart).
- **Admin view:** a REST API (`/api/admin/**`) and a plain HTML/JS page on top of it at `/`.
  HTTP basic auth, CSRF protection, strict Content-Security-Policy.

Stack: Java 21, Spring Boot 4.1, Maven, Spring Data JPA / Hibernate, Liquibase, H2 (in-memory),
Spring Security, JUnit 6 + Mockito.

## Running it

Requirements: **Java 21** and **Maven 3.9+**. Optional: Docker, to see the emails.

```bash
# 1. Optional: a local mail catcher, so sent emails can be seen at http://localhost:8025
docker run -d --rm --name alerting-mailpit -p 8025:8025 -p 1025:1025 axllent/mailpit

# 2. Start the app (choose your own admin password)
export SPRING_SECURITY_USER_PASSWORD=change-me
mvn spring-boot:run
#   or: mvn package && java -jar target/alerting-service-0.1.0-SNAPSHOT.jar
#   port 8080 busy? add --server.port=8081 (or -Dspring-boot.run.arguments=--server.port=8081)
```

Open **http://localhost:8080** and log in as `admin` / your password. If you don't set
`SPRING_SECURITY_USER_PASSWORD`, Spring Boot generates one and prints it in the startup log
(`Using generated security password: …`).

The real sources start polling immediately. Their first run stores the current feed contents
(e.g. a day of earthquakes) but, by design, doesn't notify them — they are older than 10 minutes.
Use a test event to see a notification right away.

### Demo in the browser

1. **Users** → add a user → subscribe to *Market movements* with min severity *HIGH* → add an
   *Email* channel with any address (e.g. `alice@example.com`).
2. **Detection** → *Send a test event*: category *Market movements*, severity *CRITICAL*.
3. The email is in Mailpit (http://localhost:8025); **Notifications** shows it as `SENT`.
   Without Mailpit it shows `FAILED` with `Connection refused`, and is retried automatically —
   or press *Retry now*.
4. **Events** lists everything detected, including the live USGS/BBC data.

For Slack, create an [incoming webhook](https://api.slack.com/messaging/webhooks) and add it as
the user's *Slack* channel address (`https://hooks.slack.com/services/…`).

### Demo with curl

Writes need the CSRF token: read it from the `XSRF-TOKEN` cookie and send it back as the
`X-XSRF-TOKEN` header. The cookie is re-issued on responses, so read it before every call.

```bash
BASE=http://localhost:8080/api/admin
AUTH=admin:change-me
JAR=$(mktemp)
curl -s -u "$AUTH" -c "$JAR" -o /dev/null "$BASE/users"          # get the CSRF cookie
call() {  # method path json
  TOKEN=$(awk '$6=="XSRF-TOKEN"{print $7}' "$JAR")
  curl -s -u "$AUTH" -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $TOKEN" \
       -H 'Content-Type: application/json' -X "$1" "$BASE$2" -d "$3"; echo
}
call POST /users '{"name":"Alice"}'
call PUT  /users/1/subscriptions/MARKETS '{"minSeverity":"HIGH"}'
call PUT  /users/1/channels/EMAIL '{"address":"alice@example.com"}'
call POST /fake-events '{"category":"MARKETS","title":"Demo: Bitcoin down 25% in 24h","severity":"CRITICAL"}'
curl -s -u "$AUTH" "$BASE/notifications?status=SENT"; echo
```

### Admin API

All under `/api/admin`, all require the admin login. Errors are RFC 9457 problem details;
validation errors include an `errors` map (field → message).

| Endpoint | Purpose |
|---|---|
| `GET/POST /users`, `GET/PUT/DELETE /users/{id}` | Users. Delete also removes their subscriptions, channels and notification history. |
| `PUT/DELETE /users/{id}/subscriptions/{category}` | Subscribe with `{"minSeverity": …}` (create or update). |
| `PUT/PATCH/DELETE /users/{id}/channels/{channel}` | Link a channel `{"address": …}` (validated by the channel); PATCH `{"enabled": …}` toggles it. Slack URLs are masked in responses. |
| `GET /categories`, `GET /channels`, `PATCH /channels/{code}` | Reference data; switch a channel off for everyone. |
| `GET /events?category&source&minSeverity&page&size` | Detected events, newest first. |
| `GET /notifications?status&userId&page&size` | Delivery log, newest first. |
| `POST /notifications/{id}/retry` | Retry a `FAILED` notification now (409 for any other status). |
| `GET /sources`, `POST /detection/run` | Source status; run all enabled sources now. |
| `POST /fake-events` | Inject a test event and run it through the real pipeline. |

The H2 console is at `/h2-console` (same login; JDBC URL `jdbc:h2:mem:alerting`, user `sa`,
empty password).

## Configuration

Everything is in [`application.yml`](src/main/resources/application.yml); the most useful knobs:

| Setting | Default | Meaning |
|---|---|---|
| `SPRING_SECURITY_USER_PASSWORD` (env) | generated | Admin password. `ADMIN_USERNAME` changes the user name (default `admin`). |
| `MAIL_HOST` / `MAIL_PORT` (env) | `localhost` / `1025` | SMTP server for the email channel. |
| `ALERTING_EMAIL_FROM` (env) | `alerts@alerting.local` | Sender address. |
| `alerting.sources.<name>.enabled` / `.interval` | all on; 15s–10m | Per source: `fake`, `usgs`, `rss`, `coingecko`. |
| `alerting.sources.rss.high-severity-keywords` | `breaking, killed, …` | Whole words; `evacuat*` = prefix. |
| `alerting.notification.max-event-age` | `10m` | Older events are stored but not notified. Keep source intervals below it. |
| `alerting.notification.retry.max-attempts` / `.backoff` | `4` / `1m, 5m, 15m` | Attempts include the first send. |

## Tests

```bash
mvn verify
```

151 tests; they never touch the internet (live polling is switched off in tests and real sources
are parsed from captured real responses). They include persistence tests against the real
Liquibase schema, an end-to-end test with a real SMTP server (GreenMail) and a real HTTP server
for Slack, security tests over real HTTP (login, CSRF, CSP), and API tests for every endpoint.

## Known limitations

Deliberate scope cuts and known gaps (details in `DECISIONS.md`):

- Single admin, no end-user login or self-service (D7).
- H2 in-memory: data is lost on restart (D1).
- Single instance only: no distributed locking for the scheduler or retries (D35, D39).
- A notification stuck in `PENDING` after a crash mid-send is not picked up again (D39).
- Sending is synchronous in the detection thread, bounded by HTTP timeouts (D32).
- An event reaching us more than 10 minutes after it happened is never notified (D38).
- A user's categories go to all their channels; no per-category routing, digests or quiet hours.
- CoinGecko reports only the 24h change, so market events are "big move today", not intraday spikes (D28).

## Project layout

```
src/main/java/com/sonrise/alerting/
  domain/        JPA entities, enums
  repository/    Spring Data repositories
  source/        event sources (Template Method), EventStore (dedup + save + publish)
  detection/     scheduler (per-source intervals, overlap guard, runNow)
  notification/  dispatcher (Observer), sender, retry policy + job
  channel/       NotificationChannel (Strategy): email, Slack; registry; startup check
  admin/         REST controllers, services, DTO records, error handling
  config/        security, scheduling, clock
src/main/resources/
  db/changelog/  Liquibase changesets
  static/        admin page (index.html, app.js, app.css)
```
