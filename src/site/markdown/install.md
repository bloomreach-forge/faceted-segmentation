# Installation

Installing the plugin takes eight steps. Some are one-off Maven and web.xml edits. The rest are
configuration that changes per environment.

To see every step working in a real project, look at the [Demo Project](demo.html). 

## 1. Add the Forge repository

The Maven artifacts are on the Bloomreach Forge repository. If your project's root `pom.xml`
doesn't list it yet, add it:

```xml
<repositories>
  <repository>
    <id>bloomreach-maven2-forge</id>
    <name>Bloomreach Forge Maven 2 repository</name>
    <url>https://maven.bloomreach.com/repository/maven2-forge/</url>
  </repository>
</repositories>
```

## 2. Add the dependencies

Add a version property and manage both artifacts in the root `pom.xml`:

```xml
<properties>
  <facetedsegmentation.version>1.0.0</facetedsegmentation.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.bloomreach.forge.facetedsegmentation</groupId>
      <artifactId>bloomreach-facetedsegmentation-repository</artifactId>
      <version>${facetedsegmentation.version}</version>
    </dependency>
    <dependency>
      <groupId>org.bloomreach.forge.facetedsegmentation</groupId>
      <artifactId>bloomreach-facetedsegmentation-hst</artifactId>
      <version>${facetedsegmentation.version}</version>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Then add each one to the module where it runs:

| Artifact | Module | What it brings |
|---|---|---|
| `bloomreach-facetedsegmentation-repository` | `cms-dependencies/pom.xml` | The document type and the field registration. |
| `bloomreach-facetedsegmentation-hst` | `site/components/pom.xml` | The component, the content beans and the facet catalog endpoint. |

```xml
<dependency>
  <groupId>org.bloomreach.forge.facetedsegmentation</groupId>
  <artifactId>bloomreach-facetedsegmentation-repository</artifactId>
</dependency>
```

```xml
<dependency>
  <groupId>org.bloomreach.forge.facetedsegmentation</groupId>
  <artifactId>bloomreach-facetedsegmentation-hst</artifactId>
</dependency>
```

## 3. Let HST find the content beans

The plugin's content beans are in `org.bloomreach.forge.facetedsegmentation.beans`. The
archetype's default bean scan doesn't cover that package. Add `org/bloomreach/forge` to
`hst-beans-annotated-classes` in `site/webapp/src/main/webapp/WEB-INF/web.xml`:

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

`org/example` stands for your project's own bean package. Keep the entries your `web.xml` already
has, and add only the `org/bloomreach/forge` line. 

If you
[use your own document type](own-document-type.html), its bean must be in one of the scanned
packages too. A bean that Essentials generated already is.

We recommend this step. If you skip it, whether the content renders depends on HST's
[dynamic content beans](https://docs.content.bloomreach.com/build/content-beans-translations/dynamic-content-beans):

- **Dynamic beans on** (the default). HST generates beans for the plugin's document types at
  runtime, with the same getters, so the page renders the same.
- **Dynamic beans off** (`-Ddynamic.bean.generation=false`). HST has no bean with those getters.
  Rules are still evaluated, but the template can't read `public` or a section's `content`, so
  they render blank.

With the scan in place, HST uses the plugin's beans either way.

## 4. Order your configuration after the plugin's

The plugin bootstraps its configuration in the `bloomreach-forge` group. Your project's
configuration must apply after it, so the document type and the field registration exist before
your content and overrides use them. In your `repository-data/application` module's
`hcm-module.yaml`:

```yaml
group:
  name: myproject
  after: [hippo-cms, bloomreach-forge]
```

## 5. Register the facet catalog endpoint

The field gets its facets from a REST endpoint on the site. It needs a Spring registration and an
HST mount in your project. The plugin can't register these itself: HST loads the plugin into its
own Spring context, and the REST pipeline can't see beans defined there.

**The Spring registration.** Spring files for REST resources go in
`site/components/src/main/resources/META-INF/hst-assembly/overrides/`. HST loads every `.xml` file
there, whatever its name. Projects usually call this one `custom-jaxrs-resources.xml`.

If your project has no such file yet, create `custom-jaxrs-resources.xml` with this content:

```xml
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd">

  <import resource="classpath:/org/hippoecm/hst/site/optional/jaxrs/SpringComponentManager-rest-jackson.xml"/>
  <import resource="classpath:/org/hippoecm/hst/site/optional/jaxrs/SpringComponentManager-rest-plain-pipeline.xml"/>

  <bean id="org.example.rest.FacetCatalogResource"
        class="org.bloomreach.forge.facetedsegmentation.catalog.FacetCatalogResource"/>

  <bean id="customRestPlainResourceProviders" class="org.springframework.beans.factory.config.ListFactoryBean">
    <property name="sourceList">
      <list>
        <bean class="org.apache.cxf.jaxrs.lifecycle.SingletonResourceProvider">
          <constructor-arg ref="org.example.rest.FacetCatalogResource"/>
        </bean>
      </list>
    </property>
  </bean>

