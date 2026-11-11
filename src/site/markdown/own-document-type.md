# Using Your Own Document Type

The plugin comes with a document type, `facetseg:PersonalizedContent`: public content followed by
a list of segmented sections. If that's all you need, use it as it is. The
[Installation](install.html) page covers everything.

To segment content in a document type of your own, such as an article, add the plugin's section
compound, `facetseg:FacetedSegmentationSection`, to your type. The compound brings the
segmentation field with it, so your type needs no field configuration of its own, and upgrading
the plugin upgrades the field.

The component, its document picker and its template are all written for `PersonalizedContent`. That means you must
subclass and copy them. That takes five steps. The examples below use a `myproject:article`
type with its sections stored as `myproject:sections`. Swap in your own names.

Do the [Installation](install.html) steps first. You still need all of them, including the bean
scan in step 3: the section bean comes from the plugin.

## 1. Add the sections field to your document type

In your namespace's YAML, for example
`repository-data/application/src/main/resources/hcm-config/namespaces/myproject/article.yaml`,
add a field under `hipposysedit:nodetype`:

```yaml
/sections:
  jcr:primaryType: hipposysedit:field
  hipposysedit:mandatory: false
  hipposysedit:multiple: true
  hipposysedit:ordered: true
  hipposysedit:path: myproject:sections
  hipposysedit:primary: false
  hipposysedit:type: facetseg:FacetedSegmentationSection
```

Then add its editor under `editor:templates/_default_`:

```yaml
/sections:
  jcr:primaryType: frontend:plugin
  caption: Segmented Sections
  field: sections
  plugin.class: org.hippoecm.frontend.editor.plugins.field.NodeFieldPlugin
  wicket.id: ${cluster.id}.field
  /cluster.options:
    jcr:primaryType: frontend:pluginconfig
```

`multiple: true` lets authors add as many sections as they like. `ordered: true` lets them reorder
the sections, which matters: when the component shows only the first match, the order decides
which section wins.

Document types created with Essentials extend `hippostd:relaxed`, which accepts any child node, so
your `.cnd` file needs no change. If your type doesn't extend `hippostd:relaxed`, declare the child
node in your `.cnd` file:

```
<'facetseg'='http://www.bloomreach.org/forge/facetedsegmentation/nt/1.0'>

[myproject:article] > myproject:basedocument
  + myproject:sections (facetseg:FacetedSegmentationSection) multiple
```

## 2. Subclass the component's parameters

The component's **Document** parameter only offers `facetseg:PersonalizedContent` documents. To
offer yours, extend `FacetedSegmentationInfo` and override `getDocument()` with your type in
`pickerSelectableNodeTypes`:

```java
package org.example.components;

import org.bloomreach.forge.facetedsegmentation.component.FacetedSegmentationInfo;
import org.hippoecm.hst.core.parameters.JcrPath;
import org.hippoecm.hst.core.parameters.Parameter;

public interface ArticleSegmentationInfo extends FacetedSegmentationInfo {

    @Override
    @Parameter(name = "document", required = true, displayName = "Document")
    @JcrPath(isRelative = true,
            pickerSelectableNodeTypes = "myproject:article",
            pickerConfiguration = "cms-pickers/documents-only")
    String getDocument();
}
```

## 3. Subclass the component

Point the component at your parameters, and tell it where your sections are:

```java
package org.example.components;

import org.bloomreach.forge.facetedsegmentation.component.FacetedSegmentationComponent;
import org.hippoecm.hst.core.parameters.ParametersInfo;

@ParametersInfo(type = ArticleSegmentationInfo.class)
public class ArticleSegmentationComponent extends FacetedSegmentationComponent {

    @Override
    protected String getSectionNodeName() {
        return "myproject:sections";
    }
}
```

**Don't skip `getSectionNodeName()`.** The component looks for sections by node name, and the
default is `facetseg:FacetedSegmentationSection`. If the names don't match, the component finds no
sections. Nothing fails and nothing is logged: the page shows only the public content.

## 4. Write your template

If your site renders with FreeMarker templates, you also need your own copy of the plugin's template.

The plugin's template renders a `PersonalizedContent`. Copy it from the `hst` jar,
`freemarker/facetedsegmentation/facetedsegmentation.ftl`, into your own project, and change three
things:

| What | In the plugin's template | Change it to |
|---|---|---|
| Public content | `document.public` | Your type's own field, or remove it. |
| The "new document" query | `new-PersonalizedContent-document` | Your type's query, for example `new-article-document`. |
| The `@ftlvariable` hint for `document` | `...beans.PersonalizedContent` | Your type's bean. |

Leave the section loop as it is. `matchedSections` holds the plugin's own section beans, so
`section.content` works for your type too.

The component's parameters work the same for your type. See
[Rendering Sections](rendering.html).

## 5. Register the component

Add a catalog item and a template in your HST configuration, for example in
`repository-data/site/src/main/resources/hcm-config/hst/configurations/myproject/catalog.yaml`:

```yaml
definitions:
  config:
    /hst:hst/hst:configurations/myproject/hst:catalog/myproject-catalog/article-segmentation:
      jcr:primaryType: hst:containeritemcomponent
      hst:componentclassname: org.example.components.ArticleSegmentationComponent
      hst:template: article-segmentation
      hst:iconpath: images/facetedsegmentationplugin/facetedsegmentation.svg
      hst:label: Segmented Article
    /hst:hst/hst:configurations/myproject/hst:templates/article-segmentation:
      jcr:primaryType: hst:template
      hst:renderpath: webfile:/freemarker/myproject/article-segmentation.ftl
```

Use the `hst:renderpath` that matches where you put the template: `webfile:` for a webfiles
bundle, or `classpath:` for a template inside your site jar.

The plugin's own **Faceted Segmentation** item stays in the catalog alongside yours. It still works
for `PersonalizedContent` documents.

## Check that it works

1. Rebuild and restart, then create an article. It should have a **Segmented Sections** field.
   Add two sections and give each a rule.
2. In the Experience manager, drag **Segmented Article** onto a page. The **Document** picker
   should offer your article.
3. Open the page with query parameters that match one of the rules. The matching section should
   show. If only the public content shows, check `getSectionNodeName()` against your field's
   `hipposysedit:path`.
