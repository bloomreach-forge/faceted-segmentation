# Faceted Segmentation — Open UI Extension

A Bloomreach brXM Open UI **document field extension** that replaces a wall of multi-select
facet palettes with one field holding a readable boolean expression:

```
Signed In IN [Signed In]
  AND NOT (
       Size IN [Small]
    AND Day of the Week IN [Sunday]
  )
```

The grammar, the design decisions (referenced below as D1…D14) and the module layout are in the
[root README](../README.md).

## Stack

React 19 · TypeScript 5.9 · Vite 6 · `@bloomreach/ui-extension` 17.2.0 (matching brXM 17.2.0).

The `src/expression/` layer is deliberately **framework-free** — no React import — so the
grammar can be reused by tooling, ported, or tested in isolation.

## Quick start — standalone, no brXM required

```bash
npm install
npm run dev        # -> http://localhost:5173
```

**The extension cannot function without a reachable catalog endpoint.** There is no bundled
fallback and no development stub: `catalogUrl` must point at a real endpoint serving the
[catalog contract](#1-a-facet-catalog-endpoint), which in production is the
HST REST resource in the site module. Without it the field renders the stored expression but
reports the failure and disables the builder — see [Diagnosing catalog
failures](#diagnosing-catalog-failures).

This is deliberate. A bundled fixture would let an editor build rules against facets the
repository may no longer have, and silently ship a stale catalog inside the deployed artifact.

Opening <http://localhost:5173> directly in a browser is still useful for layout and styling
work. Two expected errors there: `UiExtension.register()` rejects with `NotInIframe` outside the
CMS, and the catalog fetch fails unless an endpoint is running.

For the full round trip — `getValue`, `setValue`, `setHeight('auto')`, dialogs, compare mode —
the extension has to run inside brXM. See [Installing into brXM](#installing-into-brxm).

## Requirements

What a host project must provide before this extension works. Nothing here is optional.

### Toolchain

| Requirement | Version | Notes |
|---|---|---|
| **Node** | `^20.19.0 \|\| ^22.12.0 \|\| >=24.0.0` | Declared in `engines`. Developed and verified on **24.19.0** (pinned in `.nvmrc`). |
| **npm** | `>=10.0.0` | Verified on **11.17.0**. Needs to understand `lockfileVersion: 3`. |
| brXM | 17.x | Built against `@bloomreach/ui-extension` 17.2.0; the SDK major should match the platform. |
| JDK | 21 | For the host brXM project only — **not** needed to build this extension. |

```bash
nvm use          # reads .nvmrc -> 24.19.0
node --version   # v24.19.0
npm --version    # 11.17.0
```

**Why that Node range**, rather than a bare "Node 20+": it is the intersection of what the
dependencies actually declare, with Vite 6 as the binding constraint —

| Package | Declares |
|---|---|
| `vite@6.4.3` | `^18.0.0 \|\| ^20.0.0 \|\| >=22.0.0` |
| `vitest@2.1.9` | `^18.0.0 \|\| >=20.0.0` |
| `typescript@5.9.3` | `>=14.17` |

Note Vite's range **excludes odd-numbered Node releases** (19, 21, 23) — those are
non-LTS and unsupported, so `node 23` will fail even though it is "newer than 20". The floors
`20.19.0` and `22.12.0` are Vite 6's documented minimums within each line.

**One deliberate omission: `engine-strict` is not enabled.** Setting it in `.npmrc` would make
the range above a hard error rather than a warning, which sounds desirable — but npm applies
`engine-strict` to **dependencies too**, and `@bloomreach/ui-extension@17.2.0` declares
`{"node":"14","npm":"7"}`, so install fails outright:

```
npm error notsup Required: {"node":"14","npm":"7"}
npm error notsup Actual:   {"node":"v24.19.0","npm":"11.17.0"}
```

That declaration is stale metadata, not a real constraint — the SDK imports and runs correctly on
Node 24, and the full test suite passes against it. So `engines` here is advisory (npm warns), and
`.nvmrc` is the practical mechanism. Do not add `engine-strict=true` without also pinning an
override for the SDK.

### Reproducible installs

- `package-lock.json` is committed and is `lockfileVersion: 3`. Use **`npm ci`** in CI and
  whenever you want the exact locked tree; `npm install` may update it.
- `npm run catalog:generate` rewrites `test/fixtures/facets.json` including a `generatedAt` timestamp, so
  it shows as modified after every run even when the content is identical — compare the `etag`
  field, not the diff, to tell whether the facets actually changed.

### 1. A facet catalog endpoint

The Open UI SDK **cannot read Value Lists**, or any repository content outside the open document
— verified against the shipped type definitions. So the extension fetches its facets over HTTP
and the host project must serve them.

- Contract: `GET <catalogUrl>` → the JSON below. Supports `If-None-Match` → `304`, and
  `?refresh=true` to bypass the server cache.

  ```json
  {
    "etag": "sha256-...",
    "generatedAt": "2026-09-25T23:36:19.221Z",
    "facets": [
      {
        "id": "size",
        "name": "Size",
        "path": "/content/documents/administration/faceted-segmentation/size",
        "values": [
          { "key": "S", "label": "Small" },
          { "key": "M", "label": "Medium" }
        ]
      }
    ]
  }
  ```

  `id` is the value list's node name and is what the `facets` allow-list matches; `name` is its
  `hippo:name` and is the token that appears in expressions (D3). `path` is informational.
  The canonical types are `src/catalog/types.ts` and, on the Java side,
  `FacetCatalog` in the `hst` module.
- Production implementation is a **purpose-built HST REST resource** in the site module, chosen
  over the generic Content REST API to keep the integration surface to this one field.
- **There is no development stub**, deliberately, so that nothing can run against fixture data by
  accident. `scripts/generate-catalog.mjs` writes only `test/fixtures/facets.json`, consumed by the
  test suite — never at runtime.

### 2. Value Lists as the source of facets

Standard `selection:valuelist` documents of `selection:listitem` children with `selection:key`
and `selection:label`. Expressions store **labels** (D2), and facet tokens are the Value List's
`hippo:name` (D3), so the Value List is the single place display text is edited.

The plugin ships three example value lists (Day of the Week, Signed In, Size) in the folder it
reads by default, so a fresh install works; point it at your own folder as described in
[hst/README.md](../hst/README.md).

Two constraints on the data, which the host project owns:

- **No label may contain a comma or a square bracket** — values are unquoted in the grammar.
  Parentheses, `&`, `®`, `™`, `%`, `/` and `-` are all safe.
- **No Value List display name may contain `AND`, `OR`, `NOT` or `IN` as a standalone word** — a
  facet named e.g. "Index IN Scope" breaks tokenizing. Re-validate when adding Value Lists.

### 3. A string field of type `OpenUiString`

```yaml
/faceted_segmentation:
  jcr:primaryType: hipposysedit:field
  hipposysedit:path: <ns>:faceted_segmentation
  hipposysedit:type: OpenUiString
  hipposysedit:multiple: false
```

…with the editor template naming the registration node:

```yaml
/faceted_segmentation:
  jcr:primaryType: frontend:plugin
  caption: Faceted Segmentation
  field: faceted_segmentation
  plugin.class: org.hippoecm.frontend.editor.plugins.field.PropertyFieldPlugin
  wicket.id: ${cluster.id}.field
  /cluster.options:
    jcr:primaryType: frontend:pluginconfig
    ui.extension: facetedSegmentation
```

### 4. A `frontend:uiExtension` registration node

```yaml
/hippo:configuration/hippo:frontend/cms/ui-extensions/facetedSegmentation:
  jcr:primaryType: frontend:uiExtension
  frontend:displayName: Faceted Segmentation      # MANDATORY — see below
  frontend:extensionPoint: document.field         # MANDATORY — defaults to channel.page.tools
  frontend:url: https://extensions.example.com/index.html
  frontend:initialHeightInPixels: 120
  frontend:config: '{"catalogUrl":"https://api.example.com/api/facets","absentFacetPolicy":"permissive"}'
```

Three traps, all verified in the brXM CND (`[frontend:uiExtension]` in
`community/cms/types/.../frontend.cnd`) rather than taken from the documentation:

- **`frontend:displayName` is mandatory**, even though its value is unused for document field
  extensions. Omitting it fails config apply on boot with
  `ConstraintViolationException: mandatory property ...displayName does not exist`.
- **`frontend:extensionPoint` is mandatory and defaults to `channel.page.tools`** — it must be
  set explicitly to `document.field`.
- **Node names must be alphanumeric.** `faceted-segmentation` is rejected by
  `UiExtensionValidator`; `facetedSegmentation` is fine.

### 5. Content Security Policy allowances

CSP is on by default from brXM 15. Add the extension's origin and the catalog's origin at
`/hippo:configuration/hippo:modules/application-settings/hippo:moduleconfig/content-security-policy`:

- **`frame-src`** — the extension origin (`frontend:url`)
- **`connect-src`** — the catalog origin (`catalogUrl`)

```yaml
definitions:
  config:
    /hippo:configuration/hippo:modules/application-settings/hippo:moduleconfig/content-security-policy:
      jcr:primaryType: nt:unstructured      # NOT hipposys:moduleconfig
      frame-src:
        operation: add                      # add, never replace
        value: ['extensions.example.com']
      connect-src:
        operation: add
        value: ['extensions.example.com', 'api.example.com']
```

- **Use `operation: add`.** The platform ships a populated default list (auth0,
  `tools.bloomreach.com`, userguiding, Google Maps); a plain definition replaces it and breaks
  unrelated CMS features.
- Values are bare `host[:port]` — no scheme, no quoted `'self'`.
- **Missing `connect-src` is the confusing failure**: the field renders fine and the catalog
  silently fails to load. The UI surfaces this as a visible error rather than an empty facet list.
- Same-origin hosting needs no CSP entries at all. Bloomreach nonetheless recommends a separate
  origin, and `[frontend:uiExtension]` also permits a per-extension
  `content-security-policy` child node — **unverified**, but potentially tidier than mutating
  shared config.

### 6. Per-environment origins

`frontend:url` and the CSP host lists differ per environment (local, UAT, production) and
**must not be baked into a build**. Since brXM 14.7 `frontend:config` resolves `${...}` against
the CMS container configuration, so use e.g. `"catalogUrl": "${facetCatalog.url}"` and define it
in `$CATALINA_BASE/conf/platform.properties` per environment. `frontend:url` itself is per
environment YAML.

### 7. A delivery-tier evaluator

This extension only authors and renders the expression. **Nothing enforces it at request time
until the delivery tier parses and evaluates it.** That implementation must:

- mirror the grammar exactly (D12) — which the `common` module's Java parser and evaluator already
  do, and which its mirrored test suites keep true
- use the **same `absentFacetPolicy`** as `frontend:config`, or the builder's preview disagrees
  with production

`src/expression/evaluate.ts` is the reference implementation, with `trace()` for debugging.

## Installing into brXM

1. `npm run build` → `dist/` (two entry points: `index.html`, `dialog.html`)
2. Host `dist/` somewhere the browser can reach — ideally a different origin than the CMS
3. Add the registration node (requirement 4) and the CSP allowances (requirement 5)
4. Point `frontend:url` at the hosted `index.html`, and `catalogUrl` at your catalog endpoint
5. Set `ui.extension` on each field that should use it (requirement 3)

For local development, `frontend:url: http://localhost:5173/index.html` against `npm run dev`
works. The CSP needs `localhost:5173` in `frame-src`, plus whatever origin serves the catalog in
`connect-src`.

## Scripts

| Script | Purpose |
|---|---|
| `npm run dev` | Vite dev server on :5173 |
| `npm run build` | Production build to `dist/` (two entry points) |
| `npm test` | Vitest run |
| `npm run test:watch` | Vitest watch |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run catalog:generate` | Regenerate the **test fixture** `test/fixtures/facets.json` from the plugin's own Value Lists in `repository/` |

**Note on `ETag`:** it must be quoted (`ETag: "sha256-..."`) as RFC 9110 requires. This client
echoes back whatever it stored, so an unquoted value appears to work — but a spec-strict
intermediary would return 200 where a 304 was intended.

## Layout

```
src/
  expression/          framework-free grammar layer
    ast.ts             node types, constructors, traversal
    parse.ts           recursive-descent parser
    format.ts          canonical (deterministic) formatter
    evaluate.ts        reference evaluator: policy-aware, + trace
    validate.ts        checks an expression against the live catalog
    transform.ts       immutable tree edits used by the builder
  catalog/
    types.ts           catalog contract
    client.ts          two-layer cache: localStorage + backend
  ui/
    ExpressionView.tsx syntax-tinted read-only rendering
    ValuePicker.tsx    searchable multi-select for one facet
    NodeEditor.tsx     recursive builder tree
    styles.css         Open UI style guide values
  FieldApp.tsx         inline document field (entry: index.html)
  DialogApp.tsx        builder dialog (entry: dialog.html)
  config.ts            frontend:config parsing
scripts/
  generate-catalog.mjs  plugin Value List YAML -> test/fixtures/facets.json (tests only)
test/
  fixtures/facets.json  generated catalog fixture, NOT a runtime source
```

## Two entry points

Open UI dialogs load their own URL in a separate iframe, so the build emits two pages:

- `index.html` → `FieldApp` — the inline field. Renders the expression, grows to fit, and
  opens the dialog.
- `dialog.html` → `DialogApp` — the builder. Receives the current expression via
  `ui.dialog.options().value` and returns the new one via `ui.dialog.close()`.

## The grammar in brief

```
expression = or_expr
or_expr    = and_expr { "OR" and_expr }
and_expr   = unary { "AND" unary }
unary      = [ "NOT" ] primary
primary    = "(" expression ")" | condition
condition  = facet ("NOT IN" | "IN") "[" value_list "]"
```

Precedence: `NOT` > `AND` > `OR`. An empty string means **ungated** — shown to everyone.

Facet tokens are Value List **display names** and bracketed values are Value List **labels**,
both of which contain spaces, so tokenizing is context-sensitive: bracket contents are consumed
whole before grouping parentheses are considered, and a facet name is read greedily up to the
operator keyword. Full spec, including why that is safe for a label like
`Multi-Asset Index (MAX)`, in the [root README](../README.md#the-grammar).

## Tests

```bash
npm test
```

113 tests. Beyond unit coverage of the parser, formatter, evaluator and transforms,
`catalog-integration.test.ts` runs against the **generated catalog of the value lists the plugin
ships** (3 facets, 12 values) and asserts:

- every shipped label parses as a condition value
- each facet's full-selection expression round-trips through format→parse
- no label contains a comma or bracket, and no facet name collides with a keyword
- a multi-word facet name is read greedily, and `Signed In IN [Signed In]` is not mistaken for the
  operator
- a renamed label, and a renamed facet, are flagged rather than silently dropped
- inverting a majority selection to `NOT IN` is equivalent to `IN` whenever a value is present, and
  **diverges under `permissive`** when the facet is absent

`common/src/test/.../ShippedCatalogIntegrationTest.java` mirrors this suite against the same
fixture, in the Java parser that actually runs on the delivery tier (D12).

Because the shipped value lists are small and tidy, they do **not** exercise labels containing
parentheses, `&`, `®` or `/`, nor a list of 50+ values. Tokenizing is covered directly by
`expression.test.ts`; a project whose own value lists contain such labels should validate them with
`npm run catalog:generate`, which warns on anything unsafe.

## Configuration

`frontend:config` on the `frontend:uiExtension` node, parsed by `src/config.ts`:

```json
{
  "catalogUrl": "${facetCatalog.url}",
  "cacheTtlSeconds": 900,
  "dialogSize": "large",
  "absentFacetPolicy": "permissive",
  "facets": ["channel", "distribution", "license-state"]
}
```

`facets` is an optional allow-list of Value List **ids** (`day-of-the-week`, not `Day of the Week`);
omit it to offer all. Since brXM 14.7, `${...}` resolves from the CMS container configuration, so
`catalogUrl` can differ per environment without a rebuild.

**The unit of configuration is the registration node, not the field.** `OpenUiBehavior` hands the
iframe `extension.getConfig()` from the `frontend:uiExtension` node; a field's `cluster.options`
contributes only `ui.extension`, naming *which* node to use. So to offer different facets on
different document types, **register the extension once per facet profile** — same built assets,
different `frontend:config` — and have each field name the profile it wants. A per-field property
on the namespace cannot work, because nothing would carry it to the iframe.

`absentFacetPolicy` is `strict` (default, fail closed) or `permissive`, controlling whether a
`NOT IN` rule matches a visitor who has **no** value for that facet (D11). This setting only drives
the builder's preview — **the Java delivery tier is authoritative and must be configured to
match.**

## Diagnosing catalog failures

The catalog endpoint is a hard dependency, so each failure mode reports separately rather than
collapsing into "could not load". Every message names the URL and what to check; the field shows
it inline and the full diagnosis goes to the browser console under `[faceted-segmentation]`.

| What happened | `kind` | What the message tells the editor |
|---|---|---|
| `fetch` rejected | `unreachable` | Check in order: CSP `connect-src` allows the origin; the endpoint is deployed; it returns permissive CORS headers. The browser reports a CSP violation to the console but **not** to the page, which is why all three are named. |
| `catalogUrl` is not a valid URL | `unreachable` | Fix `catalogUrl` in `frontend:config`. |
| 404 | `not-found` | The path is wrong, or the REST resource is not mapped in the site webapp. |
| 401 / 403 | `unauthorized` | The endpoint wants credentials this client does not send — facet values are not user-specific and the endpoint should be readable unauthenticated. |
| 5xx | `server-error` | Reachable but failing; check the site webapp logs. |
| 304 with nothing cached | `server-error` | The endpoint sent 304 without an `If-None-Match` having been sent. |
| Response is not JSON | `not-json` | Reports the `content-type`; usually an HTML error page or a login redirect. |
| Missing `facets` array | `malformed` | Points at the endpoint contract. |
| `facets: []` | `malformed` | The endpoint works but found no value lists — check the repository path it reads. |

These arrive as `CatalogError` (exported from `src/catalog/client.ts`) carrying `url`, `kind` and
an optional `detail` with the underlying error.

**Degraded vs. failed.** A previously-cached catalog in `localStorage` is still served when the
endpoint goes down, with a `console.warn` saying the values may be stale — real data that was
once authoritative is better than blocking the editor. With nothing cached, the field renders the
stored expression as-is, shows the error, and **disables the builder button**, because a builder
with no facets cannot do anything useful.

## Caching

The Open UI SDK cannot read Value Lists at all, so the catalog is fetched over HTTP. Three layers
keep that sustainable:

1. **In-page memory** (`src/catalog/client.ts`) — every field instance on an open document shares
   one in-flight fetch and one result, so N sections don't each trigger their own request.
2. **`localStorage`**, under the fixed key `brxm.facetedSegmentation.catalog.v1` — survives dialog
   open/close and page reloads, so reopening the dialog is instant. Served without a network call
   whenever it is younger than `cacheTtlSeconds` (default 900s), and served **regardless of age**
   as a fallback if a live fetch then fails — see [Degraded vs.
   failed](#diagnosing-catalog-failures).
3. **In-memory TTL cache** in the catalog service (Java, not the browser) — one repository read
   serves every editor session, not just one document's fields.

**Resync facet values** in the dialog clears layers 1 and 2 and forces a fresh fetch (`?refresh=true`),
which also bypasses layer 3 on the server.

**The `localStorage` key is a fixed string, not scoped by `catalogUrl` or deployment.** Two
consequences:

- If facet names look wrong after changing `frontend:config` or pointing at a different backend,
  a stale browser-cached catalog is the first thing to rule out — **Resync facet values** clears it
  and re-fetches immediately, without leaving the CMS.
- Running the extension against more than one backend from the same browser origin (e.g. two
  `npm run dev` sessions on the default port, pointed at different `catalogUrl`s at different
  times) can serve one backend's cached catalog while configured against another, silently.
