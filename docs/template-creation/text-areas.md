# Text areas and source-linked captions

**Status:** supported in the isolated browser spike; production templates, CSV, repositories and ImageMagick rendering are unchanged. **Konva is selected for production**; the spike stays unchanged. See the [implementation proposal](proposal.md) for canvas-first contextual text controls, portable color sampling and the staged native/font/versioning work, and the [spike guide](spike.md) for the historical comparison.

## Product rule: one selected source, two representations

The supplied examples require the selected source's **image and description to stay paired**. Model a source slot independently of an area's kind. An image area bound to Source 1 and a text area bound to Source 1 consume **one** selected source, not two random draws.

Examples:

- Three photo areas bound to slots 1/2/3, with three corresponding text areas containing `Candidato {{source.description}}` or just `{{source.description}}`.
- A profile image and a text box both bound to slot 1, with `{{source.description}} entrou no grupo`.
- Static template text, without any placeholder, stays unchanged when example descriptions change.

`Source.description` already participates in metadata, normalized name uniqueness, filtering and selection; the missing feature is **rendering it into memes**. The spike never reads or modifies actual sources.

## Recommended initial text contract

Keep a typed `image`/`text` area distinction and a library-independent document. A text area has:

| Field | Semantics |
| --- | --- |
| Source binding | Slot within this generation, not a persisted source UUID; same slot as its image |
| Rectangle | Original-image x/y/width/height; axis-aligned initially |
| Text pattern | Static text and optional `{{source.description}}` tokens |
| Font ID / weight | Curated font catalog and normal/bold, not an arbitrary path/URL/CSS string |
| Size / minimum | Font size in original-image pixels, independent of editor zoom |
| Color / outline | Validated colors and bounded outline width |
| Optional box fill | Solid color behind the glyphs, useful for covering an existing caption; separate from the behind-template plane |
| Alignment / line-height | Left/center/right and bounded spacing |
| Overflow | Explicit shrink-to-fit or intentional clip; never silent data truncation |
| Plane / order | Behind template or in front; images and texts share order within each plane |

The spike uses browser font aliases `sans`, `serif`, `mono`; this is **not** the production font catalog. Rectangle text is sufficient for both supplied examples. Rotation, perspective text, rich text, multiple source placeholders inside one box, automatic case changes and free-form expressions are deferred rather than hidden assumptions. A four-corner image and a rectangular text box can still share one editor scene.

### Plain-text substitution, not a scripting language

Support only the exact description token, with optional surrounding whitespace. Reject unknown/malformed tokens with a clear field error. No HTML, JavaScript, SpEL, Handlebars helpers, arbitrary object access or recursive evaluation. Insert a description literally in one pass: `<b>Nome</b>`, `{{unexpected}}`, percent sequences and backslashes in the description are **content**, not new instructions.

The local model bounds pattern and sample description to 512 UTF-16 units, expanded text to 16,384 and JSON to 128 KiB. These are **spike** limits, not an existing database guarantee. Audit real legacy description lengths on isolated fixtures/copies before setting production limits; do not change description uniqueness/normalization or silently truncate old metadata to fit a caption. Missing/null descriptions need an explicit fallback/error policy before implementation. Prefer an actionable preview warning rather than rendering “null”.

Resolve against the source selection already made for the generation. Required slots are the distinct union of **image and text bindings**, including text-only usage. Select each slot once with the existing approved-source/history-capacity policy. A slot referenced by multiple areas must never be reselected for its text. Reject missing/noncontiguous bindings before external work.

### Wrapping and fitting

Use the same documented layout rules in browser and server:

1. Substitute literal descriptions first; preserve explicit line breaks.
2. Wrap words within the box after padding/outline allowance; split oversized words only at grapheme boundaries (accents/surrogate pairs/ZWJ emoji must not be cut apart).
3. For shrink, choose the largest bounded integer size down to the minimum that fits width and height. For fixed size, keep it and expose overflow.
4. If nothing fits at the minimum, show a visible warning and require editing or an explicitly chosen clip policy. Never introduce unnoticed ellipsis, drop characters or grow the template unpredictably.

