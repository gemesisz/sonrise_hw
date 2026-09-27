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
