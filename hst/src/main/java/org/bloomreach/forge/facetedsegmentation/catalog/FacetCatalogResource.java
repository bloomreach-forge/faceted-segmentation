/*
 * Copyright 2026 Bloomreach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bloomreach.forge.facetedsegmentation.catalog;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.hippoecm.hst.container.RequestContextProvider;
import org.hippoecm.hst.core.request.HstRequestContext;
import org.hippoecm.hst.jaxrs.services.AbstractResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the facet catalog to the faceted segmentation Open UI field.
 *
 * <h2>Why this exists</h2>
 *
 * The Open UI SDK cannot read value lists. {@code ui.document.field.getValue(...path)} traverses only
 * within the currently-open document — there is no node read and no query API. So the authoring field
 * cannot obtain its facet values from the CMS at all, and has to fetch them over HTTP from something
 * that CAN read the repository. That is this class.
 *
 * <p>It is a <strong>hard dependency</strong>, deliberately: the field ships no bundled fallback
 * catalog. If this endpoint is unreachable the field renders the stored expression, reports which
 * failure mode occurred, and disables the builder — rather than letting an editor author rules
 * against stale fixture data the repository may no longer agree with.
 *
 * <h2>Contract</h2>
 *
 * {@code GET <mount>/facets} returns {@code {etag, generatedAt, facets[]}}. It supports
 * {@code If-None-Match} &rarr; {@code 304} and {@code ?refresh=true} to bypass the cache (which is
 * what the field's "Resync facet values" button calls).
 *
 * <p>The value lists themselves are read by {@link ValueListCatalogReader}, shared with the delivery
 * tier so both halves see the same data through the same cache.
 */
@Path("/facets")
public class FacetCatalogResource extends AbstractResource {

    private static final Logger log = LoggerFactory.getLogger(FacetCatalogResource.class);

    private static final String DEFAULT_VALUE_LISTS_PATH = "/content/documents/administration/faceted-segmentation";
    private static final long DEFAULT_TTL_SECONDS = 900L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<CachedCatalog> cache = new AtomicReference<>();

    private ValueListCatalogReader reader =
            new ValueListCatalogReader(DEFAULT_VALUE_LISTS_PATH, DEFAULT_TTL_SECONDS);

    /**
     * Injected so the endpoint and the delivery-tier component share one reader, and therefore one
     * cache and one configured path. When unset, a default reader is used.
     */
    public void setReader(final ValueListCatalogReader reader) {
        this.reader = reader;
    }

    /** Convenience for a deployment that configures the resource directly rather than the reader. */
    public void setValueListsPath(final String valueListsPath) {
        reader.setValueListsPath(valueListsPath);
    }

    public void setTtlSeconds(final long ttlSeconds) {
        reader.setTtlSeconds(ttlSeconds);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFacets(@QueryParam("refresh") final boolean refresh,
                             @HeaderParam("If-None-Match") final String ifNoneMatch) {

        final HstRequestContext context = RequestContextProvider.get();
        if (context == null) {
            log.error("No HstRequestContext — the facet catalog resource is not being served through "
                    + "the HST pipeline. Check the mount's hst:namedpipeline is JaxrsRestPlainPipeline.");
            return serverError("The facet catalog is not available: no HST request context.");
        }

        try {
            final CachedCatalog cached = refresh ? null : validCachedCatalog();
            final CachedCatalog catalog = cached != null ? cached : readAndCache(context, refresh);

            // Revalidation: a warm client transfers nothing. Compared tolerantly because the client
            // echoes back whatever form it stored.
            if (CatalogEtag.matches(ifNoneMatch, catalog.etag)) {
                return Response.notModified()
                        .header("ETag", quote(catalog.etag))
                        .header("Cache-Control", cacheControl())
                        .build();
            }

            return Response.ok(catalog.json, MediaType.APPLICATION_JSON)
                    .header("ETag", quote(catalog.etag))
                    .header("Cache-Control", cacheControl())
                    .header("X-Catalog-Cache", cached != null ? "hit" : (refresh ? "bypass" : "miss"))
                    .build();

        } catch (final Exception e) {
            // Deliberately broad: whatever goes wrong reading the repository, the field needs a
            // response it can report rather than a dropped connection, which it cannot distinguish
            // from a CSP or CORS failure.
            log.error("Could not build the facet catalog from '{}'.", reader.getValueListsPath(), e);
            return serverError("Could not read the facet catalog: " + e.getMessage());
        }
    }

    // --- catalog construction ------------------------------------------------------------------

    private CachedCatalog validCachedCatalog() {
        final CachedCatalog cached = cache.get();
        if (cached == null) {
            return null;
        }
        final long ageSeconds = (System.currentTimeMillis() - cached.fetchedAtMillis) / 1000L;
        return ageSeconds < reader.getTtlSeconds() ? cached : null;
    }

    private CachedCatalog readAndCache(final HstRequestContext context, final boolean refresh)
            throws Exception {

        final List<FacetCatalog.Facet> facets = reader.read(context, refresh).all();

        final FacetCatalog catalog = new FacetCatalog();
        catalog.setFacets(facets);
        // Order matters: the ETag covers facets only, so it must be set before the
        // payload is serialized but is itself excluded from the hash.
        catalog.setEtag(CatalogEtag.compute(mapper, facets));
        catalog.setGeneratedAt(Instant.now().toString());

        final String json = mapper.writeValueAsString(catalog);
        final CachedCatalog fresh = new CachedCatalog(json, catalog.getEtag(), System.currentTimeMillis());
        cache.set(fresh);

        int valueCount = 0;
        for (final FacetCatalog.Facet facet : facets) {
            valueCount += facet.getValues().size();
        }
        log.info("Facet catalog {}: {} facet(s), {} value(s), etag {}.",
                refresh ? "re-read (cache bypassed)" : "read", facets.size(), valueCount,
                catalog.getEtag());

        return fresh;
    }

    // --- helpers -------------------------------------------------------------------------------

    /** RFC 9110 requires the entity-tag to be quoted. */
    private static String quote(final String etag) {
        return "\"" + etag + "\"";
    }

    private String cacheControl() {
        return "max-age=" + reader.getTtlSeconds();
    }

    private static Response serverError(final String message) {
        return Response.serverError()
                .type(MediaType.APPLICATION_JSON)
                .entity("{\"error\":" + jsonString(message) + "}")
                .build();
    }

    private static String jsonString(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ") + "\"";
    }

    /** An immutable snapshot: the serialized payload, its ETag, and when it was built. */
    private static final class CachedCatalog {

        private final String json;
        private final String etag;
        private final long fetchedAtMillis;

        CachedCatalog(final String json, final String etag, final long fetchedAtMillis) {
            this.json = json;
            this.etag = etag;
            this.fetchedAtMillis = fetchedAtMillis;
        }
    }
}
