# Bloomreach Forge — Faceted Segmentation

A Bloomreach Experience Manager (brXM) plugin that gates content on audience facets using a single
readable boolean expression, instead of a wall of multi-select facet palettes.

An editor writes this, in one field:

```
Signed In IN [Signed In]
  AND (
       Size IN [Large]
    OR Day of the Week IN [Saturday, Sunday]
  )
```

…and the delivery tier evaluates it against the current request.

## Why

The conventional approach gives each section of a document one multi-select palette per facet.
Two problems follow:

- **Content duplication.** Parallel palettes cannot express `A AND (B OR C)` within one section,
  so editors duplicate identical content into extra sections just to reach a different audience
  combination.
- **Visual footprint.** A dozen palettes per section, some with dozens of values, means a document
  spans many screens and editors scroll past facets to reach content.

One expression per section solves both: a shared condition is stated once, and the rule reads as a
sentence.

## Modules

| Module | Artifact | What it is |
|---|---|---|
| [`common/`](common) | `bloomreach-facetedsegmentation-common` | The grammar: parser, formatter, evaluator. No brXM dependency. |
| [`hst/`](hst) | `bloomreach-facetedsegmentation-hst` | Delivery tier — component, content beans, the facet catalog REST resource. See [hst/README.md](hst/README.md). |
| [`repository/`](repository) | `bloomreach-facetedsegmentation-repository` | CMS side — document types, the Open UI registration, example value lists. |
| [`extension/`](extension) | *(npm, not Maven)* | The Open UI field itself — React + TypeScript + Vite. See [extension/README.md](extension/README.md). |
| [`demo/`](demo) | — | A brXM project consuming the published artifacts, as a customer project would. Not a Maven module of the root pom, so it is never released. |

