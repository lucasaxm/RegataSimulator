# Backend review

Reviewed: 2026-10-06. Status: recommendations, **not implemented or approved architecture decisions**.

Subsequent development-only smoke testing is recorded in [live test results](live-test-results.md). It confirms several error paths and refines the CORS assessment below; production was not tested.

This is a historical review, not a current open-issue inventory. [The implementation plan](backend-improvement-plan.md) and [Phase 5 results](phase-5-operations-results.md) record completed local/offline Phases 0–5 and separate live-adoption prerequisites. Removed workflow classes below are historical evidence: inspect them in the parent of local cleanup commit `aab05e2` with `git show aab05e2^:<source path>`, or consult the [removal manifest](phase-3-cleanup-removal-manifest.md). They are intentionally absent from current source.

## Executive decision

| Question | Recommendation | Reason in this codebase |
| --- | --- | --- |
| Keep the chain of responsibility? | Gradually replace the custom workflow runner with explicit, typed application services. Keep lightweight Telegram routing. | Most flows are short, fixed sequences; the framework adds enum transitions and an untyped data bag without durable execution, retries, or recovery. |
| Replace JsonDB with SQLite? | Yes, assuming one application deployment on a server with persistent local disk and modest write concurrency. Prefer Spring JDBC over JPA. | Transactions, uniqueness constraints, indexed queries, and versioned schema changes improve reliability without introducing a database server. |
| Hide domains in CORS? | Externalize configuration, not encrypt it. First establish whether cross-origin API access is needed at all. | Domains are public. Java literals and environment coupling warrant improvement; tested runtime preflight handling works. |
| Rewrite everything? | No. Add behavior tests, fix safety issues, introduce boundaries, then migrate one concern at a time. | Database, filesystem, rendering, and Telegram side effects are currently intertwined; replacing all of them together is unnecessarily risky. |

Scope: all backend packages, tests, profiles, Gradle configuration, CI/deployment, and static client integration points. Frontend design/implementation review is deferred. No production database, traffic measurements, deployment host, reverse-proxy configuration, or live Telegram behavior was inspected. Dataset size, observed race frequency, and production performance are therefore unknown.

## 1. What the application actually does

See [project map](project-map.md) for the package map and runtime setup.

- One Spring Boot application serves static pages and authenticated administration APIs, consumes Telegram long-polling updates, renders memes using external ImageMagick processes, and runs scheduled publishing/backups.
- Sources, templates, users/authors, and meme history are JsonDB collections. Images live separately in UUID directories.
- Source/template previews persist `REVIEW` records. A later Telegram confirmation forwards the preview to the creator for approval; it does **not** itself approve the record. HTTP administration performs approval/rejection.
- Web authentication uses form login and in-memory Spring Session storage. It is not JWT authentication or Telegram identity authentication.
- There are 71 main Java files, 21 concrete workflow steps, six concrete Telegram routes, and three REST controllers in the reviewed tree.

### Verified baseline

This is the historical pre-Phase-0 baseline. [Phase 0 results](phase-0-results.md) supersede the dormant-context/coverage assessment below; [callback results](phase-1-callback-safety-results.md) record the now-fixed callback authorization/binding defects and the latest 590-case suite. Other diagnosed defects remain deferred unless explicitly marked implemented in the plan.

`./gradlew clean test` passed on OpenJDK 21.0.2 using Gradle 8.6: **12 tests, zero failures/errors/skips**.

| Suite | Tests |
| --- | ---: |
| `SendMessageStepTest` | 1 |
| `BuildPongMessageWorkflowStepTest` | 1 |
| `PingRouteTest` | 4 |
| `RouterServiceTest` | 4 |
| `TelegramUtilsTest` | 2 |

The application-context test in `BilubotApplicationTests.java` is entirely commented out. No existing tests exercise persistence, rendering, moderation, imports, backups, controllers, or the security filter chain. No coverage instrument was configured/run; a coverage percentage would be speculation. Editor diagnostics also report Java files as non-project files, which is a VS Code project-import issue, not evidence of Gradle compilation failure.

