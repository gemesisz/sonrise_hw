# Decisions

Running log of decisions: interpretations of the brief, scope cuts, rejected AI
output and course-corrections. Newest at the bottom.

Format: **context → decision → alternatives considered → why.**

---

## Setup

### D1 — Stack: Spring Boot + Maven, JPA/Hibernate, Liquibase, H2 in-memory
- **Decision:** my choice of stack; H2 in-memory is enough for a take-home.
- **Consequence:** data is lost on restart; accepted.

### D2 — Spring Boot 4.1.1, Java 21 (not the AI-drafted 3.3.4 / Java 17)
- **Context:** an earlier AI-drafted `pom.xml` pinned Spring Boot 3.3.4 and Java 17.
- **Decision:** checked Maven Central for the current release (4.1.1) and matched the
  installed JDK / IntelliJ setting (21).
- **Why:** the AI's version was outdated; verified against the real repository, not memory.

### D3 — Removed Thymeleaf and Spring Mail from the initial pom
- **Context:** the AI-drafted pom already contained them.
- **Decision:** removed; dependencies are added in the phase that needs them.
- **Why:** they pre-decided the admin UI technology and the email implementation before
  any planning. Setup should not smuggle in design decisions.

### D4 — Liquibase master changelog uses an explicit include list
- **Context:** the first build failed — `includeAll` rejects an empty directory.
- **Decision:** list each changeset file explicitly in `db.changelog-master.yaml`.
- **Why:** fixes the failure, and the change order is visible and reviewable in one place.

### D5 — Liquibase owns the schema; Hibernate only validates
- **Decision:** `spring.jpa.hibernate.ddl-auto: validate`.
- **Why:** one source of truth for the schema; a mismatch between entities and
  changesets fails at startup instead of being silently "fixed" by Hibernate.

## Interpreting the brief

### D6 — "Something important" = severity threshold
- **Context:** the brief gives no definition.
- **Decision:** each source maps its events to a severity `LOW / MEDIUM / HIGH / CRITICAL`
  (earthquake → magnitude, market → % change, news → fixed or keyword-based).
  Each subscription has an optional minimum severity.
- **Alternatives:** keyword rules, per-user free-form filters, ML/importance scoring.
- **Why:** simple to build, easy to explain to a PM, and extendable later
  (e.g. with the Specification pattern) without a schema rewrite.

### D7 — No end-user register/login; admin manages users
- **Context:** the brief mentions "users" and an "admin view", nothing about self-service.
- **Decision:** the admin creates users and their subscriptions.
- **Why:** keeps scope on alerting, not identity management.

### D8 — Admin view = thin HTML page over a REST API, with HTTP basic auth
- **Decision:** all logic lives in the REST API; the page only calls it.
  Spring Security basic auth, one admin user from config/env (no hard-coded secret).
- **Alternatives:** REST only (not really a "view"); Thymeleaf (logic split between
  server templates and API).
- **Why:** the API is testable on its own; the page stays trivial.

### D9 — Data sources: real key-free feeds + a fake source
- **Decision:** USGS earthquake feed (natural disasters), RSS (breaking news),
  CoinGecko (markets), each toggleable in config; plus a fake source fed via an admin endpoint.
- **Why:** real sources prove the design works against real data; the fake source
  makes tests and demos deterministic and independent of third-party uptime.

## Data model

### D10 — Categories and channels are tables, linked to users by join tables
- **Decision:** `category`, `channel`, `user_category`, `user_channel`, all with PKs and FKs.
- **Why:** admin-manageable, referentially safe. A startup check ensures every
  `channel` row has a matching strategy implementation (and vice versa).

### D11 — Channel address lives on `user_channel`, not on the user table
- **Context:** my initial plan was an optional `email` on the user table, with extra
  columns (e.g. phone) added later for new channels.
- **Decision:** `user_channel.address` holds the email address / Slack webhook /
  phone number; each channel strategy validates its own address format.
- **Why:** adding a channel must not require changing the user table — that is exactly
  the "add more channels later" requirement.
- **Consequence:** the user table has no `email` column. "Email is optional, and the
  user can't use the email channel without one" follows naturally: no address, no
  `user_channel` row for EMAIL.

