# Demo Project

The source repository includes a demo project, a standard brXM project with the plugin installed.
Every [Installation](install.html) step is already done in it, so it's also a working reference
when you install the plugin in your own project.

The demo's homepage has four Faceted Segmentation components. Each one shows a different document
or a different combination of settings. You change what they show by adding query parameters to
the URL.

## Run it

You need JDK 21, Maven 3.9+, and Node 20.19 or later (or 22.12 or later). Clone the
[repository](https://github.com/bloomreach-forge/faceted-segmentation), then run the plugin build
first. The demo uses the plugin from your local Maven repository, so it must be built first:

```bash
mvn clean install
```

Start the field's dev server in one terminal:

```bash
cd extension
npm ci
npm run dev
```

The demo loads the field from `http://localhost:5173`.

Build and start the demo in a second terminal:

```bash
cd demo
mvn clean verify
mvn -P cargo.run
```

When it has started:

| What | Where |
|---|---|
| CMS | `http://localhost:8080/cms`, log in as `admin` / `admin` |
| Site | `http://localhost:8080/site` |
| Facet catalog | `http://localhost:8080/site/api/facets` |

## The facets

The demo uses the three value lists the plugin ships. Each is in
**Content › Administration › Faceted Segmentation**.

| Facet | Query parameter | Values (key: label) |
|---|---|---|
| Day of the Week | `day-of-the-week` | `sunday`: Sunday, `monday`: Monday … `saturday`: Saturday |
| Signed In | `signedin` | `in`: Signed In, `out`: Signed Out |
| Size | `size` | `S`: Small, `M`: Medium, `L`: Large |

The query parameter is the value list's node name, and its value is an item's key. Keys aren't
case-sensitive. To give a facet more than one value, separate them with commas (`?size=S,M`) or
repeat the parameter (`?size=S&size=M`).

The demo reads facet values only from the query string. Nothing comes from the real date or a real
login, so `?day-of-the-week=monday` is Monday whatever day it is. In your own project, facet values
can come from anywhere. See [Sourcing Audience Data](audience-sources.html).

All four components use the `strict` absent facet policy. A condition on a facet the URL doesn't
give a value for is false, even a `NOT IN` condition. That's why a page with no query parameters
shows only public content.

## Things to try in the CMS

1. **Edit a rule.** Open one of the documents in **Content › Personalized Content**, open a
  section's segmentation field, and change its rule. See [Building a Rule](building-a-rule.html).
2. **Reorder sections.** Move section 4 of Personalized Content 1 to the top, and publish. Under
  `first-match`, `?day-of-the-week=monday` now shows "Visits on a non-Thursday".
3. **Preview.** Open the homepage in the Experience manager. Preview can't take query parameters,
  so each component gets its values from its **Preview mode query parameters** setting instead.
  The first component is set to `day-of-the-week=friday&signedin=in` and the fourth to
  `day-of-the-week=monday&signedin=in`. The other two use the default, which is also
  `day-of-the-week=monday&signedin=in`. See [Previewing Segments](previewing.html).
4. **Change the settings.** Select a component in the Experience manager and change its match mode
  or public content mode. See [Rendering Sections](rendering.html).
5. **Add a facet value.** Add an item to one of the value lists, then click **Resync facet values**
  in the field. The new value appears in the builder.