## 2. Workflow architecture: concrete analysis

### This is a small state-machine runner, not classic chain of responsibility

In the former `service/RouterService.java`, every matching `Route` started a flow. `startFlow` created a fresh `WorkflowDataBag`, repeatedly resolved an enum through `WorkflowManager`, and ran the associated step. Each step returned the next action.

Classic chain of responsibility usually lets successive handlers decide who will handle a request. Here, steps explicitly select the next state. That distinction matters: the application is paying for a workflow engine, not merely using a set of command handlers.

### Actual execution sequences

| Use case | Current sequence |
| --- | --- |
| Ping | `BuildPongMessageStep` → `SendMessageStep` |
| Publish meme | `GetRandomTemplateStep` → `GetRandomSourceStep` → `BuildMemeStep` → `SendMemeStep` |
| Create source preview | `CreateSourceStep` → `GetRandomTemplateStep` → `BuildMemeStep` → `SendMemeStep`; the template step takes a special single-area preview branch and preserves the submitted source |
| Create template preview | `CreateTemplateStep` → `GetRandomSourceStep` → `BuildMemeStep` → `SendMemeStep` |
| Confirm/cancel preview | Separate callback invocation of `ConfirmReviewSource/TemplateStep` or `DeleteReviewSource/TemplateStep` |
| Approve/reject | Controller saves status, constructs a synthetic Telegram `Update`, then starts a notification step |
| Backup | `BackupJsonDBStep` → `BackupTemplatesStep` → `BackupSourcesStep` → `SendReportStep` → `SendMessageStep` |

The workflow bag exists only for one synchronous invocation. Waiting for approval is modeled by database status and a later request, not by a paused/persisted workflow. There is no need for durable workflow orchestration demonstrated by these flows.

### Benefits worth preserving

- A recognizable Telegram routing boundary and reusable selection/rendering/sending behavior.
- Small components that can be tested independently.
- Explicit branches for publishing versus previews.
- A separation between web administration and Telegram update reception.

None of these benefits requires enum transitions, annotation registration, or an object-valued data bag. Reusable services can still be independently tested and called from several use cases.

### Costs and failure modes in the actual implementation

1. **Data contracts are implicit.** The former `flows/WorkflowDataBag.java` stored `Object` values keyed by enum. `getGeneric` checked raw type and generic arity, not the types of list elements. Missing entries returned null. Required inputs were discoverable only by reading each step.
2. **Business behavior depends on incidental transport state.** The former `flows/simulator/GetRandomTemplateStep.java` and `SendMemeStep.java` detected preview mode by the presence of progress messages. Publishing used a null `Update` to mean channel delivery. A typed preview/publish request would express intent directly.
3. **HTTP code knows workflow internals.** Source/template controllers synthesize `Update` objects; rejection puts a reason in `channelPost`, although no channel post occurred. Domain operations should accept an ID, decision, reason, and actor instead.
4. **Normal completion and failure look alike.** Several selection/creation/rendering steps log failures and return `NONE`. `RouterService` does not swallow thrown exceptions, but it cannot distinguish those logged failures from success. An absent registration also ends the loop silently. Exceptions can instead escape all the way through the Telegram or HTTP entry point.
5. **Registration has a concrete defect.** The former `flows/WorkflowManager.java` method `getStepEnum` dereferenced `annotation.value()` before checking whether the annotation existed. An unannotated step caused a null dereference instead of the intended warning. There was also no transition-count guard against a cycle.
6. **Routing policy is unspecified.** `routes.forEach` executes every match, not first-match dispatch. Existing predicates may be disjoint, but future overlap can trigger duplicate work. Choose and test first-match, exclusive-match, or deliberate multi-handler behavior.
7. **The abstraction does not supply the difficult guarantees.** No durable execution state, transactional grouping, centralized cleanup, bounded subprocess execution, retry policy, or idempotency is provided by the runner.

### Recommended target: a conventional modular monolith

