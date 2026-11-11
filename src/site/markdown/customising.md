# Customising

The plugin works as installed. This page covers where facets come from, and how they're cached.

## Your value lists

Each facet is a value list. The plugin reads every value list directly inside
`/content/documents/administration/faceted-segmentation`. Subfolders aren't read, so a folder of
its own is how you choose which value lists become facets.

The plugin creates that folder with three starter value lists: Day of the Week, Signed In and Size.
They're bootstrapped as content, once:

- They're loaded the first time the repository starts with the plugin installed. On an existing
  repository, that start needs `repo.bootstrap=true`, which installing the plugin needs anyway.
- After that they're your content. Editors can change or delete them, and upgrading the plugin
  never overwrites them. A deleted value list isn't recreated.
- The plugin also ships the `administration` folder itself. If your project ships one too,
  whichever loads first is kept and the other is skipped. Both sets of value lists still load.

To use only your own facets, either replace the starter value lists in the CMS, or read another
folder as below and delete the plugin's folder.

To use another folder, add a file such as `value-lists.xml` to your site components module, in
`src/main/resources/META-INF/hst-assembly/overrides/addon/org/bloomreach/forge/facetedsegmentation/`.
Any `.xml` file in that folder is loaded:

```xml
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd">

  <bean id="facetedSegmentationValueListsPath" class="java.lang.String">
    <constructor-arg type="java.lang.String" value="/content/documents/myproject/facets"/>
  </bean>

</beans>
```

This changes the folder for both the builder and the site. Don't set it on the
`FacetCatalogResource` bean instead: that gives the builder a separate cache, and the site stops
seeing resyncs.

| From the value list | Used in | Example |
|---|---|---|
| Node name | Query parameters, preview values, `frontend:config` | `day-of-the-week` |
| Display name | Stored rules | `Day of the Week` |
| Item key | Query parameters, preview values | `monday` |
| Item label | Stored rules | `Monday` |

Renaming a display name or label breaks the rules that use it. Names and labels that can't be
written in a rule are logged as warnings. See [Expression Grammar](grammar.html).

If the folder is missing, the site logs an error and shows public content, and the builder shows an
error.

## Caching and clusters

The site reads the folder at most every 300 seconds. To change that, add this bean to the same
file:

```xml
<bean id="facetedSegmentationCacheTtlSeconds" class="java.lang.Long">
  <constructor-arg type="java.lang.String" value="60"/>
</bean>
```

The builder also caches facets in the editor's browser, for `cacheTtlSeconds` in `frontend:config`.
**Resync facet values** in the builder skips both caches, so the change shows at once.

In a cluster, each node has its own cache. After a value list changes, each node picks it up within
the cache interval, and a resync only refreshes the node it reaches. If changes must reach every
node quickly, lower the interval.
