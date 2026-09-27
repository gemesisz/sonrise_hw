# Prompt History

Chronological log of prompts used to direct the AI (Claude Code, Opus 5.5,
in the terminal, working directly in this repo). Prompts are verbatim; each is
followed by what the AI did and what was checked or corrected.

---

## Session 1 — 2026-09-26

**Prompt 1:**
> This is our task:
>
> *(full task brief pasted — see README / task description)*
>
> Some basic information: I use spring boot + maven, include liquibase for
> database changes, also jpa and hibernate and a h2 memory database is enough
> for this task, if you are done with the basic project settings, we start with
> the planning

AI response: set up the base project only.
- Checked the existing (AI-generated, uncommitted) `pom.xml` and **removed
  Thymeleaf and Spring Mail** — they pre-decided the admin UI technology and
  the email implementation before any planning had happened.
- Checked Maven Central instead of trusting the old pom: it pinned Spring Boot
  3.3.4 (outdated); moved to **Spring Boot 4.1.1**, and Java 17 → **21** to
  match the installed JDK and IntelliJ settings.
- Added Liquibase (`ddl-auto: validate`, so Liquibase alone owns the schema),
  JPA/Hibernate, H2 in-memory + H2 console.
- Validated by building: the first `mvn verify` **failed** — Liquibase
  `includeAll` rejects an empty directory. Switched to an explicit include list
  in the master changelog. Second build green (context-load test passes).

**Prompt 2:**
> okay plans:
> Databases: 1: We want users to be able to set up alerts so they get notified
> when something important happens in the world — like breaking news, market
> movements, natural disasters, that kind of thing. -- based on this, we should
> have a table with users, a table with the 'important things' (categories), and
> also a join table where the users are joined with the categories they are
> intreseted in
> 2: Should work for both email and Slack. Make it flexible enough that we can
> add more channels later. -- based on this, we also need a table for channels,
> and a join table where the users are joined with the channels, also for the
> channels
> I think the user table can be a simple one, we need an admin view too, but I
> do not thing, that we should add, register, login to the functions, maybe a
> user management page, where the admin can add users with emails. The email is
> not a mandatory field, but of course that user cannot be connected to the
> email channel until that field is filled. For later, we can add extra columns
> to the user table, if there are mandatory infos for the new channels (like
> phone number for SMS)
>
> Implementation guide: start with the database tables, and the jpa entities.
> Then do the actual business logic implementation, and then create the admin
> view.
> The database tables should have primary keys and foreign keys.
> We can implement the channels with strategy pattern, so later it is easier to
> add new channels (strategies)
> I think we can use composite pattern for the categories, every user will have
> the list of the categories they are intrested in, and the implmentation could
> start with the check if they want to know about the category news
> API for the admin view: we should use records for the requests and responses,
> but they should be different from the actual entity classes.
> also validate the requests
> I think we should use some cron jobs for the event detection, based on the
> configuration there would be some jobs that are checking the news, and if they
> found something, they will notify the users. (maybe we can skip the composite
> pattern and create a cron job for each category?)
> Also I would add an endpoint for the admin view that can trigger the event
> check. Also we may need a table for storing what is the newest news that was
> sent to the user - we may think about this
> Read this, and also tell me what do you think about it, DO NOT IMPLEMENT
> ANYTHING

AI response (review only, no code). Agreed with: users/categories/join table,
no register/login, strategy pattern for channels, records separate from
entities + validation, PK/FK via Liquibase, manual trigger endpoint.
Challenged / proposed changes:
- Per-channel columns on `users` (phone, slack id…) would force a user-table
  change for every new channel, contradicting "add channels later" →
  proposed a channel `address` on the user↔channel join table instead.
- Composite pattern fits trees, not flat categories → proposed dropping it.
- One cron job per category duplicates scheduling → proposed an
  `EventSource` strategy with one scheduler iterating enabled sources.
- "Newest news sent per user" pointer is fragile → proposed an `event` table
  (dedup on source + external id) and a `notification` delivery log
  (idempotency, retries, admin history).
Gaps raised: no definition of "important" (proposed severity + per-
subscription minimum), no concrete data sources (USGS, RSS, CoinGecko + a
manual/fake source), SMTP for the demo, what "admin view" means and whether it
has auth, layer-by-layer vs thin vertical slice first, test strategy.
Open questions returned to me before filling `PLAN.md` / `DECISIONS.md`.

**Prompt 3:**
> Yes the address channel should go the the user - channel table
> 2: a thin HTML page with REST API and with basic authentication
> 3: layer by layer order
>
> I forgot about the tests, we need mockito and junit for unit tests
> I like your event store solution, also we if there was a failure we can
> retry the sending (until a limit is reached)
> I am okay with dropping the composite pattern, so I am happy if you got any
> other option for what pattern we should use for categories
> I like the fake source solution, but we should keep a few options for real
> sources