Keep Spring Boot and the existing deployment shape. Do not introduce microservices, an event broker, a new workflow engine, or a generic processor framework.

- Thin HTTP controllers and Telegram command/callback adapters translate transport inputs into typed use-case requests.
- `SourceService` and `TemplateService` own submission/moderation operations and consistent status rules; extract a dedicated submission service only if those classes become unwieldy.
- `MemeService` exposes explicit operations such as publish, source preview, and template preview. It calls shared selection and rendering components using typed inputs/results.
- `BackupService` orchestrates a consistent snapshot, packaging, delivery, and cleanup directly.
- `TelegramGateway` centralizes external sends, rate-limit handling, timeout/error translation, and sanitization. Business services should not depend on a workflow bag or fabricated `Update`.
- Domain-specific repositories isolate JsonDB now and SQLite later. `MediaStorage` isolates file operations; `ImageRenderer` isolates ImageMagick.

Use Java records for request/result types where useful. Do not create one new service for every old step. Preview and publish operations can share selection/rendering without sharing an untyped context.

**Expected gain:** easier navigation, compile-time contracts, clearer error and transaction boundaries, and tests that describe whole use cases. **Not guaranteed:** faster rendering or automatically correct side effects. Those require separate fixes.

Retaining the runner temporarily is reasonable for compatibility. Add bounded execution, explicit missing-registration errors, and distinguish failed steps from completion while migrating. Building a new runtime contract framework for the bag would invest further in the complexity we want to remove.

## 3. Persistence: SQLite versus JsonDB

### Why SQLite is a better fit here

Current data is structured: stable IDs, review status, selection weights, geometry, author identity, and history references. This is a good relational model, even though it is currently serialized as documents.

| Existing behavior | Concrete benefit from SQLite |
| --- | --- |
| Source upload scans for duplicate description, then inserts separately | A unique normalized-description key arbitrates concurrent inserts; an application-level precheck alone cannot do this |
| Publishing separately adjusts several weights and inserts/prunes history | One short transaction can atomically commit the database changes |
| Gallery filtering/sorting/pagination happens after collection loading | SQL `WHERE`, deterministic `ORDER BY`, `LIMIT`/`OFFSET`, count queries, and suitable indexes |
| Ownership/date are extracted from full Telegram `Message` objects | Explicit author/chat/message/date columns can support normal queries and transport-independent domain models |
| No application-managed schema migration sequence | Versioned migrations, repeatable setup tests, and explicit compatibility policy |
| History references can outlive deleted assets | Explicit deletion/retention policy and enforceable foreign keys where appropriate |

JsonDB was a reasonable way to get this working quickly. Its internal synchronization should not be confused with application-level transactions: the reviewed code does not make duplicate-check/insert, weight/history changes, or cross-collection operations atomic. This review does not claim that JsonDB has no internal locks or that corruption has occurred.

### Deployment conditions and limits

Recommend SQLite for the deployment shape shown by the SSH-to-one-host workflow, **subject to confirming that this is still the intended production setup**.

- Store the database on persistent local disk, outside the JAR/build directory, with write access for the application user.
- SQLite supports concurrent readers and multiple local processes, but only **one writer at a time per database**. It is not a row-locking server database.
- WAL can improve read/write coexistence; keep transactions short, configure a bounded busy timeout, and test overlapping writes using the actual JDBC driver.
- WAL is unsuitable for sharing one database across hosts via a network filesystem. Choose PostgreSQL if multi-host replicas, sustained concurrent writing, or managed database operations become requirements.
- Do not hold a write transaction while downloading, rendering, uploading to Telegram, or waiting for moderation.
- Reliability does not require waiting for hundreds of records or measurable slowness before migrating. Conversely, no measured speedup is promised: ImageMagick and network calls may dominate runtime.

### Java integration recommendation

Use `spring-boot-starter-jdbc`, an actively maintained `org.xerial:sqlite-jdbc` version, explicit repository queries/row mapping, and versioned migrations. Select/test a migration tool version with SQLite support; do not assume MySQL modules or an ORM dialect make SQLite support automatic.