The grammar exists **twice**, in Java and TypeScript, because the builder runs in a browser and the
evaluator runs on the delivery tier. They are kept in lockstep by mirrored test suites
([D12](#d12--one-grammar-two-implementations-kept-in-lockstep)).

## Installing into a project

The four Maven artifacts are on the
[Bloomreach Forge repository](https://maven.bloomreach.com/repository/maven2-forge/). Add the
repository to your project's root pom if it is not already there:

```xml
<repository>
  <id>bloomreach-maven2-forge</id>
  <url>https://maven.bloomreach.com/repository/maven2-forge/</url>
</repository>
```

### 1. Dependencies

In your **CMS** module (`cms-dependencies/pom.xml` or `cms/pom.xml`):

```xml
<dependency>
  <groupId>org.bloomreach.forge.facetedsegmentation</groupId>
  <artifactId>bloomreach-facetedsegmentation-repository</artifactId>
  <version>1.0.0</version>
</dependency>
```

In your **site components** module (`site/components/pom.xml`):

```xml
<dependency>
  <groupId>org.bloomreach.forge.facetedsegmentation</groupId>
  <artifactId>bloomreach-facetedsegmentation-hst</artifactId>
  <version>1.0.0</version>
</dependency>
```

`common` comes in transitively with either. Both are ordinary `compile` dependencies — every brXM
dependency inside the plugin is `provided`, so nothing platform-owned is bundled.

### 2. Let HST find the content beans

The plugin's content beans live in `org.bloomreach.forge.facetedsegmentation.beans`, which the
standard archetype's default bean scan (`classpath*:org/onehippo/**/*.class`) does **not** cover.
Add the filter in your site webapp's `WEB-INF/web.xml`:

```xml
<context-param>
  <param-name>hst-beans-annotated-classes</param-name>
  <param-value>classpath*:org/example/**/*.class
    ,classpath*:org/onehippo/**/*.class
    ,classpath*:com/onehippo/**/*.class
    ,classpath*:org/bloomreach/forge/**/*.class
  </param-value>
</context-param>
```

Omitting this is a quiet failure: the component still renders, but section fields resolve through
generic property lookups instead of typed beans.

### 3. Register the catalog REST endpoint

The Open UI field fetches its facet values over HTTP, and that endpoint needs a Spring registration
plus an HST mount in your own project. This cannot be automatic — the reason, and the exact XML and
YAML, are in [hst/README.md](hst/README.md#3-the-jax-rs-mount-for-the-facet-catalog-endpoint).

### 4. Host the Open UI extension and point the CMS at it

`extension/` builds to static assets you host yourself. Then set `frontend:url` and `catalogUrl` on
the registration node, and add the CSP allowances. Full detail, including the traps:
[extension/README.md](extension/README.md#requirements).

### 5. Choose where facets come from

By default the plugin reads every `selection:valuelist` directly inside
`/content/documents/administration/faceted-segmentation` — the folder it bootstraps itself, holding
three example facets (Day of the Week, Signed In, Size) so a fresh install has something to
demonstrate. The scan is not recursive. Point it at your own folder by overriding one Spring bean;
see [hst/README.md](hst/README.md#1-pointing-the-plugin-at-your-own-value-lists-optional).

### 6. Add the field to a document type

Either use the bundled `facetseg:PersonalizedContent` document type as-is, or add an
`OpenUiString` field to your own type and name the registration node from its editor template. Both
shapes are in [extension/README.md](extension/README.md#3-a-string-field-of-type-openuistring).

## How sections render

A document holds N sections, each with its own rule. Two independent component parameters decide
what a visitor sees when the rules are evaluated — they answer different questions, so they are set
separately.

**`sectionMatchMode`** — how many sections may win:

| Value | Behaviour |
|---|---|
| `first-match` (default) | Stop at the earliest matching section. Section order is **priority**; at most one section renders. |
| `all-matches` | Keep every matching section, in stored order. Section order is **presentation order** only. |

**`publicContentMode`** — whether the document's public content still renders:

| Value | Behaviour |
|---|---|
| `fallback` (default) | Public content is the ungated **alternative** — rendered only when nothing matched. |
| `always` | Public content is a **preamble** — rendered first, then whatever matched. |

Sections are always evaluated in stored order (`hipposysedit:ordered`), so reordering them in the
CMS changes the outcome under either mode.

The default pair (`first-match` + `fallback`) is the exclusive model: a visitor sees one
personalised block *or* the public one, never both. The opposite pair (`all-matches` + `always`) is
the additive model: public content plus every block the visitor qualifies for, which suits documents
built as a stack of independent sections. Both mixed pairs are legal too.

If the catalog endpoint is unreachable no rule can match, so public content renders regardless of
mode — a catalog outage degrades to public content rather than to an empty component.

### What the template receives

`FacetedSegmentationComponent` sets these request attributes; the bundled template renders them and
contains no mode logic of its own:

| Attribute | Type | Meaning |
|---|---|---|
| `document` | `HippoBean` | The configured document. |
| `matchedSections` | `List<HippoBean>` | Every section that matched, in order. Empty if none. |
| `matchedSection` | `HippoBean` | The first match, or null. Retained for templates written against a single section. |
| `renderPublicContent` | `Boolean` | Whether the template should also render public content. |
| `audience` | `Audience` | The resolved facet values, for debugging. |

Because the component resolves `renderPublicContent` itself, a custom template never needs to read
either parameter: loop `matchedSections`, and honour `renderPublicContent`.

## Building

```bash
mvn clean install                # the plugin artifacts, into your local repository
cd demo && mvn clean verify      # the demo, resolving them from there
cd demo && mvn -P cargo.run      # run it: CMS at :8080/cms, site at :8080/site
```

The demo is deliberately outside the root pom's `<modules>`: it consumes the published artifacts
rather than reactor-building them, which is what makes it a test of the plugin instead of a test of
the build. Run `mvn clean install` at the root **first**.

For the Open UI extension:

```bash
cd extension
npm install
npm run build                    # -> dist/, two entry points
npm test                         # 113 tests
npm run dev                      # dev server on :5173
```

## Toolchain

| Tool | Required | Verified on |
|---|---|---|
| JDK | 21 | Corretto 21.0.11 |
| Maven | 3.9.x | 3.9.15 |
| Node | `^20.19.0 \|\| ^22.12.0 \|\| >=24.0.0` (extension only) | 24.19.0 |
| npm | >=10 (extension only) | 11.17.0 |
| brXM | 17.x | 17.2.0 |

Node's range excludes odd-numbered releases; `extension/.nvmrc` pins it, and the reasoning plus the
one deliberate `engine-strict` omission is in
[extension/README.md](extension/README.md#toolchain).

## The grammar

This is the contract between the builder, the delivery-tier evaluator, and any migration tooling.
The stored JCR string is the only representation of a rule ([D1](#d1--store-the-expression-string-only-nothing-else)), so it is load-bearing.

```ebnf
expression  = or_expr ;
or_expr     = and_expr { "OR" and_expr } ;
and_expr    = unary { "AND" unary } ;
unary       = [ "NOT" ] primary ;
primary     = group | condition ;
group       = "(" expression ")" ;
condition   = facet operator "[" value_list "]" ;
operator    = "NOT IN" | "IN" ;
facet       = value list display name (may contain spaces) ;
value_list  = value { "," value } ;
value       = value list item label (may contain spaces and parentheses) ;
```

Precedence, tightest first: `NOT` > `AND` > `OR`. An empty expression (`""`) is valid and means
**ungated** — shown to everyone.

### Tokenizing

Facet names and values are human-readable strings containing spaces, and nothing is quoted, so
tokenizing is context-sensitive. Four rules make it unambiguous:

1. **Bracket contents are scanned to the closing `]` before anything else is considered.** This is
   what makes parentheses inside a label safe: `Index IN [Multi-Asset Index (MAX)]` cannot be
   confused with grouping, because the `(` is consumed by the bracket scan.
2. **A facet name runs from the current position up to the operator keyword**, read greedily until
   a standalone `IN` or `NOT IN`, then trimmed. This is why `Day of the Week` works as one token.
3. **`NOT` is disambiguated by what follows it.** `NOT IN` after a facet name is negated
   membership; `NOT` before `(` is group negation ([D5](#d5--not-may-negate-a-group-not-only-a-condition)).
4. **Values are split on `,` and trimmed.** No quoting or escaping is defined.

Keywords match only as **standalone uppercase words**, so a facet or label containing "in" — like
`Signed In` — is never mistaken for the operator.

### Constraints on your value lists

Two rules your data must satisfy, which the plugin cannot enforce for you:

- **No label may contain a comma or a square bracket.** Values are unquoted. Parentheses, `&`,
  `®`, `™`, `%`, `/` and `-` are all safe.
- **No value list display name may contain `AND`, `OR`, `NOT` or `IN` as a standalone word.** A
  facet named "Index IN Scope" breaks rule 2. Re-validate when adding value lists.

`npm run catalog:generate` in `extension/` warns about both when it reads your value lists.

One further limit: `ui.document.field.setValue()` caps a value at 100,000 characters per
Bloomreach's documentation. Not a practical concern — a wide realistic expression is a few hundred
characters — and treat the exact figure as documentation-sourced rather than verified.

### Canonical formatting

The builder emits a deterministic indented format, so that diffs and version comparison are
meaningful:

- Two spaces of indent per nesting level.
- A binary operator joining two operands starts the line of its right-hand operand.
- A group's `(` ends the line that introduces it; its `)` sits on its own line, at the indent of
  the construct that opened it.
- Values are separated by `, ` and wrapped at a soft ~64-column margin, aligned inside `[`.

```
Size IN [Medium, Large]
  AND Day of the Week NOT IN [Sunday]
```

```
Signed In IN [Signed In]
  AND (
       Size IN [Large]
    OR Day of the Week IN [Saturday, Sunday]
  )
```

## Design decisions

Referenced by number from source comments. Amend here rather than diverging in code.

#### D1 — Store the expression string only, nothing else

The JCR property holds *only* the expression. No JSON envelope, no parallel AST, no separate
display string. Everything downstream is recoverable by parsing, and one canonical representation
cannot drift from a structured twin. The cost is that the delivery tier must parse the grammar.

#### D2 — Bracketed values are value list **labels**, not keys

`Size IN [Small]`, not `Size IN [S]`. Readability is the priority, and the label is what an editor
already controls. The consequence: renaming a label in a value list leaves stored expressions
referencing a label that no longer resolves, so the builder must **surface** unresolvable values
rather than drop them — a silent drop would widen the audience.

#### D3 — Facet tokens are value list **display names**

The token before the operator is the value list's `hippo:name` (`Day of the Week`), not the node
name (`day-of-the-week`) and not the JCR property name. Consistent with D2: the value list stays
the single place display text is edited. The consequence is that facet tokens contain spaces, hence
tokenizing rule 2.

Note the asymmetry: expressions reference facets by **display name**, while the `facets`
allow-list in `frontend:config` uses **ids**. That is deliberate — config should survive a rename —
but it is an easy configuration error.

#### D4 — Arbitrary nested parentheses, not flat DNF

`A AND (B OR C)` is expressible directly, at any depth, rather than being normalized to a flat
disjunction. Normalizing would restore exactly the duplication the plugin exists to remove: the
shared condition would be restated in every disjunct.

#### D5 — `NOT` may negate a group, not only a condition

`NOT (A AND B)` is valid, alongside the `NOT IN` operator on a single condition. `NOT` over a bare
condition folds into `NOT IN`, and double negation collapses, so the stored form stays canonical.

#### D6 — The field renders the full expression and grows to fit

The inline field shows the whole rule, syntax-tinted, and calls `setHeight('auto')`. An editor
should be able to read a section's audience without opening anything.

#### D7 — Guided builder only; no raw text editing

The expression is authored through a structured builder in a dialog, never typed. Free text would
let an editor store a rule that does not parse, or one naming facets and values that do not exist.

#### D8 — The catalog is served over HTTP and cached in layers

The Open UI SDK cannot read repository content outside the open document, so the extension cannot
read value lists directly — it fetches them from an endpoint the host project exposes. Caching is
described in [extension/README.md](extension/README.md#caching).

#### D11 — Absent-facet behavior is configurable, defaulting to strict

What happens when a request carries **no value at all** for a facet a rule names:

| Policy | `F IN [x]` | `F NOT IN [x]` |
|---|---|---|
| `strict` (default) | false | **false** |
| `permissive` | false | **true** |

`IN` always fails without a value, under either policy.

The default is `strict` — fail closed — because this gates access to secured content and a
misconfigured deployment must not silently widen an audience. `permissive` is correct when absence
is **informative**: an editor writing `Size NOT IN [Small]` means "every size except Small", and a
visitor with no size recorded belongs in "every other size".

It is wrong when absence is an **artefact**. If an entitlement lookup fails, or authentication is
half-finished, the absent value tells you nothing about the visitor, and under `permissive` every
`NOT IN` rule would match — an access-control failure rather than a degraded page. So a deployment
using `permissive` should force `strict` for requests whose facet values could not be established.
This is why the policy is a per-call argument on the Java evaluator rather than a static setting.

Two further consequences:

- **The delivery tier is authoritative.** The extension's copy of this setting only drives the
  builder's preview, so the two must be configured consistently or the preview disagrees with
  production.
- **It interacts with rewriting a majority selection.** Inverting `IN [most values]` to
  `NOT IN [the rest]` is a footprint win and is audience-equivalent *while the request carries a
  value* — but under `permissive` the inverted form additionally matches visitors with no value for
  that facet. Pinned by tests in both languages.

#### D12 — One grammar, two implementations, kept in lockstep

The parser, formatter and evaluator exist in TypeScript (`extension/src/expression/`) and Java
(`common/src/main/java/.../expression/`). Two implementations are unavoidable — one runs in a
browser iframe, the other on the delivery tier — so they are held together by **mirrored test
suites**: the Java suites are deliberate ports of the TypeScript ones, in the same order with the
same cases, so the two can be diffed side by side. A rule the builder can author but the evaluator
cannot read would fail silently at request time.

#### D14 — No bundled catalog fallback

The catalog endpoint is a hard dependency. With nothing cached and the endpoint unreachable, the
field renders the stored expression, reports the failure, and disables the builder — rather than
falling back to fixture data. A fallback would let an editor build rules against facets the
repository no longer has, and would bake a stale catalog into the deployed artifact.

Duplicate facets are prevented at the picker, not rewritten on save: the builder will not let you
add a facet that is already an immediate sibling, but the same facet at a *different* nesting level
is legitimate and is left alone.

## Status

Functionally complete and passing: 89 Java tests, 113 TypeScript tests, type-check, and production
build. The demo renders end-to-end — drag the catalog item onto a page, and a `PersonalizedContent`
document's sections are gated by their expressions.

Before a first release:

- **Maven site** (`src/site/`) for publication to GitHub Pages, per the
  [Forge quality checklist](https://bloomreach-forge.github.io/checklist.html). Documentation is
  currently these READMEs, which the checklist does not accept as a substitute.
- **Run it in a browser.** The build and test suites pass, but the two new
  `sectionMatchMode` / `publicContentMode` parameters have not been exercised in a live CMS — only
  in unit tests and a template-rendering harness.
- **Version is `1.0.0-SNAPSHOT`.** Publishing to the Forge repository needs a release build, and
  only Bloomreach developers have write access there.

Known gaps, deliberate:

- The grammar has **no tuple support** — a condition compares one facet against a flat list of
  values. A paired facet (e.g. strategy + product, where the pair must match together) cannot be
  expressed.
- The shipped example value lists are small and tidy, so they do not exercise labels containing
  parentheses, `&`, `®` or `/`, nor a list of 50+ values. Tokenizing is covered directly by
  `expression.test.ts`; a project whose own value lists contain such labels should validate them
  with `npm run catalog:generate`, which warns on anything unsafe.

## License

Apache License 2.0.