### D12 — Event store + notification delivery log (instead of "last news sent" per user)
- **Context:** my initial idea was a table storing the newest news sent to each user.
- **Decision:** `event` (unique on `source + external_id`) and `notification`
  (one row per event × user × channel, unique on that triple).
- **Why:** a "last sent" pointer breaks with out-of-order events and partial channel
  failures. The log gives dedup, idempotency, retries and admin history for free,
  and separates detection from delivery.

### D13 — Retry failed deliveries up to a limit
- **Decision:** `notification` keeps `status`, `attempts`, `next_attempt_at`, `last_error`.
  A retry job re-sends due failures with backoff; after `max-attempts` (config) the
  status becomes `FAILED_PERMANENTLY`. The admin can trigger a retry manually.

### D14 — A user's categories go to all of their channels
- **Alternatives:** per-category channel routing (subscription × channel).
- **Why:** simpler model and UI for v1; can be added later as a new table.

## Patterns

### D15 — Strategy for channels
- **Decision:** `NotificationChannel` interface, one implementation per channel, looked
  up by `channel.code`.
- **Why:** a new channel = one class + one seed row; nothing else changes.

### D16 — Categories: Observer + Template Method (Composite dropped)
- **Context:** my initial plan used Composite for categories. The AI's review pointed
  out that Composite models trees, while our categories are flat and "does this user
  want this category" is a join-table lookup. I agreed, dropped it, and asked for
  alternatives.
- **Options presented by the AI:** Observer, Specification, Template Method.
- **Decision (mine):** Observer + Template Method; Specification kept as a future extension.
  - **Observer** for category → subscriber fan-out: sources publish `EventDetected`
    (via Spring application events, after commit); the dispatcher finds subscribers.
    Detection never knows who is subscribed or how they are notified.
  - **Template Method** for sources: the shared fetch → parse → normalise → dedupe
    skeleton lives in an abstract base; each source implements only fetch/parse.
- **Also considered:** Specification for subscription matching — noted as an extension
  point if matching grows beyond category + severity.

### D17 — One scheduler over all sources, not one cron job per category
- **Context:** I considered a cron job per category.
- **Decision:** a single detection service iterating enabled sources, each with its
  own configured interval; the admin trigger calls the same service.
- **Why:** no duplicated scheduling code; a new source is just a new bean. Overlapping
  scheduled + manual runs are guarded.

## Process

### D18 — Build order: layer by layer
- **Decision:** DB + entities → business logic → REST API → HTML page.
- **Alternative (proposed by AI):** thin vertical slice first (one source + one channel end-to-end).
- **Why:** my preference for a clear, reviewable progression.
- **Mitigation for the known risk** (schema mistakes discovered late): each phase ends with
  its own tests, the end-to-end integration test is written at the end of phase 2 before
  any API work, and schema fixes go in as new changesets, never by editing old ones.

### D19 — Tests: JUnit + Mockito, no extra dependencies
- **Decision:** verified with `mvn dependency:tree` that `spring-boot-starter-test`
  already includes JUnit Jupiter 6.0.3, Mockito 5.23.0 and AssertJ.
  GreenMail / a mock HTTP server will be added for channel integration tests when needed.
- **Amendment (phase 1):** Spring Boot 4 split the JPA test slice out of
  `spring-boot-starter-test`, so `spring-boot-starter-data-jpa-test` was added for
  `@DataJpaTest`. Verified on Maven Central and in the jar: `@DataJpaTest` and
  `TestEntityManager` moved to new packages compared to Boot 3.

## Phase 1 — Database and entities

### D20 — No CHECK constraints on enum (text) columns: H2 2.4.240 bug
- **Context:** the first version of the schema had CHECK constraints restricting
  `event.severity`, `user_category.min_severity` and `notification.status` to their
  enum values. Persistence tests failed on every *valid* insert with
  `Check constraint invalid` (H2 23514) — an evaluation error, not a rejected value.
  Startup schema validation did not catch it (it only compares columns and types).
