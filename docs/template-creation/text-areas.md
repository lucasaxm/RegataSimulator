# Text areas and source-linked captions

**Status:** browser text exists in the historical spike, and a separate [native experiment](native-text-poc.md) has executed a first generated-fixture slice locally. Production templates, CSV, repositories and ImageMagick rendering remain unchanged. **Konva is selected for production**; the browser spike stays unchanged. Order remains architecture/native proof first, complete backend second, image-only frontend POST third, frontend text/color fourth. Local native rendering is not complete backend/publication/recovery or Linux/VPS proof; browser text layout alone is not generator support. See the [backend architecture](backend-architecture.md) and [implementation phases](proposal.md#incremental-delivery-and-acceptance).

## Product rule: one selected source, two representations

The supplied examples require the selected source's **image and description to stay paired**. Model a source slot independently of an area's kind. An image area bound to Source 1 and a text area bound to Source 1 consume **one** selected source, not two random draws.

Examples:

- Three photo areas bound to slots 1/2/3, with three corresponding text areas containing `Candidato {{source.description}}` or just `{{source.description}}`.
- A profile image and a text box both bound to slot 1, with `{{source.description}} entrou no grupo`.
- Static template text, without any placeholder, stays unchanged when example descriptions change.

`Source.description` already participates in metadata, normalized name uniqueness, filtering and selection; the missing feature is **rendering it into memes**. The spike never reads or modifies actual sources.

**Text-only templates are explicitly approved.** A base template bitmap can contain 1–128 text areas and no image areas. A dynamic box may use Source 1 without any paired photo box; several boxes referencing it still select that source once. An all-literal template uses `sourceSlot: null` and zero selected sources (`K = 0`). Keep an empty source list distinct from legacy null history; no dummy image area/source, random-pool requirement or source-weight decrement is introduced. Normal template moderation/publication/history still apply. Include both text-only variants in the native PoC and phase-2 repository/render/publication/optional-preview/restore tests.

## Recommended initial text contract

Keep a typed `image`/`text` area distinction and a library-independent document. A text area has:

| Field | Semantics |
| --- | --- |
| Source binding | Dynamic-text slot within this generation, independent of image areas; same slot if paired with an image. Null for literal-only text. |
| Rectangle | Original-image x/y/width/height; axis-aligned initially |
| Text pattern | Static text and optional `{{source.description}}` tokens |
| Font ID / weight | Curated font catalog and normal/bold, not an arbitrary path/URL/CSS string |
| Size / fitting floor | Font size in original-image pixels; automatic shrink with a bounded positive engineering floor, independent of editor zoom |
| Color / outline | Validated colors and bounded outline width |
| Optional box fill | Solid color behind the glyphs, useful for covering an existing caption; separate from the behind-template plane |
| Alignment / line-height | Left/center/right and bounded spacing |
| Overflow | Automatic shrink-to-fit; no clipping, ellipsis or dropped characters |
| Plane / order | Behind template or in front; images and texts share order within each plane |

The spike uses browser font aliases `sans`, `serif`, `mono`; this is **not** the production font catalog. Rectangle text is sufficient for both supplied examples. Rotation, perspective text, rich text, multiple source placeholders inside one box, automatic case changes and free-form expressions are deferred rather than hidden assumptions. A four-corner image and a rectangular text box can still share one editor scene.

### Plain-text substitution, not a scripting language

Support only the exact description token, with optional surrounding whitespace. Reject unknown/malformed tokens with a clear field error. No HTML, JavaScript, SpEL, Handlebars helpers, arbitrary object access or recursive evaluation. Insert a description literally in one pass: `<b>Nome</b>`, `{{unexpected}}`, percent sequences and backslashes in the description are **content**, not new instructions.

The local model bounds pattern and sample description to 512 UTF-16 units, expanded text to 16,384 and JSON to 128 KiB. These are **spike** limits, not an existing database guarantee. Set measured production work limits without changing description normalization or silently truncating metadata. The user confirms descriptions are mandatory in normal source intake: no missing-description UX/fallback decision is needed. Existing nullable/imported malformed data remains defensively validated; a selected invalid description fails explicitly rather than printing “null” or rerolling. This does not change historical nullable persistence contracts.

Resolve against the source selection already made for the generation. Required slots are the distinct union of **image and text bindings**, including text-only usage. For a nonempty union, select each slot once with the existing approved-source/history-capacity policy. For all-literal `K = 0`, bypass source selection/capacity/weight work and render with an empty selected-source list; zero image areas is not an error in the typed format. A reused slot is never reselected for its text. Reject missing/noncontiguous dynamic bindings before external work.

### Wrapping and fitting

Use the same documented layout rules in browser and server:

1. Substitute literal descriptions first; preserve explicit line breaks.
2. Wrap words within the box after padding/outline allowance; split oversized words only at grapheme boundaries (accents/surrogate pairs/ZWJ emoji must not be cut apart).
3. Automatically choose the largest bounded positive size ≤ requested size that fits width and height. Proposed engineering floor is 1 original-image pixel; fitting iterations are bounded and measured in the PoC, not an unbounded loop.
4. Never clip, ellipsize, drop characters or grow the template. Invalid/physically impossible boxes still need an actionable typed failure if nothing fits at the positive floor; “shrink” cannot guarantee arbitrary text fits any tiny box. Warn visibly about very small fitted sizes without adding a new clipping product option.

The historical spike shows resolved text, fitted size, line count and overflow state; its older clipping options are not the selected production policy. Store pattern/style, not a sample's fitted result. Native generation repeats automatic fitting for every real description; a synthetic short sample does not prove later names fit. Reject impossible/over-budget work explicitly, never silently clip or reroll publication.

MVP language is **pt-BR**, including accented Latin text. A few common plain font families are selected below; complex scripts/bidi/color emoji are not promised. Glyph/decode tests remain necessary even though missing descriptions are not a normal product case; browser display is not VPS proof.

## Fonts and native rendering: architecture-phase feasibility gate

Selected plain families: **Liberation Sans, Liberation Serif and Liberation Mono**, regular/bold (400/700), with Sans as default. [Upstream](https://github.com/liberationfonts/liberation-fonts) targets Arial/Times New Roman/Courier New metric compatibility; its [license](https://github.com/liberationfonts/liberation-fonts/blob/main/LICENSE) is OFL-1.1. The native harness prepares hash-pinned 2.1.5 faces/LICENSE in private temporary storage and checks sampled pt-BR glyphs. This is not a global font installation, shipped browser/server catalog or complete glyph/legal acceptance. Retain verified artifacts/notices before packaging; no proprietary OS aliases, uploaded/remote font URLs or silent unknown-ID fallback. Await browser font loading before measurement.

Do not replace ImageMagick speculatively or select a text engine from browser results. Compare **proven literal ImageMagick text** with a bounded **Java 21 headless Java2D/TextLayout rasterizer** producing a transparent PNG for each box, then reuse the existing compositor. A literal rasterizer boundary separates text/layout from perspective image distortion and gives typed ownership/cleanup. Text rasters belong in the same behind-template/template/foreground ordered stack. The [native PoC plan](backend-architecture.md#4-rendering-proof-of-concept-before-backend-implementation) defines safety, fonts/layout, full-image regression, resource and actual-hardening exit gates; no winner has been measured yet.

Argument-array ProcessBuilder calls prevent shell interpolation but do **not** make ImageMagick's text grammar literal: [percent/property escapes](https://imagemagick.org/escape/) include `%[...]` and `fx:` expression expansion. Do not directly feed untrusted descriptions into `label:`, `caption:`, `-annotate`, MVG/Pango/HTML or file-indirection-capable text operands and assume quoting solves it. If a native text API is chosen instead, prove its literal semantics and resource bounds with hostile-string tests.

Server-owned paths, bounded raster dimensions/pixels/text/layout iterations, font IDs/colors and render admission must be validated before expensive work. The documented host has a 256m maximum heap and 448M service ceiling shared with native children; a 40MP RGBA buffer alone is about 160MB. Use bounded box-sized rasters and aggregate work/deadline/admission budgets, not a full template buffer per text box or a raised memory ceiling. Headless Java does not itself prove font/native-library availability or hard cancellation. Preserve image distortion, masks, alpha, interruption, owner-only scratch and final-artifact ownership. No network/font loading in a render transaction; selection, rasterization and Telegram/filesystem effects are not database transactions.

Native previews must exercise fixed long/short/multiline/accented descriptions, glyph fallbacks, outline extents, alpha and both planes under the real sandbox. Browser and native font metrics may differ: **server preview remains authoritative**, and any browser approximation must be labeled. No native text parity or live publication was tested in this spike.

## Persistence and compatibility work, deliberately not done here

Current `TemplateArea`/CSV and `ImageRenderer.Request` represent image quadrilaterals only. Do not bolt text properties onto CSV rows, infer text from magic source IDs, or silently export only image areas. The spike imports/exports the existing CSV only for image-only documents and refuses CSV export when any text exists. Mixed documents use clearly experimental typed JSON; the bot cannot accept it. The local image is not embedded in that JSON.

Design the versioned template definition and concrete payload/response/model changes in phase 1; implement validation/selection/rendering and both repository/recovery contracts during backend phase 2, **before** either frontend milestone. The [proposed definition](backend-architecture.md#definition-payload) supports typed image/text fields from the first new API version; the initial frontend uses only its image subset. Legacy image-only definitions/CSV translate unchanged. Use closed DTO discriminators, not unrestricted polymorphic deserialization. Include safe gallery/import attribution, explicit web origin/confirmation, durable actor/key receipts, JsonDB/SQLite and offline import/export/backup/restore/rollback. New schema/binary compatibility needs migration tests and operator approval; neither this browser spike nor the plan authorizes a data cutover.

Unknown template versions must fail clearly rather than drop text or overwrite old definitions. Retain original media/metadata during conversions. Test mixed templates through functional restore and post-write export before enabling them; existing image templates must still generate exactly as before.

## Next acceptance slice

- **Phase 1:** prioritize authentication proof and isolated native text fixtures using pt-BR, selected Liberation regular/bold families and automatic shrink. Product policies are settled; prove glyph/fitting/literal safety and complete Magick versus Java2D+compositor costs under current caps. Investigate replacement only after a measured failed gate. No new frontend text controls yet.
- **Phase 2:** implement complete mixed definitions, shared-slot selection, literal layout/rasterization and meme generation, creator identity/POST/receipt/moderation handling, both stores and migration/recovery. Test publication with long/null/glyph/overflow/alpha/plane fixtures, not just an example preview or JSON acceptance.
- **Phase 3:** connect the **image-only** editor to Telegram-authenticated submit and optional Preview using client-generated placeholders. Deploy with the backend and retire manual template CSV/document upload and active legacy fallback, with downtime allowed; preserve stored data and source intake, not dual live workflows. No text UI.
- **Phase 4:** add text rectangles and compact font/color/box-fill panels, template-pixel sampling and visible selected-region feedback on the real Konva editor, using backend capabilities/fonts. Validate Android Brave, iOS Safari and Telegram WebViews rather than inferring parity from emulation; magnifier/precision/accessibility are specified in the [proposal](proposal.md).

Continue independent fixture/contract work; the [decision register](backend-architecture.md#6-decisions-and-what-they-actually-block) separates resolved choices from remaining evidence. Optional native Preview is request-scoped and uses client-generated synthetic slot images/descriptions; persistent drafts and preview-before-submit checks are not required. The backend implements it in phase 2, before the image-only frontend in phase 3. No text may be lost through legacy CSV at any phase.