# Plan

Plan of attack for the alerting-service brief, written before any feature code and kept up to
date as phases completed (status marks ✅, decision references Dnn). The reasoning behind each
choice is in [`DECISIONS.md`](DECISIONS.md); the prompts that produced it are in
[`PROMPTS.md`](PROMPTS.md).

## 1. What the brief asks for — and what it doesn't say

| Brief says | Our interpretation |
|---|---|
| "users set up alerts" | An admin manages users and their subscriptions; no end-user register/login. |
| "something important happens in the world" | Events from external sources, each given a **severity** (`LOW`…`CRITICAL`). A subscription has a minimum severity. |
| "breaking news, market movements, natural disasters" | Three seeded **categories**; each is fed by one or more event sources. |
| "email and Slack … add more channels later" | Channels as **Strategy** implementations; the per-user address lives on the user↔channel link, so a new channel needs no user-table change. |
| "admin view" | A thin HTML page on top of a REST API, protected by HTTP basic auth. |
| *(unstated)* where data comes from | Real, key-free public feeds (USGS, RSS, CoinGecko) plus a **fake source** for tests and demo. |
| *(unstated)* how events are detected | Scheduled polling (configurable interval per source) + an admin "run now" trigger. |

## 2. Deliverables

1. **Process docs** — this plan, the decision log, the prompt log.
2. **Database schema** — Liquibase changesets with PKs, FKs, unique constraints, seed data.
3. **Domain layer** — JPA entities and repositories.
4. **Business logic** — event sources, detection scheduler, dispatcher, channel strategies, retry.
5. **Admin REST API** — request/response records, validation, error handling, basic auth.
6. **Admin HTML page** — user/subscription management, events, notification history, triggers.
7. **Tests** — JUnit + Mockito unit tests, `@DataJpaTest` persistence tests, one end-to-end integration test.
8. **README** — how to run, configure sources/channels, and demo the flow.

## 3. Data model (drafted before phase 1; as built, with the differences noted below)

```
app_user        (id PK, name, created_at)
category        (id PK, code UNIQUE, name, description)                 -- seeded: BREAKING_NEWS, MARKETS, NATURAL_DISASTERS
channel         (id PK, code UNIQUE, name, enabled)                     -- seeded: EMAIL, SLACK
user_category   (user_id FK, category_id FK, min_severity,  PK(user_id, category_id))
user_channel    (user_id FK, channel_id FK, address, enabled, PK(user_id, channel_id))
event           (id PK, category_id FK, source, external_id, title, description, url,
                 severity, occurred_at, detected_at, UNIQUE(source, external_id))
notification    (id PK, event_id FK, user_id FK, channel_id FK, status, attempts,
                 next_attempt_at, last_error, sent_at, created_at,
                 UNIQUE(event_id, user_id, channel_id))
```

- `app_user`, not `user` — `USER` is a reserved word in H2.
- `notification.status`: `PENDING → SENT` or `PENDING → FAILED → … → FAILED_PERMANENTLY`.
- As built: deleting a user cascades to `user_category` / `user_channel` in the database;
  `notification` → user is RESTRICT, and the admin API deletes history explicitly (D43).
- As built: no CHECK constraints on the enum text columns (H2 bug, D20); `attempts >= 0` is checked.

## 4. Architecture in one picture (as built)

```
 DetectionScheduler (tick, per-source interval, per-source lock) / admin "run now"
            │
            ▼
 AbstractEventSource (Template Method)  fetch → parse (+ severity) → per candidate:
   ├─ UsgsEarthquakeSource   (NATURAL_DISASTERS)      EventStore: skip duplicates → save `event`
   ├─ RssNewsSource          (BREAKING_NEWS)                     → publish EventDetected
   ├─ CoinGeckoMarketSource  (MARKETS)
   └─ FakeEventSource        (any, admin-injected)
            │  EventDetected, delivered only AFTER COMMIT
            ▼
 NotificationDispatcher (Observer)      skip events older than 10 min; subscribers of the category
            │                            with min_severity ≤ event.severity × their enabled channels
            │                            → PENDING `notification` rows
            ▼
 NotificationSender                     NotificationChannel (Strategy): EmailChannel, SlackChannel
            │                            → SENT, or FAILED + next attempt (RetryPolicy)
            ▼
 NotificationRetryJob                   re-sends due FAILED notifications → FAILED_PERMANENTLY at the limit
```

