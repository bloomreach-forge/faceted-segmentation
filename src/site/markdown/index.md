# Faceted Segmentation

Faceted Segmentation enables editors to personalize sections of content to complex visitor conditions. 
Editors write one readable rule per section. 
The site processes incoming website visitor requests and shows only the sections that match.

The facet definitions come from your own customer segmentation system.

An editor builds this in a single field:

```
Signed In IN [Signed In]
  AND (
    Loyalty IN [Gold Member, Platinum Member]
      OR Day of the Week IN [Saturday, Sunday]
  )
```

In plain English, that reads: "Show this section to signed-in visitors who are either gold or platinum members, or who visit on a weekend."
The site then checks the rule against each visitor and only shows this section if they match.

[![The segmentation field in the document editor](images/field-on-document.png)](images/field-on-document.png)

## Why

The Relevance module personalizes at the component level. In the Experience manager, an editor
copies a component into a variant for each audience and gives each variant a segment. That works
well for swapping whole components, but some needs fall outside it:

- **Personalizing part of a document.** A variant replaces the whole component. To change one
  paragraph of an article for one audience, editors copy the whole document for the variant, then
  keep the copies in sync.
- **Grouped conditions.** A segment joins its conditions with AND and OR, but can't group them.
  "Signed in, and either a gold member or visiting on a weekend" has to repeat "signed in" in
  each OR branch.

Faceted Segmentation puts a rule on each section of a document and allows conditions to be grouped to any depth.

## What's included

| Part | What it does |
|---|---|
| `bloomreach-facetedsegmentation-common` | Reads and evaluates rules. Has no brXM dependency. |
| `bloomreach-facetedsegmentation-hst` | The site side: the component that picks sections, the content beans, and the REST endpoint the field gets its facets from. |
| `bloomreach-facetedsegmentation-repository` | The CMS side: the document type, the field registration, and example value lists. |
| Open UI extension | The field and rule builder editors use. Must be hosted separately. |

Facets are ordinary value lists, so editors manage them where they already manage value lists.
The plugin has no dependency on the Relevance module. 

## Next steps

- [Installation](install.html) add the plugin to your project.
- [Demo Project](demo.html) run a working example locally.

The source and its design notes are on
[GitHub](https://github.com/bloomreach-forge/faceted-segmentation).
