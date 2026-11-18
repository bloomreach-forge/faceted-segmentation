# Release Notes

## Compatibility

| Plugin version | Bloomreach Experience Manager version |
|---|---|
| 2.0.x | 17.x.x |
| 1.0.x | 17.x.x |

## 2.0.0

The field now ships with the CMS.

- A new artifact, `bloomreach-facetedsegmentation-frontend`, carries the field and its rule
  builder. Add it to the CMS, next to `bloomreach-facetedsegmentation-repository`. The CMS serves
  it at `/cms/angular/facetedsegmentation/`, so there's nothing to host separately.
- The field's `frontend:url` is now the relative `angular/facetedsegmentation/index.html`, the same
  in every environment. The field needs no Content Security Policy entries.
- Built against and tested on brXM 17.4.1.

Upgrading from 1.0:

1. Add the `frontend` dependency to `cms-dependencies/pom.xml`. See
   [Installation](install.html#a2._Add_the_dependencies).
2. Remove your own `frontend:url` override and the `frame-src` and `connect-src` entries you added
   for the field. 1.0's documentation suggested `${...}` in `frontend:url`. That never worked: the
   CMS resolves `${...}` in `frontend:config` only.
3. Keep `catalogUrl`. If the site is on another origin than the CMS, add
   `Access-Control-Allow-Methods: GET, OPTIONS` and `Access-Control-Allow-Headers: If-None-Match` to
   the catalog mount's `hst:responseheaders`. See
   [Installation](install.html#a5._Register_the_facet_catalog_endpoint).
4. Stop hosting the 1.0 field files.

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