Prefer Spring JDBC (`JdbcTemplate`/`NamedParameterJdbcTemplate`, or `JdbcClient` if appropriate) over JPA here: there are few entities, simple operations, and no existing JPA model. JPA would introduce dialect compatibility and schema/locking behavior that do not serve the simplicity goal. Spring Data JDBC is also not automatically interchangeable with plain Spring JDBC for every dialect.

Connection/bootstrap requirements:

- Explicitly enable foreign keys on **every connection**, before beginning transactions.
- Configure busy timeout and choose a small connection pool based on tests rather than a large default.
- For reliability-first WAL operation, start with `synchronous=FULL`; document any later durability/performance tradeoff.
- Check the bundled SQLite engine version, not just the JDBC artifact name. Official SQLite documentation reports a WAL-reset corruption bug fixed in **3.51.3**, with certain earlier backports. Use a currently patched driver/engine; do not copy a 2024 driver pin into the migration.
- Use file-backed SQLite in integration tests so locking/WAL/connection settings match deployment; H2 is not a substitute for these tests.

### Proposed schema direction, not a final migration script

| Table | Fields/relationships to preserve |
| --- | --- |
| `authors` | Existing Telegram user ID as 64-bit integer; names and optional username |
| `sources` | UUID, description, normalized description key, status, weight, nullable author, original chat/message/thread identifiers, created/reviewed timestamps, image metadata |
| `templates` | UUID, status, weight, nullable author, original message identifiers, timestamps, image metadata |
| `template_areas` | Template ID, ordered area index, source slot, all four integer corner pairs, background flag |
| `memes` | UUID, template reference, published chat/message ID, publication timestamp |
| `meme_sources` | Meme ID, source reference, and position; preserve order rather than treating this as an unordered set |

If minimizing the first migration is important, ordered geometry can initially remain a validated JSON text column. Normalize what the application needs to query or constrain; do not normalize blindly.

Decide description uniqueness explicitly: upload checks case-insensitively, CSV import currently checks differently. Define a consistent trim/case/Unicode policy in Java and store a unique normalized key. SQLite's built-in `NOCASE` is not a full Unicode normalization policy. Audit duplicates before enabling the constraint; never silently discard records.

Preserve nullable origin metadata: CSV-created sources have `message=null`. Current sort comparators use `Message.date` or zero; establish a timestamp policy for imported/legacy data rather than inventing historical dates.

Choose history retention/deletion rules before adding foreign keys. Options include soft-deleting media records, rejecting deletion of referenced records, or preserving history snapshots with nullable references. A default cascade could unintentionally erase publication history. Keep review status separate from storage lifecycle if adding pending/deleting states.

### What SQLite will NOT fix

Database transactions cannot atomically include filesystem moves/deletes or Telegram API calls. Merely putting `@Transactional` around the current workflow is not sufficient; checked exception rollback rules and Spring proxy boundaries also need tests.

- Stage files in a unique job directory, validate them, and promote them to final storage with compensation/reconciliation for crash windows.
- For deletion, use an explicit lifecycle/tombstone plus retryable file cleanup, or a documented compensating strategy. A partial file deletion cannot be rolled back by SQL.
- Commit moderation before notification and define how notification failure is retried without reversing the decision. A small persisted notification/outbox table may be justified; a broker is not necessary.
- For publishing, acknowledge the ambiguous case where Telegram accepted a send but the response or later database write failed. Persist an operation record/result where practical and avoid blind retries that can post duplicates. Do not promise exactly-once delivery.

### Safe migration and backups

Introduce repositories while retaining JsonDB; migrate only after use-case and repository contract tests exist. See [implementation plan](backend-improvement-plan.md) for cutover steps.

Export all four collections from a consistent snapshot, preserve UUIDs, source order, weights, review states, author IDs, geometry, and needed origin metadata. Check duplicate descriptions, null fields, missing images, and orphaned history references before import. Keep the original database and media backup unchanged.