AI response:
- Checked before adding dependencies: `mvn dependency:tree` showed
  `spring-boot-starter-test` already brings JUnit Jupiter (6.0.3), Mockito
  (5.23.0) and AssertJ → no new test dependencies needed.
- Noticed the answers skipped the "what counts as important" (severity
  threshold) question — raised it again rather than assuming.
- Presented three pattern options for categories for me to choose from:
  Observer (category → subscribers fan-out, via Spring application events),
  Specification (subscription matching criteria), Template Method (shared
  fetch/normalise/dedupe skeleton for sources).
- Proposed retry design (attempt counter, max attempts, backoff,
  terminal failed status) and a shortlist of real sources.

**Prompt 4:**
> Okay, lets use the severities for "something important"
> and also the observer plus template method

Decisions made here: severity threshold as the definition of "important";
Observer + Template Method chosen from the presented options.

AI response: wrote `PLAN.md` (interpretation of the brief, deliverables, draft
schema, architecture, layer-by-layer phases with per-phase validation, out of
scope) and `DECISIONS.md` (D1–D19).
- Resolved a conflict between two earlier answers rather than silently picking
  one: "email on the user table" vs. "address on user↔channel". Went with the
  latter (later answer) and recorded the consequence — no `email` column on the
  user table (D11).
- Caught that `user` is a reserved word in H2 → table named `app_user`.
- Recorded the known risk of layer-by-layer order and how it is mitigated (D18).

**Prompt 5:**
> Start phase 1

AI response: wrote 8 Liquibase changesets (7 tables + seed data) and the JPA
entities. Validation applied:
- Verified all 8 changesets ran, and proved schema validation actually works by
  temporarily breaking one column mapping (startup failed with
  `missing column [link]`), then restoring it.
- Checked the real jar contents for Boot 4 test annotations instead of assuming
  Boot 3 package names (both `DataJpaTest` and `TestEntityManager` moved).
- Persistence tests found a real schema bug: the raw-SQL CHECK constraints on
  enum columns make H2 fail on every valid insert (`Check constraint invalid`,
  23514 — evaluation error, not a violation). Schema validation at startup did
  not catch it.
- Also caught a weak test that "passed" for the wrong reason (it asserted only
  that the constraint name appeared, not that it was a violation).

**Course corrections from me during Prompt 5** (given while rejecting AI tool calls):
> we do not need the repositories yet

> maybe write this tests after the repositories exist?

AI response: dropped the repositories from phase 1; agreed to move persistence
tests to when repositories exist, but flagged that the CHECK-constraint bug
must be fixed regardless.

**Prompt 6:**
> next step: create repositories and create the Channels with strategy pattern,
> create an interface with code(), validateAddress(address) and send(address,
> event), and implement Email and Slack channels. Also create a registry that
> finds the channel by channel.code

AI response: 7 repositories (only `findByCode` finders on category/channel),
`NotificationChannel` + `EmailChannel` + `SlackChannel` + `NotificationChannelRegistry`,
config, and 34 new tests (Mockito for email, `MockRestServiceServer` for Slack).
Validation applied:
- Checked `mvn dependency:tree` before coding: Boot 4 needs separate starters for mail
  and REST client (D25). Read the real timeout property names from the jar metadata;
  the older `spring.http.client.*` is deprecated.
- All tests passed first time → ran a mutation check on email validation. One guard
  (`contains("@")`) turned out to be dead code — removed (D23).
- Slack tests assert the secret webhook URL never appears in error messages (D24).
- Flagged, not yet fixed: timeouts not proven to reach the RestClient (D24); lazy
  `event.category` could fail outside a transaction (D26).

**Prompt 7:**
> next step:
>
> *(pasted the plan text for steps 2–4; the paste was garbled after step 2)*
>
> Event sources (Template Method)
> - AbstractEventSource holds the fixed sequence: fetch → parse → map to our Event
>   with a severity → skip duplicates → save. Each source only implements fetch and parse.
> - FakeEventSource first, then the real sources: UsgsEarthquakeSource (severity from
>   magnitude), RssNewsSource and CoinGeckoMarketSource. Each one can be switched on or
>   off in config.
> - Tests: parse saved sample responses from each real feed, so tests never call the
>   internet, and check the severity mapping.

AI asked before building (the paste was ambiguous); my answers: **step 2 only**, and
**JDK XML parser** for RSS (no new dependency).

AI response: `AbstractEventSource` template, `EventStore`, fake + USGS + RSS + CoinGecko
sources, config, 47 new tests. Validation applied:
- Fetched the three live feeds *before* writing parsers; fixtures are trimmed real
  responses. Checking the live USGS weekly feed revealed quarry blasts/explosions mixed in
  with earthquakes → filtered, with a real quarry blast in the fixture (D28).
- Found Boot 4 uses Jackson 3 (`tools.jackson`), not Jackson 2 (D28).
- Rejected own output: substring keyword matching (`war` matched "warm") — and a test that
  asserted that bug as expected behaviour. Replaced with whole-word matching (D30).