- **Wrong first diagnosis (AI):** the Hibernate bind log showed enums bound as H2 `ENUM`
  instead of `VARCHAR`, and the AI called that the root cause. The proposed fix
  (`@JdbcTypeCode(SqlTypes.VARCHAR)`) was applied — and the tests still failed. The fix
  was reverted. Lesson: a diagnosis isn't confirmed until the fix makes the failure go away.
- **Actual root cause:** the underlying H2 error was `The database has been closed` (90098).
  Reproduced with plain JDBC, no Spring/Hibernate: in H2 2.4.240, a CHECK that compares a
  text column with string literals (`IN (...)`, `=`/`OR`, inline or via `ALTER TABLE`)
  fails once the connection that created it is closed. Numeric checks are unaffected.
  Liquibase creates constraints on its own connection, which is then closed.
- **Why it matters beyond tests:** the app's connection pool keeps Liquibase's connection
  alive, which hides the bug — until the pool retires it (HikariCP `maxLifetime`, 30 min
  by default). After that every insert into these tables would fail in the running app.
- **Decision:** dropped the three text CHECK constraints; the Java enums already restrict
  what the application writes. Kept the numeric `ck_notification_attempts` (`attempts >= 0`).
- **Note on D18 ("never edit old changesets"):** the changesets were edited in place because
  they had never been applied to any persistent database (H2 in-memory) or committed.
  From the first commit containing them on, fixes go in as new changesets.

### D21 — Repositories and persistence tests deferred
- **Context:** the AI started adding Spring Data repositories and `@DataJpaTest` tests in phase 1.
- **Decision (mine):** phase 1 is schema + entities only; repositories come when business
  logic needs them, and persistence tests are written against those repositories.
- **Note:** the draft persistence test (`PersistenceMappingTest`, using `TestEntityManager`)
  is what exposed the D20 bug — evidence that the schema needs insert-level tests, not
  just startup validation. It is kept as a safety net until phase 2, then rewritten
  against the repositories.

## Phase 2 — Business logic

### D22 — Channel strategy contract
- **Decision (mine):** `NotificationChannel` with `code()`, `validateAddress(address)`,
  `send(address, event)`; `EmailChannel`, `SlackChannel`; a `NotificationChannelRegistry`
  that finds the implementation by `channel.code`.
- **Details (AI, reviewed):**
  - `validateAddress` throws `InvalidAddressException` with a reason instead of returning a
    boolean, so the admin API can later show *why* an address was rejected.
  - `send` wraps every channel-specific failure (`MailException`, `RestClientException`) in
    `NotificationDeliveryException`, so the dispatcher/retry logic handles all channels the same way.
  - The registry fails at startup if two beans claim the same code; lookup is exact (`email` ≠ `EMAIL`).
- **Deferred:** the startup check that every `channel` row has an implementation (and vice versa).

### D23 — Email: SMTP via Spring Mail, local mail catcher by default
- `spring.mail.host/port` default to `localhost:1025` (Mailpit/MailHog), overridable by env vars.
  Spring Boot only creates the `JavaMailSender` when `spring.mail.host` is set, so it has to
  be configured even for tests. Sender address from `alerting.channels.email.from`.
- Address validation: strict `InternetAddress` parsing **plus** a check that the parsed address
  equals the input — strict parsing alone accepts `"Alice <alice@example.com>"`.
- **Rejected AI output:** the first version also required `address.contains("@")`. A mutation
  check (removing each guard and re-running the tests) showed no test failed without it —
  strict parsing already rejects `"alice"`. Removed as dead code. The other guard *was*
  caught by a test, so it stays.

### D24 — Slack: incoming webhooks, URL is a secret
- Address = incoming webhook URL; validation requires `https`, host exactly `hooks.slack.com`
  (so `hooks.slack.com.evil.example.com` fails) and a `/services/` path.
- Error messages never include the webhook URL (anyone holding it can post to the channel);
  a test asserts this.
- HTTP timeouts (`spring.http.clients.connect-timeout: 5s`, `read-timeout: 10s`) so a slow
  Slack can't hang a sender thread. Property names checked in the jar's configuration
  metadata: the `spring.http.client.*` form the AI might reach for is deprecated since Boot 4.0.
- **Not yet verified:** that these timeouts actually reach the `RestClient` built from Boot's
  builder — to be covered by the end-to-end test (step 6).