For SQLite, use the online backup API or a verified `VACUUM INTO` snapshot. Do **not** zip/copy just a live `.db` file in WAL mode: committed data may still be in `-wal`, and copying files independently is not a consistent snapshot. Finish and validate the snapshot before publishing it. Back up images with a matching manifest/checksums and a coordinated mutation boundary; a database snapshot alone is not a complete application backup.

Rehearse restore, not just upload. Keep rollback to the original store available until cutover validation is complete. Once SQLite accepts new writes, reverting to the old JsonDB snapshot would lose them; pause writes and reconcile/export them, or explicitly approve that loss. Avoid dual-write during migration unless a real reconciliation design exists.

## 4. CORS, domain strings, and browser security

### Current configuration

- [CorsConfig](../src/main/java/com/boatarde/regatasimulator/configuration/CorsConfig.java) hardcodes two origins, applies to `/**`, allows credentials and wildcard request headers.
- [SecurityConfig](../src/main/java/com/boatarde/regatasimulator/configuration/SecurityConfig.java) has no explicit `.cors(...)` call, but live dev tests verified that allowed cookie-less preflight returns 200 and an untrusted origin returns 403. Absence of that call alone does not establish broken integration; retain full-chain regression tests when changing configuration.
- [SessionConfig](../src/main/java/com/boatarde/regatasimulator/configuration/SessionConfig.java) hardcodes a stage cookie domain, forces Secure cookies for dev, and defines no custom prod cookie serializer. Prod uses framework defaults rather than the exact stage policy.
- [api.js](../src/main/resources/static/api.js) uses relative URLs and `window.location.origin`: normal administration is same-origin. Telegram embedding does not by itself make these API requests cross-origin; iframe policy is a separate concern.

### Recommended approach

1. Confirm browser/API hosting topology. If UI and API stay same-origin, remove unnecessary cross-origin access rather than preserve an allowlist that nobody needs. Same-origin requests do not require CORS permission.
2. If cross-origin clients are required, bind validated `@ConfigurationProperties`, e.g. `regata-simulator.web.cors.allowed-origins`, supplied per environment through YAML/environment deployment configuration. Deny cross-origin access by default; do not default production to localhost or `*`.
3. Keep one authoritative policy integrated with the security chain; the existing MVC policy can be retained if full-chain checks pass. An explicit `UrlBasedCorsConfigurationSource` is an alternative, not a required fix for a demonstrated failure. Avoid conflicting MVC/controller/security policies and scope grants to endpoints needing cross-origin access, including login/logout where required.
4. Allow exact origins (scheme, host, port), needed methods and headers, and credentials only where necessary. Never reflect arbitrary `Origin` values or combine wildcard origins with credentialed access.
5. Configure the actual Spring Session `CookieSerializer`, not just servlet cookie properties. Prefer host-only cookies unless subdomain sharing is truly required. Make Secure/SameSite policy explicit per environment; test local development and Telegram WebView/iframe behavior. Do not turn SameSite=None on globally without a reason and CSRF protection.
6. Keep authentication/authorization and CSRF protections. CORS is a browser response-access policy, not a firewall or an authorization check. Moving a domain into an environment variable does not hide it from clients.

Test allowed/disallowed origins, lookalike origins, credential-less OPTIONS requests, unauthenticated actual requests, same-origin requests, absent Origin, and credentialed requests through the **full security chain**. Preflight may succeed while the actual request still requires authentication.

Keep CSP `frame-ancestors` for supported Telegram embedding. Disabling X-Frame-Options is not automatically a clickjacking vulnerability when a suitable CSP framing policy is present. Reinstating SAMEORIGIN blindly can break that embedding; audit the required parents and modern browser behavior instead.

## 5. Other backend improvements, prioritized

These are code-evidenced defects/risks, not claims of observed production incidents. Priority reflects potential impact and remediation order.