The spike shows the resolved text, fitted size, line count and overflow state. It stores the pattern/style, not just the fitted result for one sample. A future production generator must repeat fitting for every selected description; the shortest sample does not prove every future name fits. Establish publication behavior for an unfit later description—safe explicit failure or deliberate per-template clipping—not unlimited rerolls of publication.

MVP scope is Portuguese/Latin text with accents. Complex scripts, bidi, emoji color fonts and glyph coverage need explicit renderer/font tests; a browser display is not proof that the VPS can produce the same glyphs.

## Fonts and native rendering: follow-up gate

Do not depend on Arial/Georgia/OS fallback fonts being installed identically on macOS, Android, iOS and Linux. Bundle a small, versioned/hash-verified, appropriately licensed catalog for browser and server, retain notices, await browser font loading before measuring, and reject unknown font IDs. [Noto Sans's upstream license](https://github.com/google/fonts/blob/main/ofl/notosans/OFL.txt) is OFL-1.1; it is a candidate, not an asset installed by this spike. Inspect each selected face/weight and redistribution requirements before shipping. Do not allow font uploads or external font URLs in the MVP.

Keep ImageMagick as the compositor, but prefer a bounded **literal text rasterizer** producing a transparent PNG for each text area (for example a tested headless Java2D/TextLayout boundary with the pinned catalog). Then place that raster in the same behind-template/template/foreground stack as image areas. This avoids turning source descriptions into ImageMagick command strings and gives typed ownership/cleanup.

Argument-array ProcessBuilder calls prevent shell interpolation but do **not** make ImageMagick's text grammar literal: [percent/property escapes](https://imagemagick.org/escape/) include `%[...]` and `fx:` expression expansion. Do not directly feed untrusted descriptions into `label:`, `caption:`, `-annotate`, MVG/Pango/HTML or file-indirection-capable text operands and assume quoting solves it. If a native text API is chosen instead, prove its literal semantics and resource bounds with hostile-string tests.

Server-owned paths, bounded raster dimensions/pixels/text/layout iterations, font IDs/colors and render admission must be validated before expensive work. Preserve current image distortion, masks, transparent-template occlusion, interruption, per-job scratch and final-artifact ownership. No network/font loading in a render transaction; source selection, text measurement/rasterization and Telegram/filesystem effects are not database transactions.

Native previews must exercise fixed long/short/multiline/accented descriptions, glyph fallbacks, outline extents, alpha and both planes under the real sandbox. Browser and native font metrics may differ: **server preview remains authoritative**, and any browser approximation must be labeled. No native text parity or live publication was tested in this spike.

## Persistence and compatibility work, deliberately not done here

Current `TemplateArea`/CSV and `ImageRenderer.Request` represent image quadrilaterals only. Do not bolt text properties onto CSV rows, infer text from magic source IDs, or silently export only image areas. The spike imports/exports the existing CSV only for image-only documents and refuses CSV export when any text exists. Mixed documents use clearly experimental typed JSON; the bot cannot accept it. The local image is not embedded in that JSON.

Before production adoption, design a versioned template definition with validated kind-specific fields, stable area order and bounded source bindings. Legacy image-only definitions/CSV must translate unchanged. Use explicit allowed discriminators/DTOs, not unrestricted polymorphic deserialization. Update selection, renderer inputs, safe gallery/import projections and validation in one scoped feature slice, then both JsonDB/SQLite adapters and offline import/export/backup/restore/rollback contracts. New schema/binary compatibility needs separate migration tests and operator approval; this spike does not authorize a data cutover.

Unknown template versions must fail clearly rather than drop text or overwrite old definitions. Retain original media/metadata during conversions. Test mixed templates through functional restore and post-write export before enabling them; existing image templates must still generate exactly as before.

## Next acceptance slice

- Agree on the source-bound pattern, axis-aligned MVP, font catalog, missing-description and overflow policies.
- Build isolated text-rasterizer/native preview fixtures before any schema change; compare with the browser at full original-image dimensions.
- Add versioned mixed definitions/source-slot union selection, compatibility/migration/recovery coverage and then creator integration.
- Implement the selected Konva editor with compact font/color panels, template-pixel sampling and a visible selected-region preview instead of the laboratory's long inspector. Validate on Android Brave, iOS Safari and actual Telegram WebViews, not just Chromium emulation; magnifier, precision controls and accessibility requirements are specified in the [proposal](proposal.md).