</beans>
```

If your project already has the file, because it serves REST resources of its own, edit the existing file instead.

Don't set any properties on the `FacetCatalogResource` bean. Left as it is, it shares a cache with
the component, so when an editor clicks **Resync facet values** in the field, the site sees the new
values too.

**Your value lists.** The plugin reads its facets from the value lists in
`/content/documents/administration/faceted-segmentation`. It creates that folder with three starter value lists:
Day of the Week, Signed In and Size. Edit them, add your own, or read value lists from another
folder instead. See [Customising](customising.html).

**The mount.** Add an `api` mount under your site's root mount in
`repository-data/site/src/main/resources/hcm-config/hst/hosts.yaml`:

```yaml
/api:
  jcr:primaryType: hst:mount
  hst:alias: facetcatalog
  hst:namedpipeline: JaxrsRestPlainPipeline
  hst:ismapped: false
  hst:nochannelinfo: true
  hst:authenticated: false
  hst:types: [rest]
  hst:responseheaders: ['Access-Control-Allow-Origin: *', 'Access-Control-Expose-Headers: ETag']
```

Add it under every host group that needs it: local, staging and production.

With the demo's hosts, the endpoint is `http://localhost:8080/site/api/facets`.

## 6. Host the field

The field and its rule builder are an Open UI extension: static HTML, JavaScript and CSS that the
CMS loads in an iframe. It isn't a Maven artifact, so you host it yourself. 

Get the files in one of two ways:

- Download `faceted-segmentation-extension-1.0.0.zip` from the
  [GitHub release](https://github.com/bloomreach-forge/faceted-segmentation/releases/tag/1.0.0).
- Build them from source. You need Node 20.19 or later (or 22.12 or later).

  ```bash
  cd extension
  npm ci
  npm run build
  ```

  The files are written to `extension/dist/`.

Serve the files from the **root** of their origin, for example `https://extensions.example.com/`.
They load their scripts from `/assets/`, so they don't work from a subpath.

## 7. Point the field at your hosts

The plugin registers the field at
`/hippo:configuration/hippo:frontend/cms/ui-extensions/facetedSegmentation`. Two of its properties
depend on your environment:

| Property | What to set it to |
|---|---|
| `frontend:url` | The field's `index.html`, from step 6. |
| `catalogUrl`, inside `frontend:config` | The full URL of the endpoint from step 5. |

`catalogUrl` must be an absolute URL. A relative one resolves against `frontend:url`, not the CMS.

The defaults suit local development: the extension's dev server at `http://localhost:5173` and the
catalog at `http://localhost:8080/site/api/facets`. Override both in your own repository
configuration:

```yaml
definitions:
  config:
    /hippo:configuration/hippo:frontend/cms/ui-extensions/facetedSegmentation:
      frontend:url: ${facetedSegmentation.extensionUrl}
      frontend:config: '{"catalogUrl":"${facetedSegmentation.catalogUrl}","cacheTtlSeconds":300,"dialogSize":"large","absentFacetPolicy":"strict"}'
```

The CMS fills in `${...}` from its container configuration, so you can set the values per
environment in `$CATALINA_BASE/conf/platform.properties` without a rebuild:

```properties
facetedSegmentation.extensionUrl=https://extensions.example.com/index.html
facetedSegmentation.catalogUrl=https://www.example.com/site/api/facets
```

`frontend:config` takes these options:

| Option | Default | What it does |
|---|---|---|
| `catalogUrl` | | Where the field fetches facets. If it's wrong, the field shows an error and disables the builder. |
| `cacheTtlSeconds` | `300` | How long the browser reuses a fetched catalog. |
| `dialogSize` | `large` | The builder dialog's size: `small`, `medium` or `large`. |
| `absentFacetPolicy` | `strict` | How the builder's preview treats facets the visitor has no value for. It must match the component's own `absentFacetPolicy`, or the preview won't agree with the site. See [Rendering Sections](rendering.html#absentFacetPolicy). |
| `facets` | all | A list of value list node names, such as `size`, to offer. Leave it out to offer every facet. |

## 8. Allow the field's origins in the Content Security Policy

From brXM 15, the CMS blocks iframes and requests to other origins unless its Content Security
Policy lists them. Add two entries in your repository configuration:

| Directive | Origin |
|---|---|
| `frame-src` | Where the field is hosted (`frontend:url`). |
| `connect-src` | Where the catalog is served (`catalogUrl`). |

```yaml
definitions:
  config:
    /hippo:configuration/hippo:modules/application-settings/hippo:moduleconfig/content-security-policy:
      frame-src:
        operation: add
        value: ['extensions.example.com']
      connect-src:
        operation: add
        value: ['www.example.com']
```

## Check that it works

1. Start the project and log in to the CMS.
2. Create a document of the plugin's type, `facetseg:PersonalizedContent`. Add a section, and open the segmentation
   field's builder. The facet picker should list the value lists in your facets folder.
3. In the Experience manager, drag **Faceted Segmentation** from the component catalog onto a page
   and pick your document.

## Next steps

- [Building a Rule](building-a-rule.html): how editors use the field.
- [Rendering Sections](rendering.html): the component's parameters.
- [Using Your Own Document Type](own-document-type.html): segmented sections in a document type
  of your own, instead of `PersonalizedContent`.
- [Customising](customising.html): your own value lists, and running in a cluster.