| Priority | Finding and evidence | Improvement |
| --- | --- | --- |
| P1 | `SecurityConfig` globally disables CSRF despite cookie/form login. `AdminController` exposes meme publishing and backups as GET. | Convert mutations to POST; enable CSRF with token acquisition/submission in login/API clients. Protecting only POST while leaving side-effecting GETs would not fix this. |
| P1 — callback slice implemented | At review time, delete/confirm steps omitted actor ownership, REVIEW status, and preview identity checks. | Fixed on 2026-10-09 with generic rejection, persisted binding, and replay tests; see [results](phase-1-callback-safety-results.md). Broader cross-adapter/crash recovery is still pending. |
| P1 | `BuildMemeStep` uses fixed `resized_source.png`, `distorted_source_temp.png`, indexed distortions, and `final_output.png` inside the chosen template directory. | Give each rendering invocation a unique scratch directory. Concurrent scheduled, HTTP, and preview operations can otherwise overwrite/delete one another's files. |
| P1 | `BuildMemeStep` waits on subprocesses without timeouts, generally ignores exit codes, and does not drain stderr. | Centralize command execution with bounded output capture, exit-code checks, timeout/process termination, interruption handling, and ImageMagick memory/disk/time limits. Keep argument-array `ProcessBuilder`; do not switch to shell interpolation. |
| P1 | `GetRandomTemplateStep`/`GetRandomSourceStep` remove `ceil(poolSize * 0.75)` historically used candidates, then select. A pool of one with that item in history becomes empty; source selection can also leave fewer candidates than required slots. | Bound history exclusion by required capacity; progressively relax recent-history filtering. Return explicit unavailable-data results. Add small-pool and special-date tests. |
| P1 | Creation saves files then source/template and author separately; failure tries `Files.deleteIfExists(newDir)`, which cannot remove a populated directory. Deletion removes files before the database row. | Stage/promote files, use coordinated DB mutations, reliable recursive cleanup, and reconciliation for both missing files and orphan directories. SQLite alone does not solve these windows. |
| P2 | `SourceImporterService` filters duplicates against existing DB records before the batch; duplicate rows within one CSV can both pass. Row download failures are logged/skipped, and final insert may fail after all files were created. | Validate required headers/rows/size, deduplicate normalized names within the batch, enforce DB uniqueness, return per-row success/failure/skip results, and clean failed files. |
| P2 | Template CSV parser checks exact header/count/numbers but not contiguous/unique area indices, source slots, valid geometry, or bounds. Renderer indexes arrays using `index-1` and `source-1`. Route catches only IOException, not NumberFormatException. | Validate semantics before downloads/rendering; resolve slots explicitly or require/test contiguous 1-based numbering; bound dimensions/pixels. Validate actual image content, not only Telegram's MIME/filename. |
| P2 | Controllers lack DTO/pagination constraints; absent records and duplicate decisions throw RuntimeException. APIs return persistence objects containing entire Telegram messages; image endpoints always label bytes PNG although JPEG is allowed. | Add Jakarta validation (and its starter), bounded pagination/body limits, domain exceptions + `ProblemDetail` advice for 400/404/409, explicit response DTOs, and correct detected image content type. Raw exceptions do not prove public stack traces under Boot defaults. |
| P2 | `TemplateController` runs the rejection notification even if origin message is null; `SendTemplateRejectedMessageStep` dereferences the absent message/reason. Status is already saved. | Make notification eligibility explicit and consistent with source moderation. Add regression tests for null-origin metadata and notification failure after status commit. There is no current template-import API; this concerns nullable/legacy metadata. |
| P2 | `TelegramFileDownloader` uses HttpURLConnection without explicit connection/read timeouts and always writes `source.jpg`. Publishing performs Telegram sends before several independent DB updates. | Consolidate Telegram adapters, timeouts, bounded read retries, content checks, and explicit partial-failure handling. Respect rate-limit retry-after; do not blindly retry uncertain sends. Telegram requires token-bearing URL paths: redact them, do not invent bearer-header authentication. |
| P2 | Startup registers the bot before initializing collections; registration errors are logged and startup continues. Health calls Telegram `getMe` synchronously. | Initialize storage first; allow bot/scheduler disablement for tests/local runs; separate local liveness from external readiness and expose failed registration explicitly. Cache/bound remote health checks. |
| P2 | `release-and-deploy.yml` depends on a server command named `subprocess` absent from this repository, runs a restart loop, and validates startup by log text, without automatic rollback. | Verify that host dependency instead of assuming it is a typo. Prefer a documented systemd unit, graceful shutdown, trusted host-key configuration, readiness smoke checks, and rollback after failed deployment. |
| P2 | Boot 3.2.3 and several pinned libraries are old; Spring Session 3.3.2 overrides Boot's managed dependency family. | Inventory resolved dependencies and run vulnerability/license checks; upgrade to a supported patched combination, preferably with BOM-managed Spring versions. Do not claim specific exploitable CVEs without scanning. |
| P3 | `FileUtils.zipInChunks` closes ZIP streams only on normal paths; oversized individual items can exceed target chunk size. Backup zip cleanup happens only after successful send; directories are zipped while mutable. | Exception-safe stream/temp cleanup, actual archive-size checks, complete manifests, coherent snapshots, independent backup retention, and restore tests. |
| P3 | Birthday selection hardcodes names/dates; cron uses scheduler timezone while birthday logic explicitly uses America/Sao_Paulo. Weights/history caps and timing are scattered. | Inject Clock/randomness for deterministic tests, externalize appropriate policies, define timezone explicitly, and fall back if birthday candidates are unavailable. |
| P3 | Single in-memory admin account/session store, demo password-encoder helper, full Telegram response logging, no moderation audit trail. | Use an explicit production password encoder/hash, login throttling, minimized/redacted logs, and reviewer/timestamp audit metadata. In-memory sessions may remain acceptable for one admin if logout-on-restart is intentional. |

