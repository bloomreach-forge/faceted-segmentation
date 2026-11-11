# Sourcing Audience Data

The component needs the visitor's facet values to evaluate rules. By default it reads them from the
request's query parameters. That's simple and handy for the demo or headless implementation, 
but some sites get these values from somewhere else: a login, an identity service, a CDP, or another entitlements service.

To use your own source, subclass the component and override `resolveAudience`. 
Nothing else needs to be changed.

## The resolveAudience method

```java
protected Audience resolveAudience(HstRequest request, ValueListCatalogReader.Facets facets)
```

It returns an `Audience`: the visitor's values for each facet. The component calls it once per
render, on live requests only.

An `Audience` uses the names stored rules use, not the ids your source sends. The `facets`
argument translates them:

| | Your source sends | The `Audience` needs | Translate with |
|---|---|---|---|
| Facet | Node name, `day-of-the-week` | Display name, `Day of the Week` | `facets.displayNameForId(id)` |
| Value | Key, `monday` | Label, `Monday` | `facets.labelForKey(id, key)` |

Both return `null` for anything the CMS doesn't know. Drop those.

A facet you leave out of the `Audience` is a visitor with no value for it. What rules then do
depends on `absentFacetPolicy`. See [absentFacetPolicy](rendering.html#absentFacetPolicy).

## Preview is handled for you

`resolveAudience` isn't called in preview, where the component uses `previewModeQueryParams`
instead. Your subclass needs no preview code.

## An example

`visitorFacets` stands for your own lookup. It returns the visitor's keys, keyed by value list node
name:

```java
public class LoginSegmentationComponent extends FacetedSegmentationComponent {

    @Override
    protected Audience resolveAudience(HstRequest request, ValueListCatalogReader.Facets facets) {
        Audience.Builder builder = Audience.builder();
        visitorFacets(request).forEach((id, keys) -> {
            String facet = facets.displayNameForId(id);
            List<String> labels = keys.stream()
                    .map(key -> facets.labelForKey(id, key))
                    .filter(Objects::nonNull)
                    .toList();
            if (facet != null && !labels.isEmpty()) {
                builder.putAll(facet, labels);
            }
        });
        return builder.build();
    }
}
```

Register it as a catalog item with `hst:template: faceted-segmentation`, as in step 5 of
[Using Your Own Document Type](own-document-type.html). Components already on pages keep the class
they were added with, so replace them or update their `hst:componentclassname`.

## Look values up once per request

`resolveAudience` runs once for each component on the page. If it calls a remote service, store
the result on the request context, which every component shares. `request.setAttribute` won't work:
an `HstRequest`'s attributes belong to one component.

```java
HstRequestContext context = request.getRequestContext();
Audience audience = (Audience) context.getAttribute("myproject.audience");
if (audience == null) {
    audience = lookUpAudience(request, facets);
    context.setAttribute("myproject.audience", audience);
}
return audience;
```

## Protecting against audience source failures

If your source fails, don't return an empty `Audience`. That means "this visitor has no values",
and some rules match such visitors: every `NOT IN` under `permissive`, and a negated group such as
`NOT (Size IN [Small] AND Day of the Week IN [Monday])` under either policy. An outage could then
show restricted content to everyone.

Throw an exception instead. The component then renders nothing, and the error is logged.

## Caching

Query parameters are part of the URL, so URL-keyed caches keep visitors apart. Values from a
session, cookie or header aren't. If a CDN or page cache sits in front of the site, keep segmented
pages out of it, or add the values to the cache key.