- **Correction (D41):** the claim "error messages never include the webhook URL" was only true for
  HTTP error responses — I/O errors leaked it. The timeouts are now verified (D40).

### D25 — Boot 4 starters for mail and REST client
- Neither `JavaMailSender` nor `RestClient.Builder` auto-configuration comes with the web
  starter in Boot 4; checked with `mvn dependency:tree` and added `spring-boot-starter-mail`
  and `spring-boot-starter-restclient`.

### D26 — Known risk: lazy `event.category` in channel messages
- Both channels print `event.getCategory().getName()`. `category` is a lazy association and
  `open-in-view` is off, so calling `send` outside a transaction on a freshly loaded event
  would throw `LazyInitializationException`. Unit tests don't hit this (they build events in
  memory). To handle in the dispatcher (step 4): load events with their category, and cover
  it in the end-to-end test.

### D27 — Event source template: what the base class owns
- **Decision:** `AbstractEventSource<T>.detect()` is `final` and fixes fetch → parse →
  (per candidate) skip duplicates → save. Subclasses implement only `fetch()` and `parse(T)`;
  `T` is the raw payload (a response body, or the fake source's queue contents).
  Code and enabled flag are passed to the constructor, not extra abstract methods.
- `parse` returns `EventCandidate`s carrying category and severity, so severity mapping
  stays with the source that understands its data.
- Fetch or parse failure → `EventSourceException`, nothing stored. A single bad candidate
  (unknown category, id too long) → counted as rejected, the rest of the run continues.
- `EventStore.saveIfNew` runs each candidate in its own transaction. Over-long title /
  description are truncated; an over-long URL is dropped (a cut URL is a broken link);
  an over-long external id is **rejected**, because truncating could merge two events.
- **Known limit:** dedup is check-then-insert. Two *concurrent* runs of the same source could
  race; the unique constraint would then fail one insert. The step 3 overlap guard prevents
  concurrent runs.

### D28 — Real sources, checked against live responses before coding
- Fetched all three live feeds first; the test fixtures are trimmed **real** responses
  (captured 2026-09-27), not AI-written samples.
- **USGS** (`2.5_day.geojson`): severity by magnitude — <5 LOW, 5–6 MEDIUM, 6–7 HIGH, ≥7 CRITICAL.
  PAGER `alert` was considered but is usually `null`. Checking the weekly feed showed it also
  contains **quarry blasts, explosions, mining explosions, ice quakes** (42 in one week) —
  these are filtered out (`type == "earthquake"`); the fixture includes a real quarry blast.
- **RSS** (BBC World): default severity MEDIUM, HIGH if the title contains a configured keyword.
  `guid` as id (falls back to `link`); unparseable `pubDate` falls back to detection time.
- **CoinGecko** (`/simple/price`, no key): event when |24h change| ≥ 3% —
  3–5 LOW, 5–10 MEDIUM, 10–20 HIGH, ≥20 CRITICAL. The API is stateless, so the external id is
  `coin:UTC-day:direction:severity` — one event per coin/direction/level per day, a new one if
  the move escalates. The live sample had only <1% moves → correctly produces no events.
- Boot 4 ships **Jackson 3** (`tools.jackson.*`), not Jackson 2 — APIs like `asText()` are now
  `asString()`. Checked with `mvn dependency:tree`.

### D29 — RSS parsed with the JDK XML parser, hardened against XXE
- **Decision (mine):** JDK DOM parser, no new dependency (Rome considered).
- Feeds are untrusted input: DOCTYPE declarations are rejected outright. A test feeds an
  XXE payload; a mutation check (removing the DOCTYPE ban) made that test fail, so it really
  guards the setting.

### D30 — Rejected AI output: substring keyword matching
- **Context:** the first RSS version matched keywords with `title.contains(keyword)` —
  `war` matched "**war**m", "a**war**d", "**war**ning", "**War**saw"; `dead` matched "**dead**line".
  The AI even wrote a test asserting `"Warm weather ahead"` → HIGH as "expected", documenting
  the bug instead of fixing it.
- **Decision:** whole-word, case-insensitive regex; a trailing `*` in config makes a deliberate
  prefix (`evacuat*` → "evacuated", "evacuation"). Tests cover the false positives.

### D31 — Enabling sources; injectable clock
- Each source reads `alerting.sources.<name>.enabled`; the bean always exists and exposes
  `isEnabled()`, so the scheduler (step 3) can skip it and the admin view can still list it.
- A `Clock` bean is injected wherever "now" matters (detection time, CoinGecko day bucket,
  RSS date fallback), so tests use a fixed clock.
- **Not done:** no live end-to-end run of the real sources yet (there is no scheduler or
  trigger to run them) — to be done in step 3.

### D32 — Observer: publish after commit, dispatch in three short transactions
- **Decision:** `EventStore` publishes `EventDetected(eventId)` inside its transaction;
  `NotificationDispatcher` listens with `@TransactionalEventListener(AFTER_COMMIT)`, so an
  event that failed to save is never notified. The payload is only the id; the dispatcher
  loads what it needs itself.
- The dispatcher uses three kinds of short transactions: (1) create all PENDING notification
  rows, (2) send each with **no** transaction open (network calls), (3) record each outcome in
  its own transaction. A crash leaves PENDING/FAILED rows, never a rolled-back batch of
  messages that were actually sent.
- Subscriber matching is one query: enabled channel links, on enabled channels, of users
  subscribed to the category with `min_severity IN (event severity and below)`. Severities are
  stored as text, so "≤" can't be done in SQL; `Severity.andBelow()` builds the list in Java.
- One failed channel → that notification is FAILED with `attempts = 1` and the error; the loop
  continues. `next_attempt_at` is left for the retry step (5).
- **Resolves D26:** events are loaded with `join fetch category`; a test makes the mocked
  channel read `event.getCategory().getName()` outside any transaction.
- **Sending is synchronous** in the detecting thread (simplest, deterministic in tests). A slow
  channel slows detection; bounded by the HTTP timeouts (D24). Async is a possible later change.

### D33 — Bug caught by the tests: AFTER_COMMIT listener joined the finished transaction
- **Symptom:** all 7 dispatcher tests failed; logs showed `Notification null` and nothing was
  ever committed.
- **Cause:** during `AFTER_COMMIT`, the committed transaction's resources are still bound to
  the thread. The dispatcher's `TransactionTemplate` used the default `REQUIRED` propagation,
  so it silently *joined* that finished transaction — writes were never committed.
- **AI's earlier wrong reasoning:** Spring rejects plain `@Transactional` on transactional
  event listeners (it requires `REQUIRES_NEW`). The AI chose a programmatic `TransactionTemplate`
  claiming this "sidesteps the restriction" — it only sidestepped the safety check and walked
  straight into the bug the restriction exists to prevent.
- **Fix:** the template uses `PROPAGATION_REQUIRES_NEW`, with a comment explaining why.

### D34 — Tests that didn't test what they claimed
- A second dispatcher test failure was a **test bug**: it disabled a detached entity and then
  saved freshly loaded copies (`saveAll(findAll())`), so the change was lost. Fixed the test,
  not the code.
- `unexpectedErrorInDispatchDoesNotBreakDetection` threw from the channel registry — an
  error already caught per delivery — so it never exercised the listener-level catch its name
  claimed. Split into two tests; the new one makes subscriber lookup crash.
- **Mutation check on the listener-level catch:** removing it did *not* fail the test —
  Spring itself catches and logs exceptions from AFTER_COMMIT listeners. The AI's assumption
  that they would propagate into the detection loop was wrong. The catch stays (it logs which
  event failed) with a corrected comment; the test stays because it guards the behaviour that
  matters, whichever layer provides it.

### D35 — Detection scheduler: one tick, per-source intervals, per-source lock
- **Decision:** a single `@Scheduled` tick (`alerting.detection.tick`, 15s) runs every enabled
  source whose own `alerting.sources.<name>.interval` has elapsed (fake 15s, USGS 5m, RSS 10m,
  CoinGecko 5m). `runNow()` runs every enabled source immediately and returns a `SourceRun` per
  source (COMPLETED / FAILED / SKIPPED_ALREADY_RUNNING) — ready for the admin trigger endpoint.
- **Alternatives:** one `@Scheduled` method per source (duplicated scheduling code — rejected
  earlier in D17); registering a task per source at startup (more moving parts).
- **Overlap guard:** a `ReentrantLock` per source, `tryLock` — a second attempt while one is
  running is *skipped*, not queued. Per source, so a slow USGS call never blocks the fake source.
  This closes the check-then-insert race noted in D27 (within one instance).
- The last start time is recorded *before* running, so a failing source waits its interval
  instead of being retried every tick. One failing source never stops the others.
- The interval was added to the source constructors next to `enabled`, rather than having the
  scheduler guess config keys from source codes.
- **Verified:** a concurrency test holds one run inside `detect()` while `runNow()` is called;
  a mutation check (lock disabled) makes that test fail.

### D36 — Tests must never poll the live feeds
- **Risk spotted before coding:** with scheduling on, every `@SpringBootTest` context would call
  USGS/BBC/CoinGecko and inject real events into the dispatcher tests' database.
- **Decision:** `@EnableScheduling` sits on a config class guarded by
  `alerting.detection.scheduling-enabled`; `src/test/resources/config/application.yml` sets it to
  `false`. (Boot loads `classpath:/config/application.yml` *in addition to* the main file, so the
  main config still applies.)
- **Verified both ways:** no live source runs in the full test log; forcing the flag back on for
  one test class made live polling appear within 3 seconds.

### D37 — First live run of the real sources (closes D31)
- Ran the packaged app for 40s against the live feeds: USGS 47 events, BBC RSS 26, CoinGecko 0
  (all moves < 3%), fake source every 15s; no errors or warnings; all 73 events dispatched.
- **Found — open decision:** the first run after startup stores the whole current window
  (a day of earthquakes, the full RSS feed) as *new* events. With subscribers already present,
  they would get a burst of old news at every restart (H2 is in-memory, so every restart is a
  first run). Options: skip notifying events that occurred before startup, notify only events
  younger than a configurable age, or treat each source's first run as a silent baseline.
  → resolved in D38.

### D38 — Only notify events younger than 10 minutes
- **Decision (mine):** option 2 of D37, with a 10-minute limit
  (`alerting.notification.max-event-age: 10m`).
- **Where:** in the dispatcher, not the sources: old events are still stored (they belong in the
  admin history), they are just not notified. It is a notification rule, so it lives where
  notification is decided. Measured on `occurred_at`; an event exactly at the limit is notified.
- **Why over the alternatives:** "before startup" doesn't help when a feed re-publishes old items
  while running; a "silent first run" needs per-source state. The age rule covers both cases
  with one config value.
- **Side effect:** an event that reaches us more than 10 minutes after it happened (slow feed,
  long source interval) is never notified. With the current intervals (≤ 10m) a fresh event is
  caught in time, but a longer interval must stay below this limit.
- **Verified:** tests for 11 min (stored, not notified) and 9 min (notified); mutation check
  (rule removed) fails the test; live run held back all 73 first-run events.

### D39 — Retry: 4 attempts, backoff 1m / 5m / 15m, then FAILED_PERMANENTLY
- **Decision:** `alerting.notification.retry.max-attempts: 4` counts every attempt, the first send
  included (1 send + 3 retries); `backoff: 1m, 5m, 15m` is the wait before retry 1, 2, 3 (the last
  delay repeats if max-attempts is raised). Invalid config (0 attempts, retries without delays)
  stops startup.
- **AI self-correction:** the AI had earlier suggested "delays 1, 5, 15 min and 3 attempts" —
  three delays imply four attempts. Made the numbers consistent and documented the counting rule.
- A failure sets `next_attempt_at = now + delay`; at the limit the status becomes
  `FAILED_PERMANENTLY` and `next_attempt_at` is cleared.
- `NotificationRetryJob` (tick 30s, batch 100, oldest due first) re-sends FAILED notifications
  that are due. It uses the user's **current** channel link: a corrected address is picked up; a
  removed or disabled link (or disabled channel) ends the retries without sending
  (`abandon`, no attempt counted).
- Retries ignore the 10-minute event age (D38): that rule decides whether to notify at all;
  once a notification exists, it is delivered.
- **Refactor:** "send, then record the outcome" moved from the dispatcher into a shared
  `NotificationSender` (keeps `REQUIRES_NEW`, see D33), used by both first delivery and retries.
- **Known gaps:** a crash between creating a notification and sending it leaves it PENDING
  forever (D32) — the retry job only picks FAILED rows; and with more than one app instance,
  two retry jobs could send the same notification (no row claiming). Both acceptable for a
  single-instance take-home. A manual "retry now" comes with the admin API.
- **Verified:** integration tests with a controllable clock (not due at 59s, due at 60s; growing
  backoff; give-up after 4; corrected address used; disabled link abandons); mutation check
  (off-by-one in the attempt limit) fails both unit and integration tests.
- **Rejected AI output:** a test ended with `verify(email, never()).send(eq("nobody"), ...)` — an
  assertion that can never fail — and its name promised a case it didn't test. Removed and renamed.

### D40 — End-to-end test with real channels
- **Decision:** `EndToEndTest` runs the whole chain with nothing in the application mocked:
  `DetectionScheduler.runNow()` → fake source → `EventStore` → commit → dispatcher →
  `EmailChannel` over real SMTP (**GreenMail**) and `SlackChannel` over real HTTP (the JDK's
  built-in `HttpServer`, no extra dependency) → notification rows → retry job. Only time is
  controlled (a test clock); real sources are disabled so the test never touches the internet.
- Covered: delivery on both channels (mail recipient/sender/subject/body; Slack JSON body and
  content type); a Slack 500 retried and sent a minute later while email is not sent twice;
  a stalled Slack timing out.
- **Dependency check:** GreenMail 2.1.14 pulls `org.eclipse.angus:jakarta.mail`, an all-in-one jar
  duplicating the `jakarta.mail.*` classes the app already gets from `jakarta.mail-api` +
  `angus-mail`. Excluded it so only one copy is on the classpath.
- **D24 timeouts proven:** with `read-timeout: 1s` and a Slack stalling 5s, the whole run finished
  in ~1s. The expected error wording was wrong: the JDK client reports a read timeout as
  "Request cancelled", not "timed out" — the elapsed time is the real assertion.
- **Known limit:** the timing assertion (< 3s) could be flaky on a very slow CI machine.

### D41 — Security fix found by the end-to-end test: webhook URL leaked into `last_error`
- **Found:** the stalled-Slack test stored
  `I/O error on POST request for "http://…/services/T0001/B0001/SeCrEtToKeN": Request cancelled`.
  I/O errors (timeouts, refused connections) include the full request URL in their message; the
  Slack webhook URL is a secret (anyone holding it can post to the channel), and `last_error` is
  shown in the admin view.
- **Why it slipped through:** D24 claimed URLs never appear in errors, but the unit test only
  covered an HTTP error *response* (404), whose message has no URL. The AI generalised from the
  one case it tested.
- **Fix:** error responses → `Slack webhook returned <status>: <body>`; any other client error →
  the message with the URL replaced by `<webhook URL>`. New unit test for the network-error case;
  the end-to-end test asserts the secret path never reaches the database.

### D42 — PersistenceMappingTest rewritten on the repositories (closes D21)
- Persisting and loading now go through the repositories; the `EntityManager` is only used to
  clear the persistence context (so reloads really hit the database) and for raw SQL the entities
  can't express (a link to a non-existent user, a row without an address).
- **Stricter constraint checks:** every "database rejects this" test now asserts that the *root
  cause* is H2's `JdbcSQLIntegrityConstraintViolationException` naming the expected constraint or
  column — the name-only check is what passed for the wrong reason in D20. The outer exception
  type is deliberately not asserted: repositories wrap it in Spring's
  `DataIntegrityViolationException`, raw SQL in Hibernate's (the first draft asserted Spring's
  type everywhere and failed on the two raw-SQL tests).
- **New coverage** for queries added after phase 1: `existsBySourceAndExternalId`,
  `findWithCategoryById` (category really initialized — D26 depends on it) and `findDue`
  (status filter, due boundary, oldest first, batch limit, everything needed for sending loaded).
- **Mutation check:** removing the unique constraint from the event changeset makes the
  duplicate-event test fail — it tests the database, not the entity annotation.
