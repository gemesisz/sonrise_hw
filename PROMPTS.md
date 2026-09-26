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

---

<!-- Append new prompts below, in chronological order, as work continues. -->