### Keep the simple parts simple

Keep the modular monolith, UUID asset IDs, filesystem image storage, normal Spring dependency injection, and small scheduled jobs. Do not add Redis solely to preserve one admin session, Kafka solely for retries, or reactive programming solely because HTTP calls exist. A bounded in-process job executor may be enough; add durable job state only where restart/retry requirements justify it.

## 6. Recommended sequence and unresolved decisions

[The implementation plan](backend-improvement-plan.md) proposes small reviewable changes: tests and safety fixes → configuration/security → typed service boundaries → SQLite repository/migration → recovery and operations hardening. Do not combine the database cutover and whole workflow rewrite in one release.

Before implementation, confirm:

1. Will production remain one application host with persistent local storage?
2. Are there any genuine cross-origin API clients, or only same-origin pages embedded in Telegram?
3. Who may submit, confirm/cancel, approve/reject, and delete each item? Are there legacy records without author/message metadata?
4. Should deleting a source/template retain its meme history, and can previously rejected submissions be reviewed again?
5. Is partial CSV import intended, and what duplicate-description normalization is desired?
6. What are acceptable backup loss/recovery windows and notification/publishing retry behavior?

These questions do not block the review; they prevent silently changing product behavior during migration.

## Primary references

Official documentation was checked for database and browser-security guarantees. Spring's current examples may target newer releases than this app; choose APIs compatible with the version being implemented.

- [SQLite appropriate uses](https://www.sqlite.org/whentouse.html)
- [SQLite WAL: concurrency, local-host requirement, durability, WAL-reset fix](https://www.sqlite.org/wal.html)
- [SQLite 3.51.3 release notes](https://www.sqlite.org/releaselog/3_51_3.html)
- [SQLite online backup](https://www.sqlite.org/backup.html) and [VACUUM INTO](https://www.sqlite.org/lang_vacuum.html#vacuuminto)
- [SQLite foreign-key activation and constraints](https://www.sqlite.org/foreignkeys.html)
- [Spring Security CORS integration](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html)
- [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) and [safe-method/cookie considerations](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html#csrf-when)
- [Telegram Bot API request authentication and files](https://core.telegram.org/bots/api#making-requests)