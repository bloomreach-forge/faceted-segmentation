# bloomreach-facetedsegmentation-hst — installation

What's automatic once this jar is on the site webapp's classpath, and what a consuming project has
to wire up itself. For dependency coordinates and the wider install sequence, see the
[root README](../README.md#installing-into-a-project).

## Automatic

- `FacetedSegmentationComponent` (the delivery-tier component), `FacetedSegmentationInfo` (its
  Channel Manager parameters), the shared `ValueListCatalogReader`, and its Spring wiring — via
  `META-INF/hst-assembly/addon/...` (HST addon module scanning). Nothing to register.
- `PersonalizedContent` / `FacetedSegmentationSection` (`org.bloomreach.forge.facetedsegmentation.beans`)
  — typed HST content beans for `facetseg:PersonalizedContent` / `facetseg:FacetedSegmentationSection`.
  Not required by `FacetedSegmentationComponent` itself (it reads sections via the generic
  `HippoBean` API), but used by this jar's own bundled template (next bullet) instead of falling
  back to generic property lookups. **These need one line of project configuration** — see
  [step 0](#0-let-hst-find-the-content-beans) below.
- The `hst:catalog` entry and `hst:templates` registration for the generic (non-subclassed)
  component, via `hcm-config/hst/configurations/default/{catalog,templates}.yaml`. These bootstrap
  into `/hst:hst/hst:configurations/hst:default`, which the HST engine treats as reserved — any
  configuration literally named `hst:default` is implicitly inherited by every other
  configuration, no `hst:inheritsfrom` needed. Unlike the `common` node described below, this node
  is not created by the consuming project, so there's no cross-group ordering problem: this
  module's `hcm-module.yaml` joins `group: hippo-cms` and orders itself `after:
  hippo-site-toolkit` — the same pattern other onehippo/Bloomreach forge plugins that bootstrap
  HST configuration use (e.g. `hippo-plugin-sitemap-hcm-site`).
- The template itself (`freemarker/facetedsegmentation/facetedsegmentation.ftl`), rendered via a
  `classpath:` render path (`hst:renderpath: classpath:/freemarker/facetedsegmentation/facetedsegmentation.ftl`).
  `HstFreemarkerServlet` wires an `HstClassTemplateLoader` alongside its `jcr:`/`webfile:` loaders,
  which resolves a `classpath:` path via plain `Class.getResource(...)` — no JCR import/bootstrap
  step at all, unlike `webfile:`, which depends on a webfile *bundle* actually being imported into
  `/webfiles/<bundle>` and is a better fit for a project owning one dedicated bundle than for a
  library module shipping a single template alongside Java classes. It renders against the two
  bean classes above and is self-contained (it inlines its own `hst`/`fmt` taglib assigns rather
  than depending on a project's `include/imports.ftl`, whose relative path is an archetype
  convention this plugin can't assume).
- The catalog icon (`images/facetedsegmentationplugin/facetedsegmentation.svg`), shipped under
  `META-INF/resources/...` in this jar. `hst:iconpath` is a plain site-webapp-relative static
  resource path — no `webfile:` scheme support like `hst:renderpath` has — so it can't go through
  the webfile bundle; `META-INF/resources` is the Servlet 3.0+ convention (the same one webjars
  use) for a jar to contribute static resources to a webapp with zero project wiring.

Drag the catalog item onto a page and it renders end-to-end out of the box now — nothing above
requires a project-side install step.

## Not automatic — the bean scan, and anything you want to customise

One thing tried and abandoned before landing on `hst:default`, worth recording so nobody retries
it:

- **Bootstrapping the component's `hst:catalog`/`hst:templates` registration into the project's
  shared `hst:configurations/common` node** (the archetype's fixed, hardcoded name for it, verified
  across every 13.x–17.x archetype version) failed with `IllegalStateException: ... contains
  definition rooted at unreachable node '.../hst:configurations/common/...'`. `common` is created
  by the *project's own* `repository-data/site` module, under the project's own HCM group (named
  after its `rootArtifactId`, which this plugin cannot know). This plugin's `bloomreach-forge`
  group and a project's group are *siblings* — both declare `after: hippo-cms`, but nothing orders
  them relative to each other — so whether `common` exists yet when this module applies is
  undefined, and in practice it does not always exist yet. Declaring `after: bloomreach-forge` in
  the project's own group doesn't fix this either: it only orders that one project module relative
  to the plugin, not the reverse, and does nothing for the specific project module that creates
  `common` if that module doesn't declare it too.
- **Registering the JAX-RS mount under a bootstrapped host group** doesn't have `hst:default`'s
  fix available, for a related but distinct reason: `hst:hosts/dev-localhost` is a hardcoded
  archetype default too, but unlike `common`, a host group's name is explicitly meant to change
  per deployment — real projects rename or add host groups for staging, production, and every
  other domain. There's no name a plugin could bootstrap into that would still exist in a live
  project.

### 0. Let HST find the content beans

The standard archetype's default `hst-beans-annotated-classes` scan covers
`classpath*:org/onehippo/**/*.class`, which does **not** match this plugin's
`org.bloomreach.forge.facetedsegmentation.beans` package. Add the filter in your site webapp's
`WEB-INF/web.xml`:

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

This is a quiet failure if omitted: the component still renders, because it falls back to the
generic `HippoBean` API, but the bundled template's typed accessors resolve through generic property
lookups instead. See
[`../demo/site/webapp/src/main/webapp/WEB-INF/web.xml`](../demo/site/webapp/src/main/webapp/WEB-INF/web.xml).

### 1. Pointing the plugin at your own value lists (optional)

By default the plugin reads every `selection:valuelist` **directly inside**
`/content/documents/administration/faceted-segmentation` — the folder its `repository` module
bootstraps, holding three examples (Day of the Week, Signed In, Size) so a fresh install has
something to demonstrate. The scan is **not recursive**, so pointing it at a dedicated subfolder is
how you scope which of your value lists become facets.

To change it, drop a file at
`META-INF/hst-assembly/overrides/addon/org/bloomreach/forge/facetedsegmentation/value-lists.xml`
in your site components module, redefining the path bean:

```xml
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd">

  <bean id="facetedSegmentationValueListsPath" class="java.lang.String">
    <constructor-arg type="java.lang.String" value="/content/documents/myproject/facets"/>
  </bean>

</beans>
```

`facetedSegmentationCacheTtlSeconds` (default 900) can be overridden the same way.

### 2. Narrowing the document picker (optional)

If you want to restrict the document picker to your own document type, subclass
`FacetedSegmentationInfo` (see its class javadoc) and register your own catalog entry pointing
`hst:componentclassname` at your subclass, in your own
`hcm-config/hst/configurations/<project>/catalog.yaml` — see
[`../demo/repository-data/site/src/main/resources/hcm-config/hst/configurations/myproject/catalog.yaml`](../demo/repository-data/site/src/main/resources/hcm-config/hst/configurations/myproject/catalog.yaml)
for a worked example. Otherwise the plugin's own catalog entry (installed automatically, above)
is ready to use as-is.

If you do subclass, copy this jar's `freemarker/facetedsegmentation/facetedsegmentation.ftl` into
your own project (any classpath location, or a webfiles module if you prefer), adjust the bean
types / `manageContent` document-type query / `rootPath` to your own document type's content
structure, and register your own template pointing at the copy (via a `classpath:` or `webfile:`
render path) in your own `faceted-segmentation.yaml` — the bundled template's defaults are for the
generic, non-subclassed document type only.

### 3. The JAX-RS mount for the facet catalog endpoint

The facet catalog REST resource (`FacetCatalogResource`, serving `GET /facets`) needs a Spring
registration and a mount. **Why this can't be automatic:** HST loads each addon module into its
own *child* Spring context. The plain JAX-RS pipeline's `customRestPlainResourceProviders` list
lives in the *root* context, which cannot see beans defined in this module's addon context — see
the comment on `FacetCatalogResource`'s bean definition in
`META-INF/hst-assembly/addon/org/bloomreach/forge/facetedsegmentation/faceted-segmentation.xml`.

**Spring registration** — add to your site module, e.g.
`META-INF/hst-assembly/overrides/custom-jaxrs-resources.xml`:

```xml
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd">

  <import resource="classpath:/org/hippoecm/hst/site/optional/jaxrs/SpringComponentManager-rest-jackson.xml"/>
  <import resource="classpath:/org/hippoecm/hst/site/optional/jaxrs/SpringComponentManager-rest-plain-pipeline.xml"/>

  <bean id="your.project.FacetCatalogResource"
        class="org.bloomreach.forge.facetedsegmentation.catalog.FacetCatalogResource"/>

  <bean id="customRestPlainResourceProviders" class="org.springframework.beans.factory.config.ListFactoryBean">
    <property name="sourceList">
      <list>
        <bean class="org.apache.cxf.jaxrs.lifecycle.SingletonResourceProvider">
          <constructor-arg ref="your.project.FacetCatalogResource"/>
        </bean>
      </list>
    </property>
  </bean>

</beans>
```

The `<import>`s are required: the JAX-RS pipelines are optional and their Spring definitions live
outside the auto-scanned `META-INF/hst-assembly` tree, so nothing loads them unless you do it here.

**The mount** — add to your own `hcm-config/hst/hosts.yaml`, under every virtual host group/domain
that needs it (local dev, staging, production — not just one):

```yaml
definitions:
  config:
    /hst:hst/hst:hosts/dev-localhost/localhost/hst:root:
      /api:
        jcr:primaryType: hst:mount
        hst:alias: facetcatalog
        hst:namedpipeline: JaxrsRestPlainPipeline
        hst:ismapped: false
        hst:nochannelinfo: true
        hst:authenticated: false
        hst:types: [rest]
        # CORS — the Open UI extension fetches this cross-origin. Narrow beyond `*` for production.
        hst:responseheaders: ['Access-Control-Allow-Origin: *', 'Access-Control-Expose-Headers: ETag']
```

`Access-Control-Expose-Headers: ETag` is not optional if you want `304` revalidation to work: without
it the browser hides the `ETag` header from the client, which then can never send `If-None-Match`.

### 4. Point the Open UI field at the mount

`catalogUrl` in the field's `frontend:config` (on its `frontend:uiExtension` node) needs an
absolute URL to wherever step 3's mount resolves — e.g.
`http://localhost:8080/site/api/facets`. A relative path resolves against wherever the Open UI
extension itself is hosted (its `frontend:url`), not against the CMS, so it only works when both
are same-origin.

See [`../demo`](../demo) for a complete worked example, including the subclassed component/catalog
pattern from step 2.
