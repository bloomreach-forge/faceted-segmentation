# Release Notes

## Compatibility

| Plugin version | Bloomreach Experience Manager version |
|---|---|
| 1.0.x | 17.2.x |

## 1.0.0

First release.

- A document field where editors build a segmentation rule with AND, OR and NOT, grouped to any
  depth. The rule is stored as readable text.
- Facets come from ordinary value lists in one repository folder. Three starter value lists are included.
- A component that shows the sections of a document whose rules match the visitor. It can show the
  first match or every match, and public content as a fallback or always.
- A choice of how rules treat facets the visitor has no value for: strict or permissive.
- Facet values set on the component for preview, where authors can't add query parameters.
- A hook for reading facet values from your own source, such as a login or an entitlements service.