- Mutation check: removing the XXE protection makes the XXE test fail (D29).
- Not done: a live run of the real sources — no scheduler/trigger exists yet (D31).

**Prompt 8:**
> now do the Observers:
>
> Dispatcher (Observer)
> - Saving a new event publishes EventDetected, but only after the database transaction
>   commits, so we never notify about an event that failed to save.
> - The dispatcher finds the category's subscribers whose minimum severity the event meets,
>   creates one notification row per subscriber × enabled channel, and sends each one.
> - One failed channel doesn't stop the others.

(Step 3, the scheduler, skipped for now — the fake source's `detect()` drives the tests.)

AI response: `EventDetected`, publishing from `EventStore`, `NotificationDispatcher`
(AFTER_COMMIT listener, three short transactions), subscriber query, and an integration test
running the real flow with real commits (only channels mocked). Validation applied:
- All 7 new tests failed at first → real bug: the listener's transaction template joined the
  already-committed transaction, so nothing was saved. The AI had earlier claimed its
  approach "sidesteps" Spring's REQUIRES_NEW rule — wrong; fixed with REQUIRES_NEW (D33).
- One remaining failure was a bug in the AI's own test setup, fixed in the test (D34).
- Noticed a test that never reached the code path its name claimed; rewrote it. Mutation
  check then showed Spring already swallows AFTER_COMMIT listener exceptions — the AI's
  assumption was wrong; corrected the code comment (D34).
- Closed D26 (lazy category) with a test that reads the category outside a transaction.

**Prompt 9:**
> now do the schedulers with runNow()

AI response: `DetectionScheduler` (one tick, per-source intervals, `runNow()`, per-source
overlap lock), `SourceRun` result, scheduling config switchable by property, interval per
source in config, 5 new scheduler tests. Validation applied:
- Spotted before coding that enabling scheduling would make every test context poll the live
  feeds; turned it off for tests via `config/application.yml`, then proved the override works by
  forcing it back on for one run and seeing live polling appear (D36).
- A match in the "no live polling" check turned out to be a simulated failure from a unit
  test — checked rather than assumed.
- Mutation check: disabling the lock makes the concurrency test fail (D35).
- First live run of the real sources: USGS 47, RSS 26, CoinGecko 0 events, no errors (D37).
  It revealed a new issue: every restart re-notifies a full day of old events — raised as an
  open decision.

**Prompt 10:**
> Yes option two, last 10 minutes is enough

AI response: added `alerting.notification.max-event-age: 10m`; the dispatcher stores old
events but does not notify them. Two new integration tests (11 min → not notified, 9 min →
notified). Validation: mutation check (rule removed → test fails); live run held back all 73
first-run events. Noted the side effect: a source interval longer than 10 minutes would miss
events (D38).

**Prompt 11:**
> okay, do the retry

AI response: `RetryPolicy` (4 attempts, 1m/5m/15m), `NotificationRetryJob`, shared
`NotificationSender` (moved out of the dispatcher), due-query, config, 10 new tests.
Validation applied:
- Corrected its own earlier suggestion (3 delays but "3 attempts" was inconsistent) and
  documented the counting rule (D39).
- Integration tests use a controllable clock to check exact due times and backoff.
- Removed an always-true assertion from its own test and fixed the test's misleading name.
- Mutation check: off-by-one in the attempt limit fails both unit and integration tests.
- Listed known gaps instead of hiding them: stuck PENDING rows, multi-instance double sends.

**Prompt 12:**
> now do the end-to-end test

AI response: `EndToEndTest` — scheduler → fake source → dispatcher → real SMTP (GreenMail)
and real HTTP (JDK `HttpServer`) → notification rows → retry; nothing in the app mocked.
Validation applied:
- Checked GreenMail's dependencies before trusting them: it brings a second copy of the
  `jakarta.mail` classes; excluded it (D40).
- Proved the Slack timeouts from D24 actually apply (5s stall → run finished in ~1s).
- **Found a security bug in its own earlier code:** I/O errors put the secret Slack webhook URL
  into `last_error` (database, admin view). D24 had claimed this couldn't happen — the unit test
  only covered one error type. Fixed with redaction, added the missing unit test, corrected D24 (D41).
- Its assumed timeout wording ("timed out") was wrong — the JDK client says "Request cancelled";
  the test asserts elapsed time instead.

**Prompt 13:**
> rewrite the PersistenceMappingTest first

AI response: rewrote the test on the repositories, kept every existing check (including my
own later additions), made constraint assertions check the real root cause, and added tests
for the three repository queries added since phase 1 (D42). Validation applied:
- First run: 2 failures — the AI had assumed Spring's exception wrapper for raw SQL too;
  the root causes were exactly right, so the helper now asserts only the root cause.
- Mutation check: removing the event unique constraint from the changeset fails the test.

---

<!-- Append new prompts below, in chronological order, as work continues. -->