## 5. Build order — layer by layer

Each phase ends with a green build and its own tests before the next starts.

**Phase 0 — Project setup** ✅
Spring Boot 4.1.1, Java 21, Maven, JPA/Hibernate, Liquibase, H2 in-memory.

**Phase 1 — Database and entities** ✅ (D20, D21)
- Liquibase changesets, one file per table, plus seed data for categories and channels.
- JPA entities (no repositories yet — they arrive with the business logic that uses them, D21).
- Validation: Hibernate `ddl-auto: validate` must pass against the Liquibase schema
  (proven to fail on a deliberately broken mapping); persistence tests that FKs and
  unique constraints actually reject bad data are written against the repositories.

**Phase 2 — Business logic** ✅
0. Spring Data repositories ✅ + `@DataJpaTest` persistence tests rewritten on them ✅ (D42).
1. Channel strategies ✅: `NotificationChannel` interface, `EmailChannel`, `SlackChannel`,
   a registry keyed by channel code, and a per-channel address validator.
   Startup check ✅: every `channel` row has a strategy bean and vice versa (D50).
2. Event sources ✅: `AbstractEventSource` (Template Method) + `FakeEventSource`, then
   `UsgsEarthquakeSource`, `RssNewsSource`, `CoinGeckoMarketSource`; each can be
   enabled/disabled and given its own interval in config.
3. Detection scheduler ✅ + dedupe on `(source, external_id)`; guarded against a
   scheduled run and a manual trigger overlapping.
4. Dispatcher (Observer) ✅: listens for `EventDetected`, matches subscriptions by
   category and minimum severity, creates `notification` rows, sends them.
5. Retry job ✅: backoff, max attempts from config, then `FAILED_PERMANENTLY`.
- Validation: Mockito unit tests for matching, severity mapping, retry rules;
  an end-to-end integration test ✅ (fake source → dispatcher → email via GreenMail,
  Slack via a mock HTTP server, plus retry and timeout; `EndToEndTest`, D40).

**Phase 3 — Admin REST API** ✅ (D43–D45)
- Request/response **records**, separate from entities; Bean Validation on requests.
- Global exception handler returning `ProblemDetail`.
- Spring Security with HTTP basic auth, one admin user from config/env.
- Endpoints (draft): users CRUD, subscriptions, user channels, categories/channels
  list, events list, notifications list + retry, trigger detection, inject fake event.
- Validation (as built): full-context MockMvc tests for every endpoint (validation, 404/409,
  paging), and security tests over real HTTP (401, CSRF, CSP) instead of `@WebMvcTest` slices.

**Phase 4 — Admin HTML page** ✅ (D46–D49)
- Static HTML + JS served by Spring, calling the REST API only (no logic in the page).

**Phase 5 — Wrap-up** ✅ (D50, D51)
- README with run/demo instructions (every command run as documented), review of docs against
  what was actually built.

## 6. How AI output is validated at every step

- Build and run tests after every generated change — never accept code that hasn't compiled and run.
- Check library/API claims against the real source (Maven Central, dependency tree,
  the actual feed format) rather than trusting the AI's memory.
- Review generated schema/entities for missing constraints, wrong nullability, cascade surprises.
- Look for shortcuts: swallowed exceptions, tests that assert nothing, hard-coded secrets,
  "TODO" logic presented as done.
- Rejected or rewritten output is recorded in `DECISIONS.md`.

## 7. Out of scope (for this task)

See also "Known limitations" in the [README](README.md) for gaps found during the build.

- End-user registration, login, self-service subscriptions.
- Choosing a different channel per category (a user's categories go to all their channels).
- Digests, rate limiting, quiet hours.
- Multi-instance scheduling (distributed locks) and persistent storage — H2 is in-memory, data is lost on restart.
