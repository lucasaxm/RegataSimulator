# Creator backend architecture and feasibility gates

**Status: backend foundation implemented; remaining submission/storage/rendering contracts are planned.** `POST /api/creator/session` now verifies Telegram initData and issues a bounded creator-only credential. Immutable version-1 image/text definitions, a strict codec and validation are implemented in `application/creator`; they are not yet wired into stored templates, uploads or generation. Capabilities, submit, receipts, schema/recovery and production text rendering remain unimplemented. The released image-only editor still uses manual document/caption submission. The order remains **complete backend → image-only frontend direct submission → frontend text areas**. See the [delivery phases](proposal.md#incremental-delivery-and-acceptance), [current flow](current-flow.md), [text semantics](text-areas.md) and [native experiment guide](native-text-poc.md).

**Delivery policy:** Magick feasibility is accepted for development. The comparison/gallery phase is closed; ordinary typography issues become focused implementation regressions, not an exhaustive Unicode/cross-engine parity research gate. Retain work/time limits and known capacity rejections. A focused isolated Linux render/sandbox check is still needed before production enablement, not before continued backend implementation.

**Product choices now resolved:** **ImageMagick is selected for backend text and image rendering; initial optional preview is client-only Konva**, using local synthetic source images/descriptions. Accept an approximate browser/native match initially; investigate server-rendered preview, text-layer bitmaps or another strategy only if an evident mismatch becomes a practical problem. No headless browser, Node canvas backend or Java2D production text path is selected. Telegram-only direct submission uses the confirmed **Criar** menu WebApp; **text-only templates are valid**. Retire manual **template** CSV/document submission at the first API-integrated deployment, with downtime acceptable and no dual live flow. Text is pt-BR, with plain Liberation fonts, mandatory source descriptions and automatic shrink-to-fit. Renderer selection does not complete its fitting/safety/resource gates or implement authentication/backend text. Persistent drafts and a server-preview endpoint are not initial requirements.

## 1. Architecture recommendation

Keep the Java/Spring modular monolith, thin transport adapters, synchronous typed use cases, existing legacy repository adapters and moderation. New creator persistence/workflow targets SQLite only. No new Node service, browser renderer, broker or workflow framework is required. Separate these responsibilities:

| Boundary | Responsibility / change from today |
| --- | --- |
| Creator authentication | Verify Telegram launch data and issue a creator-only credential; never reuse ADMIN login or trust a posted actor ID. |
| Media acquisition | Telegram file-ID download and bounded HTTP multipart streaming converge on the same owned-media validation boundary. `SubmissionService.Upload` currently requires a Telegram file ID; do not fake one for HTTP. |
| Template definition | One versioned, typed image/text model, original-pixel geometry, ordered planes and source-slot bindings. CSV becomes an image-only compatibility adapter, not the core domain format. |
| Generation | Select each source slot once, retain its image/description pair, resolve literal text, rasterize and composite through a transport-independent render use case. |
| Final submission | Validate, durably bind request identity/content, persist a confirmed REVIEW item and receipt, then attempt administrator notification. No implicit Telegram Confirm callback for web items. |
| Moderation / publication | Existing administrator decision and publication services consume the new definition/origin projections; approval remains mandatory. Preview never changes publication weights/history. |

**First API design: multipart submit, authentication, capabilities and receipt lookup; preview stays local.** A submit never requires a preview ID, samples or prior preview call. `POST /api/creator/template-previews` is deferred, not a phase-2/3 deliverable. Durable duplicate protection remains useful even for a few friends; do not introduce OAuth infrastructure, JWT libraries, durable render queues or persistent drafts just for this. Backend mixed rendering is still required before either frontend milestone. Implement phases 2 and 3 separately, but their first public rollout is one coordinated backend/frontend cutover, not a production coexistence period.

### Authentication and notification destination

- `POST /api/creator/session` receives raw `initData` in a bounded JSON body, never a URL. Verify the configured bot's documented HMAC, constant-time comparison, required user, bounded parsing/duplicate fields, freshness and future skew. Use synthetic signatures in unit/filter-chain tests.
- Prefer a narrowly matched creator bearer chain with no cookie authentication and `credentials: omit`; retain the credential only in page memory. Scope any CSRF exception to this chain. Existing ADMIN session/XOR-CSRF, CORS, cookies and frame ancestors remain unchanged.
- Validate ownership on every creator read/write. Public bootstrap is not a public upload endpoint. Standalone/missing-initData access remains editing/export only unless a separate login design is approved.
- A verified user ID identifies the actor, **not an arbitrary notification chat**. Derive an allowed private notification destination from verified launch context/existing bot relationship and write permission, and bind that nullable destination **per submission at acceptance**. Do not accept `chatId`, group targets, names or `initDataUnsafe` as authorization; later actor/profile changes cannot retarget an old item. Delivery can still fail after permission changes; missing/blocked DM permission must not erase a committed submission. An optional submitter-DM outcome is distinct from the administrator outcome and is never a new confirmation requirement.
- The user's chat and BotFather screenshots confirm the **Criar menu WebApp** targets `https://regsim.boatarde.dev/create`. This settles launch configuration; a separate Main Mini App targets the root admin page and need not change. The implemented verifier has synthetic positive/negative and HTTP filter-chain tests; real Telegram launch/initData end-to-end verification has not been performed or authorized by this implementation.

### Official SDK identity proof and proportionate security

The [official Mini App documentation](https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app) distinguishes raw signed `Telegram.WebApp.initData` from parsed **untrusted** `initDataUnsafe`. The [current hosted SDK](https://telegram.org/js/telegram-web-app.js) exposes those launch fields; it does not validate them in the browser. Java's TelegramBots version is a different dependency and does not determine the browser SDK version.

Keep exactly `https://telegram.org/js/telegram-web-app.js`: this is Telegram's maintained current distribution, already loaded by the editor. Do not invent a numeric/versioned script URL, vendor a stale copy or install a community wrapper to satisfy “latest SDK”. `WebApp.version` describes the client-supported Bot API, not a package version; keep feature detection/old-client fallbacks. The newer `WebApp.Serverless.call` belongs to [Telegram-hosted Serverless](https://core.telegram.org/bots/serverless.md); it does not automatically authenticate this self-hosted Spring/Magick backend or replace multipart upload.

The local code does not configure the launch button because its configuration is external. **The user has now supplied that evidence:** BotFather shows menu title **Criar**, creator URL `https://regsim.boatarde.dev/create`, and a separate fullscreen Main Mini App at `https://regsim.boatarde.dev/` used for administration. Telegram's [menu-launch documentation](https://core.telegram.org/bots/webapps#launching-mini-apps-from-the-menu-button) says menu launches behave like inline WebApp buttons, so this is the appropriate signed-identity launch—not an ordinary URL link or reply-keyboard launch. Keep the creator/menu and main admin entry points distinct; no BotFather or admin-page change is needed to implement creator authentication. A plain standalone browser visit still has no Telegram identity. Validate signed initData end-to-end during implementation in approved development; do not log raw credentials, request them in chat or change production configuration merely to repeat the answered launch question.

Implemented server bootstrap, with frontend/consumer wiring still pending:

1. Read the raw initData after SDK readiness and POST it to `/api/creator/session` over same-origin HTTPS. Do not rebuild it from `initDataUnsafe.user`; do not send raw credentials in URLs, analytics or local drafts.
2. Bound query bytes/field count, reject malformed percent encoding/duplicates, extract a valid 32-byte `hash`, and form the documented sorted `key=value` LF-separated data-check string from decoded values **excluding the hash field**. Preserve the original `user` JSON string rather than deserialize/reserialize before hashing. Follow the bot-token HMAC algorithm's field rules (including new signed fields), not the separate third-party Ed25519 recipe which excludes both hash and signature.
3. Derive `secretKey = HMAC-SHA256(key = UTF-8("WebAppData"), message = UTF-8(botToken))`, then the HMAC of the data-check string using that secret. Compare hash bytes in constant time. The bot token stays in existing private server configuration; verification needs no Telegram network call or browser secret.
4. Require a signed valid user ID and `auth_date`; reject wrong-bot, altered, stale or excessively future data before trusting identity. Implemented bounds are bootstrap age ≤5 minutes and future skew ≤60 seconds, tested with an injected clock—not SDK guarantees. Raw query admission is 16KiB and 32 fields; the closed JSON request body is bounded to 32KiB before parsing.
5. Issue a 32-byte SecureRandom Base64url credential after verification. Only SHA-256 token keys, actor and absolute expiry are retained in a 1,024-entry process-local registry; lifetime is 30 minutes without refresh/extension. Restart invalidates sessions, and full capacity returns generic 503 without evicting active credentials. Bootstrap reserves at most 20 attempts per remote address per 60 seconds in a separate bounded 2,048-entry limiter; forwarded headers do not establish identity or bypass throttling. Requests/responses and exceptions are redacted/no-store. Frontend reopen/reauthentication wiring remains future work.
6. On every creator submit/receipt request, require the credential, derive actor/ownership from it, validate all inputs and apply simple per-user/upload/global-render caps. Capabilities use the same creator credential. Client-only preview needs no upload or creator credential; any future server preview would require its own bounded authenticated contract. Leave ADMIN/session CSRF unchanged. Optional numeric friend allowlisting is authorization beyond Telegram identity, not a substitute for HMAC.

**What this prevents:** a caller cannot change the signed user ID or invent another user's identity and make a valid HMAC without the bot secret. **What it does not promise:** the API can tell that a valid Telegram credential is presented, not that the caller clicked our UI. A legitimate user can call the API directly as themselves; stolen initData/session credentials can be replayed within their allowed lifetime. HTTPS, short lifetimes, no credential logging/storage, ownership and duplicate/admission checks are proportionate safeguards. CORS, Referer, User-Agent, SDK/platform/version presence and hiding the endpoint are not identity proof.

Authentication acceptance tests: synthetic valid/altered/wrong-bot HMACs; signed user versus spoofed body user; hash/field/encoding/age/future/missing-user failures; unknown/new signed fields under the documented algorithm; token expiry/restart/registry bounds; cross-actor receipt access and unchanged ADMIN CSRF. Also demonstrate direct HTTP calls with no/forged credential fail, while an authenticated user remains correctly attributed. Dedicated-bot launch verification supplements these tests; do not paste real initData or tokens into reports.

Implemented tests cover the verifier/session cases, bounded/closed bootstrap input, throttle, bearer header parsing, real ADMIN-session versus creator isolation, unchanged ADMIN CSRF and CORS/frame policy. A protected **test-only** creator controller exercises the chain; it is not a production endpoint. Receipt ownership tests belong to the unimplemented receipt slice. Focused and full `./gradlew test` passed using local Corretto 21.0.7; this is below the maintenance release floor and is not CI/release parity or real Telegram acceptance.

## 2. Proposed minimal HTTP contract

Only the session endpoint below is implemented in this foundation. Capabilities, submission and receipt endpoints remain proposed and unavailable. Responses are bounded DTOs and `Cache-Control: no-store`; do not return persisted Messages, internal paths, preview bindings or unrelated source metadata.

| Endpoint | Request | Successful response |
| --- | --- | --- |
| **Implemented:** `POST /api/creator/session` | Closed `application/json`: `{ "initData": "<raw signed query string>" }`, bounded as above | `200`: `{ "accessToken": "<memory-only credential>", "tokenType": "Bearer", "expiresAt": "<UTC instant>" }`. No cookie/session creation; token values never enter logs/docs/URLs. Generic 400/401/413/415/429/503 rejection paths are tested. |
| `GET /api/creator/capabilities` | Creator bearer | `200`: supported `definitionVersions`, `areaKinds`, byte/pixel/area/text limits, pinned `fontCatalogVersion` and allowed font IDs/weights. Publish only implemented capabilities; no host paths or secrets. |
| `POST /api/creator/template-submissions` | Creator bearer; UUID `Idempotency-Key`; multipart `image` binary and `definition` JSON | First commit `201` with `Location` pointing to the owned receipt; identical committed replay `200` with the same submission identity, never a second insert/notification. |
| `GET /api/creator/template-submissions/{requestId}` | Creator bearer; `requestId` is the UUID idempotency key, scoped to actor | `200` committed receipt for lost-response reconciliation. Unknown/nonowned request `404`; in-progress or uncertain operation is an explicit conflict, not permission to submit under a new key. |

The frontend creates one request key per immutable image/definition snapshot and retains it until reconciliation. It streams the original validated file, not base64, a screenshot, a remote URL or a Telegram file ID. HTTP upload replaces manually sending the image through Telegram; the bytes still have to reach the server once.

### Definition payload

The implemented **version-1 contract** (`TemplateDefinition`, `TemplateDefinitionCodec`, `TemplateDefinitionValidator`) supports ordered image/text records with closed discriminators. It is not yet a persistence format or upload/render integration: legacy `Template.areas`, CSV, repositories and schema remain unchanged, with no text-to-image-only projection. The phase-3 frontend will send only `kind: image` in this envelope. Array position defines order; UUID area IDs identify edits independently of order. Coordinates/sizes are original-image pixels. Validation accepts optional decoded dimensions for future media intake and rejects a mismatch.

The codec uses an isolated Jackson 2 mapper, with 256KiB UTF-8 input/output, depth 16, numeric length 64, strict duplicates/unknown fields/trailing-token/type rejection, and no unrestricted polymorphic binding. Image geometry reuses existing validation through a single copied legacy quad, not the legacy global image-slot rules. Initial text admission is 4,096 UTF-16 pattern units, ≤2MP/2,048 per side, font size 1–128, outline 0–4, padding 0–64 and finite line-height 1–2. These are provisional validation bounds, not native resource/glyph proof or published capabilities. `boxFill` absent means null; literal text must use `sourceSlot: null`, dynamic exact `{{source.description}}` requires a slot. Internal whitespace aliases in that token are not accepted. Original text/newlines/NFD and area order are preserved. The returned slot count K is validation only, not implemented source selection/publication. Codec output field order is explicit, but is not yet the durable idempotency fingerprint contract.

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

Additional text-area example; catalog/font IDs are implemented provisional allowlists, not packaged font assets or published capabilities:

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
| `409` | Same key/different canonical content or request in progress/uncertain. Safe machine codes distinguish these cases. |
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

**Storage scope approved by the user:** production migration to SQLite is already complete; it is not pending creator work. JsonDB must receive no new features: it is maintenance-only for legacy compatibility and security fixes, preserving stored legacy data and existing import/export/recovery behavior. New creator persistent definitions, origin, receipts, idempotency, schema migrations and workflow target **SQLite only**. Retiring the JsonDB runtime/default is separate later scope; do not change dependencies/configuration or remove legacy adapters/tests here. Future creator persistence on JsonDB must reject unsupported operations clearly and fail closed; this is a planned requirement, not implemented runtime or CLI behavior.

JSON is also an offline bundle format, not a synonym for the JsonDB engine. Typed SQLite exports/recovery may require versioned JSON bundles preserving definitions/origins/receipts; new mixed data and receipts must never fall back to live JsonDB. Exact schema fields, constraints and change-set identifiers below remain proposals to freeze in the next storage slice, not implemented storage.

| Model / adapter | Required work |
| --- | --- |
| Template definition | Add a version and a closed typed ordered area list in SQLite. Proposed approach: explicit `definition_version` with the existing bounded `areas_json` array holding version-specific area DTOs; legacy version 0 reads old quads, version 1 reads typed image/text areas. Freeze exact fields/checks/change-set numbers in the next storage slice; this is not executable DDL or a new JsonDB document feature. Avoid two independently writable image-only/mixed truths. |
| Submission attribution | Persist explicit origin kind (`TELEGRAM_DOCUMENT`, `TELEGRAM_MINI_APP`, legacy unknown/imported), verified author ID/name projection, server submission time and nullable authorized notification destination. Retain original nullable Messages for legacy items; web items have no fabricated Message or preview binding. Existing derived author/date SQL columns must use explicit web attribution, not null out these fields on later saves. |
| Receipt/idempotency | Durable actor + UUID key + canonical image/definition fingerprint + reserved template ID + operation/commit outcome and submission time; unique binding and bounded storage/retention. Keep an immutable dedup binding/tombstone for committed requests even after current-item deletion so a replay cannot recreate an item. Include receipts/attribution in recovery data. |
| Author/gallery/import/audit | Reuse verified Telegram `Author` identity; extend safe projections/accessors instead of making UI/storage consumers navigate a fake Message. Preserve minimal legacy `message.from` display compatibility, author filtering/order, nullable imported origins and immutable moderation audit semantics. Mixed definitions must be readable in admin review and never offered as image-only CSV. |
| Rendering request | Include the validated definition and immutable selected-slot image/description snapshots. Text uses the same selected source as its paired image; literal-only areas need none. |
| Optional preview | Client-only Konva view/sample state, not persisted or uploaded as part of the definition. No backend preview route, render, receipt or draft is required. |

Existing JsonDB collections/schema versions and legacy JSON round-trips remain compatibility obligations, alongside SQLite constraints/indexes, description NFKC/ROOT uniqueness, nullable origins, history UUID/order and compensated deletion. Plan new schema markers, validators and receipt storage for **SQLite only**, with versioned offline JSON bundle compatibility and recovery, not new live JsonDB features.

Retain compact committed-key bindings/tombstones independently of verbose notification/receipt retention, including through item deletion/restore. The proposed client UUID has no trustworthy issuance time: simply expiring its tombstone would let an old request create another item. Initially hard-cap/admit durable bindings without silently evicting committed or uncertain keys; D8 must settle capacity and any verifiable expiry/archival protocol before enabling cleanup. Backup retention count is not a receipt TTL. Failed/uncertain deletion never removes the key binding or authorizes re-creation; `.delete-*` bytes retain existing manual inspection/reconciliation rules from the [recovery runbook](../operations-runbook.md), not automatic age-based purge.

### Direct-submit workflow (recommended first delivery)

1. Verify actor, stream to owned temporary media, decode/validate bytes and full definition; derive a canonical content fingerprint. No Telegram message or DB transaction during upload/decode.
2. Serialize/reserve the actor/key operation with a stable template identity. Concurrent/lost/uncertain operations must converge on that identity, not produce a new UUID on every attempt.
3. Persist owned final media and confirmed REVIEW metadata/author/receipt in SQLite with explicit compensation. Commit metadata + dedup receipt atomically; filesystem effects remain outside. Test interruption/reconciliation at that boundary rather than adding a JsonDB submission path. A process-local lock is insufficient for crash/restart correctness.
4. Only after acceptance, render/send the administrator review notification under bounded admission/deadlines. Failure/busy/ambiguous Telegram delivery updates a safe outcome, not a rollback or invitation to reupload. Notification retries are not implicit; the item remains in the admin queue. Optional submitter DM uses the authorized destination, not the client payload.
5. Return/reconcile the receipt. The user's explicit **Enviar para aprovação** POST is submitter confirmation; **REVIEW → APPROVED/REJECTED** remains the administrator's separate decision. No additional chat Confirm for web-origin items.

Required restart/failure cases for planned SQLite persistence and versioned offline JSON bundle recovery:

| Durable state after interruption | Safe reconciliation |
| --- | --- |
| Key reserved, no confirmed item | Preserve the reserved identity. Verify metadata/media absence or quarantine uncertain owned stages before any same-key continuation; no blind overwrite/new UUID. |
| Media was finalized but the SQLite metadata/receipt transaction did not complete | Preserve the reserved identity; verify transaction outcome, owned media and digests before continuation. Inconsistent or uncertain state fails closed for reconciliation, never a second insert. |
| Accepted receipt before notification | Return acceptance; missing notification outcome is unknown/skipped according to the frozen policy, not implicit permission to resend on POST replay or GET. |
| Telegram send happened but its outcome write was interrupted | Preserve acceptance with an ambiguous notification outcome. Never infer nondelivery or replay the whole render/send/submission automatically. |
| Accepted item later removed or moderation changed | Return the original acceptance binding/receipt (with a safe availability indication if needed), never restore/reinsert it from a request replay. |

Current legacy upload already inserts REVIEW **before** chat confirmation; do not describe its confirmation as DRAFT → REVIEW. Until the cutover, old callbacks retain original actor/chat + stored preview-message authorization. Resolve/reconcile pending legacy template confirmations during the stopped cutover rather than keeping the old template submission workflow indefinitely; source callback behavior is not implicitly retired. Finalized web items are immutable through creator APIs; later edits require a new proposal unless revisioning is separately designed. No render/send/notification operation belongs inside a metadata transaction.

### Compatibility rollout and rollback

- Implement/test backend phase 2 and image-only frontend phase 3 before their **one coordinated first API deployment**. At that deployment, retire manual template CSV/document intake and the active old-creator fallback; no dual live flow or feature-flagged coexistence period is required. Downtime is acceptable. Historical CSV/stored-data decoding and offline/import compatibility are different from keeping a public upload workflow alive.
- Prepare the stopped cutover: freeze writers/schedules, capture coherent metadata/media and compatible artifact/unit, test migration/restore on isolated copies, resolve/reconcile pending legacy template confirmations, deploy matching API/editor/schema, then reopen with the new path only. Do not delete pending items/media as cleanup. Reject old manual template uploads with guidance to the editor; preserve `source:` document upload and its callbacks unless separately scoped.
- Remove **Criador anterior** and old active-page/asset exposure as part of implementation; recommend redirecting old creator aliases/bookmarks to the new editor. Update route/browser/JAR guards that currently require `/create/legacy` and document the intended retirement. This plan does not change the currently released files/routes or execute a deployment.
- Preserve existing legacy JsonDB/JSON round-trips and import/export/recovery tests. Test new image/text definitions, origins and receipts through SQLite schema migration and versioned offline JSON bundles, author ordering, orphan history, imports/row reports, exports, complete WAL-aware backups, fresh-target SQLite restore and receipt lookup after restart/restore. JSON bundle compatibility does not require live JsonDB support for new data. Unknown formats fail closed before writes/rendering; never drop text during downgrade.
- A schema/new-origin/mixed write changes rollback safety. The old image-only JAR is not automatically compatible. Use a tested compatible backend artifact, or an approved isolated export/restore procedure preserving definitions, origins, receipts and new writes. Artifact rollback alone cannot undo these changes. Keep offline bundle-version handling explicit; unversioned legacy readers must not silently consume new typed data.

## 4. Rendering proof-of-concept before backend implementation

**Historical feasibility material below is retained as reference, not a blocker for backend implementation.** Magick is accepted for development; no more comparison/gallery rounds are planned absent a concrete defect. Cover ordinary glyph/style fixes with representative implementation tests, not pixel identity with Java2D or an exhaustive script matrix. Keep a focused isolated Linux render check and existing resource/hardening limits before production enablement.

**ImageMagick is selected for text rasterization and existing perspective/mask/two-plane composition.** Finish its bounded literal-text/layout implementation behind a small rasterizer boundary; do not restart engine selection merely because the retained comparison includes Java2D. The old browser Konva/Moveable spike stays unchanged. Selection accepts the preferred visual direction, not unsafe fitting, dropped glyphs or failed resource gates.

### Selected strategy, retained comparisons and safety gate

| Strategy / status | What the isolated PoC must prove |
| --- | --- |
| **Selected: ImageMagick text + existing compositor** | Version-specific literal settings/escaping or server-owned UTF-8 text files, safe handling of percent/property escapes, backslashes and leading `@`, fixed font paths, bounds, wrap/shrink/outline/box fill and deterministic placement. Documentation/options alone are not proof. No unproven raw-text path into `caption:`, `label:`, `-annotate`, MVG/Pango or other interpreted operands. |
| Retained comparison: Java 21 headless Java2D/TextLayout → small PNG → ImageMagick | Existing fixture/resource comparison remains reproducible, not a production implementation milestone. No continuing engine competition or automatic fallback is required. |
| Deferred alternatives | Revisit if Magick cannot meet a required safety/resource gate, or if a practically significant Konva/native mismatch calls for a different preview approach. Preserve perspective, off-canvas masks, alpha, two-plane order and image-only fixtures; no new browser/Node service by default. |

ProcessBuilder arrays prevent shell interpolation but do not disable ImageMagick's percent/property expansion. Prove that benign metacharacter fixtures remain literal and cannot read a sentinel file outside owned scratch; do not inspect host secrets. Disable unneeded delegate/network/file-indirection routes without weakening current policy. Use the selected literal ImageMagick path; preserve prior Java2D measurements without treating them as a replacement decision.

The upstream [`-annotate` documentation](https://imagemagick.org/command-line-options/#annotate) describes literal text read from a file. Include this as a concrete candidate: only the server constructs an owner-only bounded UTF-8 scratch file/path; no client-controlled indirection path. Verify actual installed-version behavior and compatibility with the existing security policy rather than broadly enabling `@*` reads. Test `%[fx:1+1]`, `%[filename]`, literal `@sentinel`, backslashes, real versus escaped newlines, markup/token-looking text, malformed encoding/surrogates, combining/ZWJ and unsupported bidi/glyph cases. Content must stay literal or fail explicitly; no expression expansion, secondary file read or network/delegate access. `-label` metadata alone is not drawn text and is not a substitute for this proof.

### Measured constraints, not guessed capacity

The [deployment guide](../deployment-setup.md) documents host-owned heap **64m/256m**, **MemoryMax448M / MemorySwapMax64M**, and private extracted `squashfs-root/AppRun` because FUSE AppImage mounting fails under `NoNewPrivileges`. These are the documented baseline, not a fresh inspection or permission to change the live service. Source `ImageMagickRenderer` applies per-command memory **128MiB**, map **256MiB**, disk **512MiB**, thread **2**, time **25 seconds**; `ProcessRunner` bounds each command at **30 seconds**. They are **not** an aggregate job/RSS/deadline/concurrency budget. Do not assume a CPU count or native process cost without measurement.

One 40MP RGBA raster is about 160MB before overhead. Avoid one full-template JVM/canvas buffer per text box or retaining every raster in heap. Rasterize bounded boxes sequentially, use owner-only scratch, free intermediates and preserve `RenderedImage` ownership through delivery. Define aggregate text pixels, strings/glyphs/lines/font-size iterations, encoded output, scratch/disk and whole-job deadline before integration. Start with **one global heavy render** shared with publication and existing review renders, with bounded/no waiting admission; measure fairness and reject overload instead of raising the host memory ceiling. Client-only preview adds no native render workload.

### Executable experiment plan and exit gate

1. **Isolated harness:** create a separate native-text experiment with generated fixtures and licensed candidate fonts, no Spring/bot startup, secrets, user images, production data or changes to the old browser spike. Record exact JDK/Magick/font/build identities and image-only baseline output/resource measurements. Keep native execution explicit/opt-in, not an undeclared requirement for ordinary unit tests.
2. **Text matrix:** short/long/multiline pt-BR accents, combining/surrogate/ZWJ boundaries, literal token/percent/backslash/`@` strings, malformed legacy descriptions/glyphs, repeated slots and approved text-only cases (dynamic without image areas; all-literal with `K = 0`), automatic shrink/positive-floor/impossible-fit failures and rejection of unsupported clipping, outline/padding/background, negative/off-canvas boxes and invalid limits. Use owned sentinel files only for indirection tests.
3. **Whole generation:** image-only versus image+text on both planes, reused image/description pairs, alpha holes, skew/off-canvas geometry and order **B2,B4,template,F1,F3**. Include representative and boundary byte/pixel/area cases; rejected/timeout cases are reported, not counted as success. Compare decoded image-only regression pixels, not merely PNG container bytes.
4. **Resource/failure matrix:** cold/warm latency distribution, peak combined JVM/child RSS or cgroup usage, CPU, scratch/output bytes, process count, interruption/timeout/cleanup, and competing publication/review admission. Compare with image-only baseline under identical caps; no invented latency percentile target or server-preview workload requirement.
5. **Linux hardening proof:** run only in an approved isolated Linux environment reproducing heap/cgroup, private temp, filesystem and `NoNewPrivileges` with extracted AppRun. macOS output or resource-only SSH tests do not establish VPS parity. Live bots2 benchmarking needs separate authorization; do not stop/change production to obtain routine evidence.
6. **Production-enablement gate:** freeze the selected Magick font/layout/text/job budgets against available service headroom and resolve failing required cases. If matching Linux evidence is unavailable, continue backend storage/rendering/workflow/API implementation and tests; only production enablement remains blocked. If Magick cannot meet a required gate, document it before researching replacement; engine choice is not a reason to waive the gate.

Start the PoC with **pt-BR** and the selected plain [Liberation Sans / Serif / Mono families](https://github.com/liberationfonts/liberation-fonts), regular/bold only. These provide Arial/Times New Roman/Courier New-style metrics without assuming proprietary OS fonts. Upstream documents OFL-1.1 and a 2.1.5 binary release; verify actual artifact hashes/notices and accented glyph coverage in the experiment before packaging. Test automatic shrink with short/long descriptions and finite fitting iterations. Keep malformed/legacy-null fixtures as defensive tests, not product options to reconsider. Font choice is delegated and settled at family level; the native strategy and safe budgets still require measured evidence.

The local experiment in `spikes/native-text/` has executed literal text, pt-BR/shrink, image-only/mixed/text-only and style/visual fixtures with pinned temporary Liberation fonts, without Spring or production changes. [Commands, verified conclusions and limits](native-text-poc.md) distinguish the user-selected Magick direction from incomplete safety/readiness proof. Java2D was cheaper on historical long-text/128-box cases, but is not the chosen production text path.

**Implemented PoC fitting, not a production contract:** shared `spikes/native-text/MagickCaptionFitter.java` and its pure tests serve `NativeTextPoc` and visual callers; the runner includes the helper in compilation and source hashes. It measures native free-height `caption:split=true` at explicit integer sizes descending within **1..128**, testing every larger size before accepting a candidate under budget. It uses neither Magick's auto-fit suggestion nor a numeric binary-search monotonicity assumption. Batches contain at most **8** probes in one process, with a dummy **1×1** image and deletion of each probe raster rather than retaining them together. The accepted size is rerendered and its dimensions strictly verified. Native admission no longer depends on Java2D fitting: Java retains font glyph checks and the model/oracle comparison only. Zero outline explicitly uses `-stroke none` for all harness captions, not the production renderer.

Current PoC bounds are **128 probes**, **17 fit commands** and scalar work **16,000,000**, charging **UTF-16 text length × size²** for probes and the accepted rerender. Input text is bounded to **4,096 UTF-16 units**, boxes to **2MP / 2,048 per side**; the positive floor is **1**, with no text normalization. Existing native caps, **90-second job** and **295-second launcher** deadlines remain unchanged. The scalar budget is a heuristic, not proof of memory/latency safety or a production API limit. Limited huge input must fail explicitly, not cause unbounded work, a silently lower floor or more timeout. **Before budget tightening**, the heavy 96px `long-shrink` case exhausted the deadline; it now rejects as `FIT_WORK_LIMIT` and retains FAIL rather than becoming a successful fit.

**Before correction:** a separate split probe improved the long-word auto-fit suggestion from 1px to 12px, while decomposed-accent auto-fit suggested 7px despite an explicit 28px fit. Those are retained probe history, not current helper behavior. Corrected local NFD and NFC fixtures fitted at 30px and the long word at 13px, with original UTF-8 hashes unchanged, counter caps and largest-measured-integer checks passing. Byte preservation is not complete native glyph/grapheme coverage: caption-internal clipping, multiline/paragraph/line-height, alignment, reference and exact-fill gates remain unresolved.

Local Python, pure fitter, existing self-test and smoke checks passed; both sequential 128-area candidates completed. Final visual execution had no process execution failures or Java comparison FAIL, but retained native FAIL/LIMITATION and Java LIMITATION; neither successful execution nor fit counters make that matrix all green. The 40MP solid-color native baseline exhausted the existing pixel cache **before text**: a capacity rejection, not a passed boundary or permission to raise limits. Keep a focused isolated Linux render/sandbox check at heap64m/256m, MemoryMax448M / MemorySwapMax64M before production enablement. Evidence stays private; old failures are not relabeled. Creator HMAC/session bootstrap and the versioned definition contract are now implemented; SQLite schema, durable receipts, versioned offline JSON bundle recovery integration and production text remain future backend work.

## 5. Client-only preview and deferred alternatives

**Initial preview is optional and client-only Konva.** Generate synthetic images/descriptions locally, reuse one example per referenced slot and keep them out of submitted definitions/media. Literal `K = 0` has no source examples; dynamic text-only examples use their referenced slots without inventing image areas. Pin the planned font files/weights and reuse original-pixel geometry, wrapping/overflow/style intent, while labeling the result as an approximation rather than promising equal browser/native pixels or fitted sizes. Final Magick rendering resolves real selected source descriptions and repeats validated fitting.

Preview performs no upload, creator API call, backend render, persistent draft, receipt, weight/history write or Telegram action. Submit remains independent and sends only its original image plus versioned definition/idempotency key through the authenticated contract. Local preview remains available without Telegram credentials; submission does not. Phase 3 adds local image-only examples; text preview follows in phase 4 after the complete backend is ready. This decision does not authorize text UI now.

Revisit only if an evident mismatch in useful layouts becomes a practical problem. Compare the same input/font/style/geometry and intended display size before changing architecture; possible follow-ups include server-rendered bitmap preview or text layers. Do not add a server-preview endpoint, Node/Skia runtime or headless browser preemptively. Optional preview stays optional even if the implementation changes later.

### Deferred native-preview design, not initial API scope

The following retained design is a possible follow-up **only if later justified and approved**, not a phase-2/3 dependency or an implemented endpoint. Persistent drafts remain separately deferred.

If a native-preview alternative is adopted, preview still must not gate submission. The client would generate synthetic placeholder images/descriptions; the server would use them only for that example. No real-source gallery or sample UUID lookup is implied. The current client-only choice does not implement any of this transport.

| Operation | Proposed contract |
| --- | --- |
| `POST /api/creator/template-previews` | Same `image` + versioned `definition` as submission; JSON `samples` manifest of `{ sourceSlot, imagePart, description }` and one bounded local PNG/JPEG part per referenced slot. Multipart names/paths are mapped by the server; descriptions remain untrusted literal text. Return `200 image/png` bytes or ordinary safe ProblemDetail. |
| Client display | Fetch with creator Authorization and `credentials: omit`, turn bytes into a local object URL and revoke on replacement/closure. Capture local document revision/content at click; discard late responses after editing/replacement. No persistent/public preview URL or credentials in an image `src`. |
| `POST /api/creator/template-submissions` | Submit current original image + definition with its own idempotency key. **No previewId, samples, prior call or draft required**. Samples are rejected/ignored according to the frozen strict DTO, never stored as real sources or used for publication selection. |

Every referenced slot appears exactly once in the manifest; repeated image/text areas use the same placeholder/description. Source-linked text-only templates supply samples for their slots even though they have no image areas; all-literal `K = 0` templates send an empty samples manifest and no sample-image parts. Propose small placeholder caps (≤256×256 each and ≤1 MiB total encoded samples) in addition to existing template and aggregate request/part/metadata bounds; confirm complete native work budgets in the PoC. Client generation is UX intent, not trusted provenance: validate/decode every sample, forbid remote URLs/paths/source IDs and apply admission even for authenticated friends. Output bytes/pixels/time remain bounded.

The native request uses owner-only temporary files and the same mixed renderer/font catalog. It never inserts REVIEW/draft/receipt/source/history records, changes weights or sends Telegram messages. Stream the bounded artifact and close `RenderedImage` after completion/error/disconnect; do not free final bytes prematurely or assume a browser abort stops native work. No persistent draft lifecycle, cache database or new permanent DRAFT status is needed. Later local restore/server drafts are separate enhancements. Administrator/publication renders continue selecting approved real sources, not these synthetic examples.

## 6. Decisions and what they actually block

User answers settle the product direction. Session authentication and the typed definition foundation are implemented/tested; remaining storage/submission/runtime recommendations need their owning tests. Do not repeat answered questions or restart PoC work as a prerequisite for backend progress.

| ID | Question / recommendation | Needed before | Unblocked work |
| --- | --- | --- | --- |
| D1 — resolved | Initial preview is **optional client-only Konva**, with an approximate match accepted. Server preview/other alternatives are deferred unless an evident mismatch becomes a practical problem. | Local image preview in phase 3; text preview in phase 4 | No server-preview implementation/test dependency; auth/backend work does not wait for preview parity. |
| D2 — resolved | Retire manual **template** CSV/document submission at the first new API/editor deployment, with downtime and no dual live flow; source uploads remain. | Coordinated phases-2/3 cutover implementation/backup/reconciliation | Backend/frontend tests and stored legacy migration. |
| D3 — bootstrap implemented | Telegram-only creator session verifies raw initData with bot-token HMAC; bounded bearer registry and full-filter tests are implemented. Confirmed Criar menu and separate root admin entry remain unchanged. No real launch/initData end-to-end test is claimed. | Frontend wiring and eventual submission/notification ownership tests | Durable receipts/storage and mixed generation. |
| D4 — product resolved | pt-BR; Liberation Sans/Serif/Mono regular/bold; descriptions mandatory; automatic shrink, no clipping; **text-only templates valid**, including dynamic slots without image areas and all-literal `K = 0`. Artifact/glyph/work-limit proof remains. | Mixed layout/catalog implementation and native proof | Image-only, mixed, dynamic text-only and zero-source literal fixtures. |
| D5 — development direction accepted | **Magick text + composition accepted for implementation.** Shared fitting correction is retained; normal glyph/style issues become focused backend regressions, not another comparison matrix. Keep admission/timeouts and a focused isolated Linux render/sandbox check; no memory increase or false capacity pass. | Production enablement, not backend development | Complete backend/storage/workflow integration now. |
| D6 — resolved | Synthetic placeholder images/descriptions stay **client-local** in the initial preview; reuse slots, use no samples for literal K=0, and do not submit/persist samples or look up real sources. | Local phase-3/4 preview/definition tests | Final-submit and mixed/text-only publication/recovery work require no preview API. |
| D7 — definition contract implemented; storage open | Version-1 image/text records, strict codec and slot/geometry/style validation exist. Next propose/freeze SQLite-only definition/origin/receipt schema, canonical fingerprint/version, versioned offline JSON bundle compatibility and post-write rollback; no new JsonDB feature or mixed data through legacy areas. Production's SQLite engine migration is complete. | Durable submission/schema migration implementation | SQLite repository/bundle recovery tests on generated fixtures, preserving existing legacy tests, then renderer/workflow integration. |
| D8 — bootstrap limits implemented; durable admission open | Bootstrap age/skew/session TTL and in-memory throttle/capacity are implemented as above. Upload quotas and durable receipt/tombstone caps/replay horizon remain to implement; UUID tombstones cannot simply expire safely. Visible committed receipt/admin queue first, no implicit notification resend. | Submission API enablement | Deterministic storage/quota/restart/failure tests. |

### References for the feasibility/authentication work

- [Telegram launch identity and validation](https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app).
- [ImageMagick property/percent escapes](https://imagemagick.org/escape/) and [version-dependent defines](https://imagemagick.org/defines/).
- [Java 21 TextLayout](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/font/TextLayout.html); inspect line breaking, font loading and native headless dependencies rather than assuming one `drawString` call implements the contract.
