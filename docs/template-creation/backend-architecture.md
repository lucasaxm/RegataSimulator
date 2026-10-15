# Creator backend architecture and feasibility gates

**Status: proposed production contracts; a first isolated native PoC slice has executed locally, not completed the feasibility/release gate.** No creator endpoints or production text rendering are implemented. The released image-only editor still uses manual document/caption submission. The agreed order is **architecture and rendering proof → complete backend → image-only frontend direct submission → frontend text areas**. See the [delivery phases](proposal.md#incremental-delivery-and-acceptance), [current flow](current-flow.md), [text semantics](text-areas.md) and [native experiment guide](native-text-poc.md).

**Product choices now resolved:** Telegram-only direct submission from the confirmed **Criar** menu WebApp; **text-only templates are valid**; an optional Preview button using client-generated synthetic source images/descriptions; retire manual **template** CSV/document submission at the first API-integrated deployment, with downtime acceptable and no dual live flow. Text is pt-BR, with a few plain fonts, mandatory source descriptions and automatic shrink-to-fit. Authentication proof and native text feasibility are the immediate priorities; persistent server drafts are not necessary for optional preview.

## 1. Architecture recommendation

Keep the Java/Spring modular monolith, thin transport adapters, synchronous typed use cases, both repository adapters and existing moderation. No new Node service, browser renderer, broker or workflow framework is required. Separate these responsibilities:

| Boundary | Responsibility / change from today |
| --- | --- |
| Creator authentication | Verify Telegram launch data and issue a creator-only credential; never reuse ADMIN login or trust a posted actor ID. |
| Media acquisition | Telegram file-ID download and bounded HTTP multipart streaming converge on the same owned-media validation boundary. `SubmissionService.Upload` currently requires a Telegram file ID; do not fake one for HTTP. |
| Template definition | One versioned, typed image/text model, original-pixel geometry, ordered planes and source-slot bindings. CSV becomes an image-only compatibility adapter, not the core domain format. |
| Generation | Select each source slot once, retain its image/description pair, resolve literal text, rasterize and composite through a transport-independent render use case. |
| Final submission | Validate, durably bind request identity/content, persist a confirmed REVIEW item and receipt, then attempt administrator notification. No implicit Telegram Confirm callback for web items. |
| Moderation / publication | Existing administrator decision and publication services consume the new definition/origin projections; approval remains mandatory. Preview never changes publication weights/history. |

**First API design: independent multipart submit and optional native-preview operations, plus authentication, capabilities and receipt lookup.** A submit never requires a preview ID or prior preview call. Durable duplicate protection remains useful even for a few friends; do not introduce OAuth infrastructure, JWT libraries, durable render queues or persistent drafts just for this. Backend mixed rendering is still required before either frontend milestone. Implement phases 2 and 3 separately, but their first public rollout is one coordinated backend/frontend cutover, not a production coexistence period.

### Authentication and notification destination

- `POST /api/creator/session` receives raw `initData` in a bounded JSON body, never a URL. Verify the configured bot's documented HMAC, constant-time comparison, required user, bounded parsing/duplicate fields, freshness and future skew. Use synthetic signatures in unit/filter-chain tests.
- Prefer a narrowly matched creator bearer chain with no cookie authentication and `credentials: omit`; retain the credential only in page memory. Scope any CSRF exception to this chain. Existing ADMIN session/XOR-CSRF, CORS, cookies and frame ancestors remain unchanged.
- Validate ownership on every creator read/write. Public bootstrap is not a public upload endpoint. Standalone/missing-initData access remains editing/export only unless a separate login design is approved.
- A verified user ID identifies the actor, **not an arbitrary notification chat**. Derive an allowed private notification destination from verified launch context/existing bot relationship and write permission, and bind that nullable destination **per submission at acceptance**. Do not accept `chatId`, group targets, names or `initDataUnsafe` as authorization; later actor/profile changes cannot retarget an old item. Delivery can still fail after permission changes; missing/blocked DM permission must not erase a committed submission. An optional submitter-DM outcome is distinct from the administrator outcome and is never a new confirmation requirement.
- The user's chat and BotFather screenshots confirm the **Criar menu WebApp** targets `https://regsim.boatarde.dev/create`. This settles the launch-type question; menu launches provide the signed context used by the proposed verifier. A separate Main Mini App targets the root admin page and need not change. Future authentication implementation still needs positive/negative signed-data tests; the screenshots do not prove HMAC verification already runs.

### Official SDK identity proof and proportionate security

The [official Mini App documentation](https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app) distinguishes raw signed `Telegram.WebApp.initData` from parsed **untrusted** `initDataUnsafe`. The [current hosted SDK](https://telegram.org/js/telegram-web-app.js) exposes those launch fields; it does not validate them in the browser. Java's TelegramBots version is a different dependency and does not determine the browser SDK version.

Keep exactly `https://telegram.org/js/telegram-web-app.js`: this is Telegram's maintained current distribution, already loaded by the editor. Do not invent a numeric/versioned script URL, vendor a stale copy or install a community wrapper to satisfy “latest SDK”. `WebApp.version` describes the client-supported Bot API, not a package version; keep feature detection/old-client fallbacks. The newer `WebApp.Serverless.call` belongs to [Telegram-hosted Serverless](https://core.telegram.org/bots/serverless.md); it does not automatically authenticate this self-hosted Spring/Magick backend or replace multipart upload.

The local code does not configure the launch button because its configuration is external. **The user has now supplied that evidence:** BotFather shows menu title **Criar**, creator URL `https://regsim.boatarde.dev/create`, and a separate fullscreen Main Mini App at `https://regsim.boatarde.dev/` used for administration. Telegram's [menu-launch documentation](https://core.telegram.org/bots/webapps#launching-mini-apps-from-the-menu-button) says menu launches behave like inline WebApp buttons, so this is the appropriate signed-identity launch—not an ordinary URL link or reply-keyboard launch. Keep the creator/menu and main admin entry points distinct; no BotFather or admin-page change is needed to implement creator authentication. A plain standalone browser visit still has no Telegram identity. Validate signed initData end-to-end during implementation in approved development; do not log raw credentials, request them in chat or change production configuration merely to repeat the answered launch question.

Recommended small implementation:

1. Read the raw initData after SDK readiness and POST it to `/api/creator/session` over same-origin HTTPS. Do not rebuild it from `initDataUnsafe.user`; do not send raw credentials in URLs, analytics or local drafts.
2. Bound query bytes/field count, reject malformed percent encoding/duplicates, extract a valid 32-byte `hash`, and form the documented sorted `key=value` LF-separated data-check string from decoded values **excluding the hash field**. Preserve the original `user` JSON string rather than deserialize/reserialize before hashing. Follow the bot-token HMAC algorithm's field rules (including new signed fields), not the separate third-party Ed25519 recipe which excludes both hash and signature.
3. Derive `secretKey = HMAC-SHA256(key = UTF-8("WebAppData"), message = UTF-8(botToken))`, then the HMAC of the data-check string using that secret. Compare hash bytes in constant time. The bot token stays in existing private server configuration; verification needs no Telegram network call or browser secret.
4. Require a signed valid user ID and `auth_date`; reject wrong-bot, altered, stale or excessively future data before trusting identity. Proposed defaults to test: bootstrap age ≤5 minutes and future skew ≤60 seconds, adjustable before enablement—not an SDK guarantee.
5. Issue a securely random opaque credential after verification, mapped in a small bounded in-memory registry to the verified actor. Proposed absolute lifetime 30 minutes, no refresh-token/OAuth/JWT machinery, cookies omitted and no extension through expired bootstrap data. Restart invalidates sessions; safe reopen/reauthentication preserves local work. Limit the registry/bootstrap rate and redact Authorization/initData everywhere.
6. On every submit/preview/receipt request, require the credential, derive actor/ownership from it, validate all inputs and apply simple per-user/upload/global-render caps. Leave ADMIN/session CSRF unchanged. Optional numeric friend allowlisting is authorization beyond Telegram identity, not a substitute for HMAC.

**What this prevents:** a caller cannot change the signed user ID or invent another user's identity and make a valid HMAC without the bot secret. **What it does not promise:** the API can tell that a valid Telegram credential is presented, not that the caller clicked our UI. A legitimate user can call the API directly as themselves; stolen initData/session credentials can be replayed within their allowed lifetime. HTTPS, short lifetimes, no credential logging/storage, ownership and duplicate/admission checks are proportionate safeguards. CORS, Referer, User-Agent, SDK/platform/version presence and hiding the endpoint are not identity proof.

Authentication acceptance tests: synthetic valid/altered/wrong-bot HMACs; signed user versus spoofed body user; hash/field/encoding/age/future/missing-user failures; unknown/new signed fields under the documented algorithm; token expiry/restart/registry bounds; cross-actor receipt access and unchanged ADMIN CSRF. Also demonstrate direct HTTP calls with no/forged credential fail, while an authenticated user remains correctly attributed. Dedicated-bot launch verification supplements these tests; do not paste real initData or tokens into reports.

## 2. Proposed minimal HTTP contract

All names below are recommendations to freeze in phase 1, **not existing APIs**. Responses are bounded DTOs and `Cache-Control: no-store`; do not return persisted Messages, internal paths, preview bindings or unrelated source metadata.

| Endpoint | Request | Successful response |
| --- | --- | --- |
| `POST /api/creator/session` | `application/json`: `{ "initData": "<raw signed query string>" }` | `200`: `{ "accessToken": "<memory-only credential>", "tokenType": "Bearer", "expiresAt": "<UTC instant>" }`. Token values must never enter logs/docs/URLs. |
| `GET /api/creator/capabilities` | Creator bearer | `200`: supported `definitionVersions`, `areaKinds`, byte/pixel/area/text limits, pinned `fontCatalogVersion` and allowed font IDs/weights. Publish only implemented capabilities; no host paths or secrets. |
| `POST /api/creator/template-previews` | Creator bearer; multipart `image`, `definition`, `samples` manifest and bounded client-generated placeholder image parts | `200 image/png`: native synthetic example bytes, `no-store`; no REVIEW item, persistent draft, history/weight or Telegram side effect. See section 5. |
| `POST /api/creator/template-submissions` | Creator bearer; UUID `Idempotency-Key`; multipart `image` binary and `definition` JSON | First commit `201` with `Location` pointing to the owned receipt; identical committed replay `200` with the same submission identity, never a second insert/notification. |
| `GET /api/creator/template-submissions/{requestId}` | Creator bearer; `requestId` is the UUID idempotency key, scoped to actor | `200` committed receipt for lost-response reconciliation. Unknown/nonowned request `404`; in-progress or uncertain operation is an explicit conflict, not permission to submit under a new key. |

The frontend creates one request key per immutable image/definition snapshot and retains it until reconciliation. It streams the original validated file, not base64, a screenshot, a remote URL or a Telegram file ID. HTTP upload replaces manually sending the image through Telegram; the bytes still have to reach the server once.

### Definition payload

Proposed new format **version 1** supports both image and text; legacy unversioned quadrilateral arrays are an explicit compatibility format, not silently reinterpreted version 1. The phase-3 frontend sends only `kind: image`, using this same envelope. Array position defines canonical order/derived 1-based indices; UUID area IDs identify edits independently of order. All coordinates/sizes are original-image pixels. Declared canvas dimensions must match decoded bytes.

Image-only `definition` part example (the image is a separate binary part):

```json
{
  "definitionVersion": 1,
  "coordinateSpace": "ORIGINAL_IMAGE_PIXELS",
  "canvas": { "width": 1000, "height": 800 },
  "areas": [
    {
      "id": "a1111111-1111-4111-8111-111111111111",
      "kind": "image",
      "sourceSlot": 1,
      "plane": "FOREGROUND",
      "corners": {
        "topLeft": { "x": 50, "y": 50 },
        "topRight": { "x": 400, "y": 50 },
        "bottomRight": { "x": 400, "y": 400 },
        "bottomLeft": { "x": 50, "y": 400 }
      }
    }
  ]
}
```

Additional text-area example; font/catalog IDs here are illustrative, not selected or shipped assets:

```json
{
  "id": "b2222222-2222-4222-8222-222222222222",
  "kind": "text",
  "sourceSlot": 1,
  "plane": "FOREGROUND",
  "bounds": { "x": 50, "y": 420, "width": 600, "height": 100 },
  "textPattern": "Olá {{source.description}}",
  "style": {
    "fontCatalogVersion": "catalog-v1",
    "fontId": "liberation-sans",
    "fontWeight": 700,
    "fontSize": 32,
    "color": "#FFFFFF",
    "outline": { "color": "#000000", "width": 2 },
    "boxFill": null,
    "align": "CENTER",
    "verticalAlign": "TOP",
    "lineHeight": 1.2,
    "padding": 4,
    "overflow": "SHRINK_TO_FIT"
  }
}
```

- Use explicit allowed DTO discriminators, not Java class names or unrestricted polymorphic deserialization. Reject duplicate area IDs, unknown versions/kinds/fields, nonfinite values, invalid colors/catalog IDs and kind-incompatible fields. Defaults and numeric bounds must be frozen and represented in capabilities/tests, not inferred from browser CSS.
- Preserve image limits: PNG/JPEG ≤20 MiB, ≤40MP, ≤10,000/side; 1–128 total areas, convex image quads/corners within ±10,000 and positive-area overlap. Text rectangles also need positive width/height, bounded corners and **positive-area intersection** with the canvas; partial off-canvas placement is supported, fully outside or edge/corner-only contact is invalid. Apply separate text/raster/work budgets. A large off-canvas box is not permission for an unbounded allocation.
- **Text-only is supported:** a template still has its base bitmap and 1–128 total areas, but all areas may be text (zero image areas). Dynamic text requires a source slot independently of any image area; literal-only text may use `sourceSlot: null` and consumes no source. For `K > 0`, the referenced-slot union across both kinds is `1..K`, and repeated slots select once. For all-literal definitions, `K = 0` and the selected-source list is empty, not null or a dummy source; do not invoke random source selection/capacity checks or source-weight updates. Normal template moderation, template weight and publication history still apply. No image area must be fabricated to satisfy the old image-only validator.
- Only `{{source.description}}` is substituted, once and literally; unknown/malformed template tokens fail validation. Strings inside a source description are never re-evaluated. Store pattern/style, never a sample's resolved string/font size as permanent definition.
- Text policy is **SHRINK_TO_FIT**, not clip: preserve content/newlines and search bounded positive sizes down to an engineering floor (proposed 1 original-image pixel). Normal source intake requires a nonblank description; no missing-description user option/fallback is needed. Unexpected legacy/imported invalid data still fails explicitly rather than becoming “null” or silently modifying metadata. pt-BR fonts/glyphs and impossible-box handling remain validation/PoC tests; no unbounded shrink/reroll loop or promise that arbitrary content fits a zero/tiny box.
- Image-only CSV adapts losslessly to/from image definitions. Mixed CSV export fails explicitly; never extract just the image subset or flatten text into the template to pretend compatibility.

### Submission response and errors

Example successful receipt:

```json
{
  "requestId": "c3333333-3333-4333-8333-333333333333",
  "templateId": "d4444444-4444-4444-8444-444444444444",
  "status": "REVIEW",
  "submittedAt": "2026-10-14T12:00:00Z",
  "notification": { "administrator": "SENT" }
}
```

`submittedAt` is server time, not client data. Administrator notification may instead be `FAILED`, `SKIPPED` or `UNKNOWN` (ambiguous delivery); none makes the commit unsuccessful. The admin queue remains authoritative. Receipt identity/time remain stable; notification outcome may be finalized after commit. A receipt describes acceptance, not automatic approval or a render job. Return no `202 Accepted` promise unless a separately designed job/status lifecycle is actually implemented.

| HTTP status | Planned meaning |
| --- | --- |
| `400` | Malformed/unsupported definition, geometry, dimensions, text/font/style or multipart parts; bounded field errors. |
| `401` / `403` | Generic invalid/expired credential or forbidden operation; never bypass auth because the SDK is absent. |
| `404` | Unknown/nonowned receipt/draft; do not expose another actor's submission. |
| `409` | Same key/different canonical content, request in progress/uncertain, or optional stale draft/preview. Safe machine codes distinguish these cases. |
| `413` / `415` | Upload/metadata byte limit or unsupported media type; proxy/servlet/application failures must be bounded and recognizable. |
| `429` / `503` | Per-actor quota or global admission/service failure, with bounded retry guidance. After any ambiguous failure, reconcile the original key before a mutation retry. |
| `500` | Generic unexpected failure; no raw process, SQL, token or filesystem detail. |

Use existing ProblemDetail conventions plus safe `code` and bounded `fieldErrors` if approved. Strict unsupported-version/media responses need contract tests through the full filter chain, including multipart rejection before expensive decoding. Re-authentication does not authorize automatic mutation replay.

### Canonical fingerprint and replay semantics

Recommend SHA-256 over a version-tagged, unambiguous combination of the original image-byte digest and canonical validated-definition digest. Canonicalization uses the closed versioned DTO, deterministic property ordering/numeric encoding and explicit defaults; preserve area order/IDs and literal text exactly. JSON key order/whitespace alone must not change it. Do not normalize source names/text, reorder areas or substitute a preview description during hashing. Filename, multipart boundary and refreshed auth credential are not content. Freeze the canonicalization version and golden cases before implementation; store it with the binding so upgrades do not change old-key meaning.

Scope keys to the verified actor, not a short-lived bearer session. Re-authentication by that actor can reconcile the original key; another actor cannot read it. A changed image/definition needs an explicit new action/key, never a silent timeout retry. Receipt `status: REVIEW` describes acceptance, not a promise of the current moderation state; later approval/rejection/deletion must not change the original dedup identity or cause replay to recreate the item.

## 3. Persistence and workflow changes

### Current dependency to remove

`Template.areas` and `ImageRenderer.Request` hold image-only `TemplateArea` values. `CommonEntity.message` supplies origin/author information; `SubmissionOrigin` wraps a real Telegram destination/Message. SQLite currently stores `templates.areas_json`, `message_json`, derived `author_id/chat_id/origin_date` and legacy preview binding fields. These are not already a web-origin/session/receipt design. Do not use null Message to infer web origin: legacy imports also have null origins.

### Proposed model/storage delta

| Model / adapter | Required work |
| --- | --- |
| Template definition | Add a version and a closed typed ordered area list. Recommended SQLite approach: explicit `definition_version` with the existing bounded `areas_json` array holding version-specific area DTOs; legacy version 0 reads old quads, version 1 reads typed image/text areas. JsonDB needs the matching versioned document/codec. Freeze exact fields/checks/change-set numbers in phase 1; this is not executable DDL. Avoid two independently writable image-only/mixed truths. |
| Submission attribution | Persist explicit origin kind (`TELEGRAM_DOCUMENT`, `TELEGRAM_MINI_APP`, legacy unknown/imported), verified author ID/name projection, server submission time and nullable authorized notification destination. Retain original nullable Messages for legacy items; web items have no fabricated Message or preview binding. Existing derived author/date SQL columns must use explicit web attribution, not null out these fields on later saves. |
| Receipt/idempotency | Durable actor + UUID key + canonical image/definition fingerprint + reserved template ID + operation/commit outcome and submission time; unique binding and bounded storage/retention. Keep an immutable dedup binding/tombstone for committed requests even after current-item deletion so a replay cannot recreate an item. Include receipts/attribution in recovery data. |
| Author/gallery/import/audit | Reuse verified Telegram `Author` identity; extend safe projections/accessors instead of making UI/storage consumers navigate a fake Message. Preserve minimal legacy `message.from` display compatibility, author filtering/order, nullable imported origins and immutable moderation audit semantics. Mixed definitions must be readable in admin review and never offered as image-only CSV. |
| Rendering request | Include the validated definition and immutable selected-slot image/description snapshots. Text uses the same selected source as its paired image; literal-only areas need none. |
| Optional preview | Authenticated request-scoped media/render only; no template/receipt/draft persistence. Persistent editor drafts remain deferred, not required to click Preview or Submit. |

Existing JsonDB collections/schema versions, SQLite constraints/indexes, description NFKC/ROOT uniqueness, nullable origins, history UUID/order and compensated deletion remain compatibility obligations. Plan forward schema markers, validators and new receipt storage in **both** engines; do not add SQLite-only correctness hidden behind production's engine choice.

Retain compact committed-key bindings/tombstones independently of verbose notification/receipt retention, including through item deletion/restore. The proposed client UUID has no trustworthy issuance time: simply expiring its tombstone would let an old request create another item. Initially hard-cap/admit durable bindings without silently evicting committed or uncertain keys; D8 must settle capacity and any verifiable expiry/archival protocol before enabling cleanup. Backup retention count is not a receipt TTL. Failed/uncertain deletion never removes the key binding or authorizes re-creation; `.delete-*` bytes retain existing manual inspection/reconciliation rules from the [recovery runbook](../operations-runbook.md), not automatic age-based purge.

### Direct-submit workflow (recommended first delivery)

1. Verify actor, stream to owned temporary media, decode/validate bytes and full definition; derive a canonical content fingerprint. No Telegram message or DB transaction during upload/decode.
2. Serialize/reserve the actor/key operation with a stable template identity. Concurrent/lost/uncertain operations must converge on that identity, not produce a new UUID on every attempt.
3. Persist owned final media and confirmed REVIEW metadata/author/receipt with explicit compensation. SQLite can commit metadata + dedup receipt atomically; filesystem effects remain outside. JsonDB multi-document writes are **not atomic**: persist the binding before finalization, inspect the reserved ID on retry, and fail closed/reconcile uncertain states. A process-local lock is insufficient for crash/restart correctness.
4. Only after acceptance, render/send the administrator review notification under bounded admission/deadlines. Failure/busy/ambiguous Telegram delivery updates a safe outcome, not a rollback or invitation to reupload. Notification retries are not implicit; the item remains in the admin queue. Optional submitter DM uses the authorized destination, not the client payload.
5. Return/reconcile the receipt. The user's explicit **Enviar para aprovação** POST is submitter confirmation; **REVIEW → APPROVED/REJECTED** remains the administrator's separate decision. No additional chat Confirm for web-origin items.

Required restart/failure cases, tested in both engines:

| Durable state after interruption | Safe reconciliation |
| --- | --- |
| Key reserved, no confirmed item | Preserve the reserved identity. Verify metadata/media absence or quarantine uncertain owned stages before any same-key continuation; no blind overwrite/new UUID. |
| Reserved ID has matching item/actor/content but receipt completion was interrupted (JsonDB) | Validate the stored origin/request binding and canonical definition/media digests; complete/recover the receipt without inserting again. Inconsistent state fails closed for reconciliation. |
| Accepted receipt before notification | Return acceptance; missing notification outcome is unknown/skipped according to the frozen policy, not implicit permission to resend on POST replay or GET. |
| Telegram send happened but its outcome write was interrupted | Preserve acceptance with an ambiguous notification outcome. Never infer nondelivery or replay the whole render/send/submission automatically. |
| Accepted item later removed or moderation changed | Return the original acceptance binding/receipt (with a safe availability indication if needed), never restore/reinsert it from a request replay. |

Current legacy upload already inserts REVIEW **before** chat confirmation; do not describe its confirmation as DRAFT → REVIEW. Until the cutover, old callbacks retain original actor/chat + stored preview-message authorization. Resolve/reconcile pending legacy template confirmations during the stopped cutover rather than keeping the old template submission workflow indefinitely; source callback behavior is not implicitly retired. Finalized web items are immutable through creator APIs; later edits require a new proposal unless revisioning is separately designed. No render/send/notification operation belongs inside a metadata transaction.

### Compatibility rollout and rollback

- Implement/test backend phase 2 and image-only frontend phase 3 before their **one coordinated first API deployment**. At that deployment, retire manual template CSV/document intake and the active old-creator fallback; no dual live flow or feature-flagged coexistence period is required. Downtime is acceptable. Historical CSV/stored-data decoding and offline/import compatibility are different from keeping a public upload workflow alive.
- Prepare the stopped cutover: freeze writers/schedules, capture coherent metadata/media and compatible artifact/unit, test migration/restore on isolated copies, resolve/reconcile pending legacy template confirmations, deploy matching API/editor/schema, then reopen with the new path only. Do not delete pending items/media as cleanup. Reject old manual template uploads with guidance to the editor; preserve `source:` document upload and its callbacks unless separately scoped.
- Remove **Criador anterior** and old active-page/asset exposure as part of implementation; recommend redirecting old creator aliases/bookmarks to the new editor. Update route/browser/JAR guards that currently require `/create/legacy` and document the intended retirement. This plan does not change the currently released files/routes or execute a deployment.
- Test legacy/current/mixed JSON and SQLite round-trips, author ordering, orphan history, imports/row reports, offline exports, complete WAL-aware backups, fresh-target restore and receipt lookup after restart/restore. Unknown formats fail closed before writes/rendering; never drop text during downgrade.
- A schema/new-origin/mixed write changes rollback safety. The old image-only JAR is not automatically compatible. Use a tested compatible backend artifact, or an approved isolated export/restore procedure preserving definitions, origins, receipts and new writes. Artifact rollback alone cannot undo these changes. Keep offline bundle-version handling explicit; unversioned legacy readers must not silently consume new typed data.

## 4. Rendering proof-of-concept before backend implementation

**Do not replace ImageMagick speculatively.** It already supplies perspective distortion/masks and template alpha/two-plane compositing. Compare text strategies behind a small rasterizer boundary; decide with evidence. The old browser Konva/Moveable spike is not a native rendering PoC and remains unchanged.

### Candidates and safety gate

| Candidate | What the isolated PoC must prove |
| --- | --- |
| ImageMagick text + existing compositor | Version-specific literal settings/escaping or server-owned UTF-8 text files, safe handling of percent/property escapes, backslashes and leading `@`, fixed font paths, bounds, wrap/shrink/outline/box fill and deterministic placement. Documentation/options alone are not proof. No unproven raw-text path into `caption:`, `label:`, `-annotate`, MVG/Pango or other interpreted operands. |
| Java 21 headless Java2D/TextLayout → small transparent PNG → ImageMagick | Literal text, pinned font loading/glyph coverage, line/pixel/outline metrics, bounded grapheme-safe layout, box-sized raster allocation/cleanup and total JVM/native cost. Headless mode does not itself guarantee font/native-library availability or hard cancellation. |
| Replacement renderer, only if needed | Investigate only if neither bounded text strategy meets correctness/sandbox/resource gates. Must reproduce perspective, off-canvas masks, alpha, two-plane order and existing image-only fixtures before adoption; no heavyweight service/browser/AI default. |

ProcessBuilder arrays prevent shell interpolation but do not disable ImageMagick's percent/property expansion. Prove that benign metacharacter fixtures remain literal and cannot read a sentinel file outside owned scratch; do not inspect host secrets. Disable unneeded delegate/network/file-indirection routes without weakening current policy. A verified literal ImageMagick mode may be simpler than another rasterizer; Java2D is a candidate, not an already chosen replacement.

The upstream [`-annotate` documentation](https://imagemagick.org/command-line-options/#annotate) describes literal text read from a file. Include this as a concrete candidate: only the server constructs an owner-only bounded UTF-8 scratch file/path; no client-controlled indirection path. Verify actual installed-version behavior and compatibility with the existing security policy rather than broadly enabling `@*` reads. Test `%[fx:1+1]`, `%[filename]`, literal `@sentinel`, backslashes, real versus escaped newlines, markup/token-looking text, malformed encoding/surrogates, combining/ZWJ and unsupported bidi/glyph cases. Content must stay literal or fail explicitly; no expression expansion, secondary file read or network/delegate access. `-label` metadata alone is not drawn text and is not a substitute for this proof.

### Measured constraints, not guessed capacity

The [deployment guide](../deployment-setup.md) documents host-owned heap **64m/256m**, **MemoryMax448M / MemorySwapMax64M**, and private extracted `squashfs-root/AppRun` because FUSE AppImage mounting fails under `NoNewPrivileges`. These are the documented baseline, not a fresh inspection or permission to change the live service. Source `ImageMagickRenderer` applies per-command memory **128MiB**, map **256MiB**, disk **512MiB**, thread **2**, time **25 seconds**; `ProcessRunner` bounds each command at **30 seconds**. They are **not** an aggregate job/RSS/deadline/concurrency budget. Do not assume a CPU count or native process cost without measurement.

One 40MP RGBA raster is about 160MB before overhead. Avoid one full-template JVM/canvas buffer per text box or retaining every raster in heap. Rasterize bounded boxes sequentially, use owner-only scratch, free intermediates and preserve `RenderedImage` ownership through delivery. Define aggregate text pixels, strings/glyphs/lines/font-size iterations, encoded output, scratch/disk and whole-job deadline before integration. Start with **one global heavy render** shared with publication/previews and bounded/no waiting admission; measure fairness and reject overload instead of raising the host memory ceiling.

### Executable experiment plan and exit gate

1. **Isolated harness:** create a separate native-text experiment with generated fixtures and licensed candidate fonts, no Spring/bot startup, secrets, user images, production data or changes to the old browser spike. Record exact JDK/Magick/font/build identities and image-only baseline output/resource measurements. Keep native execution explicit/opt-in, not an undeclared requirement for ordinary unit tests.
2. **Text matrix:** short/long/multiline pt-BR accents, combining/surrogate/ZWJ boundaries, literal token/percent/backslash/`@` strings, malformed legacy descriptions/glyphs, repeated slots and approved text-only cases (dynamic without image areas; all-literal with `K = 0`), automatic shrink/positive-floor/impossible-fit failures and rejection of unsupported clipping, outline/padding/background, negative/off-canvas boxes and invalid limits. Use owned sentinel files only for indirection tests.
3. **Whole generation:** image-only versus image+text on both planes, reused image/description pairs, alpha holes, skew/off-canvas geometry and order **B2,B4,template,F1,F3**. Include representative and boundary byte/pixel/area cases; rejected/timeout cases are reported, not counted as success. Compare decoded image-only regression pixels, not merely PNG container bytes.
4. **Resource/failure matrix:** cold/warm latency distribution, peak combined JVM/child RSS or cgroup usage, CPU, scratch/output bytes, process count, interruption/timeout/cleanup, and competing publication/preview admission. Compare with image-only baseline under identical caps; no invented latency percentile target.
5. **Linux hardening proof:** run only in an approved isolated Linux environment reproducing heap/cgroup, private temp, filesystem and `NoNewPrivileges` with extracted AppRun. macOS output or resource-only SSH tests do not establish VPS parity. Live bots2 benchmarking needs separate authorization; do not stop/change production to obtain routine evidence.
6. **Decision gate:** select the smallest passing strategy and freeze font/layout/text/job budgets against available service headroom. If local evidence is good but matching Linux evidence is unavailable, continue pure contracts/tests and mark runtime enablement blocked—not the entire plan. If no strategy fits, document the specific failed gate before researching replacement.

Start the PoC with **pt-BR** and the selected plain [Liberation Sans / Serif / Mono families](https://github.com/liberationfonts/liberation-fonts), regular/bold only. These provide Arial/Times New Roman/Courier New-style metrics without assuming proprietary OS fonts. Upstream documents OFL-1.1 and a 2.1.5 binary release; verify actual artifact hashes/notices and accented glyph coverage in the experiment before packaging. Test automatic shrink with short/long descriptions and finite fitting iterations. Keep malformed/legacy-null fixtures as defensive tests, not product options to reconsider. Font choice is delegated and settled at family level; the native strategy and safe budgets still require measured evidence.

The first local experiment is now runnable in `spikes/native-text/`; [commands, verified conclusions and limits](native-text-poc.md) are documented separately. Local file-fed literal text, pt-BR/shrink and image-only/mixed/text-only fixtures executed with pinned temporary Liberation fonts, without Spring or production changes. Java2D text plus Magick composition was cheaper on the tested long-text/128-box cases; native caption remains an alternative, not a finalized selection. The 40MP solid-color native baseline exhausted the existing pixel cache before text work. This is a capacity rejection, not a passed boundary or reason to raise host limits. Matching Linux sandbox/cgroup, whole-service headroom and remaining full text-contract tests are still required. Evidence stays in private/CI artifacts, not checked-in per-run reports.

## 5. Optional native preview and deferred drafts

**Preview is optional and does not gate submission.** The client generates synthetic placeholder images/descriptions (numbered/color-coded slots); the server uses them only for the requested native example. No approved real-source gallery or sample UUID lookup is offered. This describes the recommended native implementation of the user's optional button; a browser-only overlay remains a labeled approximation, not proof of Magick/text parity.

| Operation | Proposed contract |
| --- | --- |
| `POST /api/creator/template-previews` | Same `image` + versioned `definition` as submission; JSON `samples` manifest of `{ sourceSlot, imagePart, description }` and one bounded local PNG/JPEG part per referenced slot. Multipart names/paths are mapped by the server; descriptions remain untrusted literal text. Return `200 image/png` bytes or ordinary safe ProblemDetail. |
| Client display | Fetch with creator Authorization and `credentials: omit`, turn bytes into a local object URL and revoke on replacement/closure. Capture local document revision/content at click; discard late responses after editing/replacement. No persistent/public preview URL or credentials in an image `src`. |
| `POST /api/creator/template-submissions` | Submit current original image + definition with its own idempotency key. **No previewId, samples, prior call or draft required**. Samples are rejected/ignored according to the frozen strict DTO, never stored as real sources or used for publication selection. |

Every referenced slot appears exactly once in the manifest; repeated image/text areas use the same placeholder/description. Source-linked text-only templates supply samples for their slots even though they have no image areas; all-literal `K = 0` templates send an empty samples manifest and no sample-image parts. Propose small placeholder caps (≤256×256 each and ≤1 MiB total encoded samples) in addition to existing template and aggregate request/part/metadata bounds; confirm complete native work budgets in the PoC. Client generation is UX intent, not trusted provenance: validate/decode every sample, forbid remote URLs/paths/source IDs and apply admission even for authenticated friends. Output bytes/pixels/time remain bounded.

The native request uses owner-only temporary files and the same mixed renderer/font catalog. It never inserts REVIEW/draft/receipt/source/history records, changes weights or sends Telegram messages. Stream the bounded artifact and close `RenderedImage` after completion/error/disconnect; do not free final bytes prematurely or assume a browser abort stops native work. No persistent draft lifecycle, cache database or new permanent DRAFT status is needed. Later local restore/server drafts are separate enhancements. Administrator/publication renders continue selecting approved real sources, not these synthetic examples.

## 6. Decisions and what they actually block

User answers settle the product direction; technical recommendations remain unimplemented and require their owning proof/tests. Do not repeat answered questions or block independent work.

| ID | Question / recommendation | Needed before | Unblocked work |
| --- | --- | --- | --- |
| D1 — resolved | Preview button is **optional**; submission is independent. Recommend the stateless native-preview contract above, without permanent drafts. | Implement/test preview backend in phase 2, button in phase 3 | Auth and text PoC do not wait for draft infrastructure. |
| D2 — resolved | Retire manual **template** CSV/document submission at the first new API/editor deployment, with downtime and no dual live flow; source uploads remain. | Coordinated phases-2/3 cutover implementation/backup/reconciliation | Backend/frontend tests and stored legacy migration. |
| D3 — product and launch configuration resolved | Direct submit is Telegram-only via the confirmed **Criar → `/create` menu WebApp**. Separate root admin Main Mini App remains unchanged. Current official hosted SDK and server-verifiable raw initData HMAC are the selected design; actual verifier/end-to-end tests are implementation work, not another launch question. | Runtime auth/notification enablement tests | Auth PoC, DTO/recovery and negative direct-call tests. |
| D4 — product resolved | pt-BR; Liberation Sans/Serif/Mono regular/bold; descriptions mandatory; automatic shrink, no clipping; **text-only templates valid**, including dynamic slots without image areas and all-literal `K = 0`. Artifact/glyph/work-limit proof remains. | Mixed layout/catalog implementation and native proof | Image-only, mixed, dynamic text-only and zero-source literal fixtures. |
| D5 | Which strategy passes the PoC, and what job/text/upload/admission budgets fit existing caps? Matching isolated Linux runner/permission must be arranged; no memory increase assumed. | Renderer selection and runtime enablement | Pure layout/contracts, golden fixtures and migration preparation. |
| D6 — resolved | Client-generated synthetic placeholders/descriptions only for optional creator preview; no real-source lookup/persistence. Real approved sources remain for admin/publication. | Bounded multipart/slot validation and native-preview tests | Auth, final-submit and mixed publication work. |
| D7 | Freeze versioned model/SQLite/JsonDB receipt-origin fields, canonical fingerprint/version, bundle versions and post-write rollback target. Recommend explicit version + typed ordered areas and durable actor/key receipts, not nullable-Message heuristics or separate text collection. | Migration implementation / schema rollout | PoC and mapping/recovery test design on generated fixtures. |
| D8 | Freeze auth freshness/session lifetime, upload quotas and receipt/tombstone storage caps, replay horizon and any verifiable expiry/archival protocol; UUID tombstones cannot simply expire safely. If drafts are chosen add TTL/cache/lease values. Decide whether failed admin notifications need a durable outbox now; recommend visible committed receipt/admin queue first, no implicit resend. | API enablement and capacity tests | Contracts and deterministic clock/quota/failure tests. |

### References for the feasibility/authentication work

- [Telegram launch identity and validation](https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app).
- [ImageMagick property/percent escapes](https://imagemagick.org/escape/) and [version-dependent defines](https://imagemagick.org/defines/).
- [Java 21 TextLayout](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/font/TextLayout.html); inspect line breaking, font loading and native headless dependencies rather than assuming one `drawString` call implements the contract.
