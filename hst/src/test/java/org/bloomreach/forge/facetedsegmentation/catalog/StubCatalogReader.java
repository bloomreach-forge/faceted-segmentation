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

import java.util.List;

import org.hippoecm.hst.core.request.HstRequestContext;

/**
 * A reader that serves fixed facets, or fails, without touching a repository. Lives in this package
 * because {@link ValueListCatalogReader.Facets} has a package-private constructor.
 */
public final class StubCatalogReader extends ValueListCatalogReader {

    private final List<FacetCatalog.Facet> facets;

    private StubCatalogReader(final List<FacetCatalog.Facet> facets) {
        super("/stub", 0L);
        this.facets = facets;
    }

    /** Serves {@code facets} on every read. */
    public static StubCatalogReader serving(final List<FacetCatalog.Facet> facets) {
        return new StubCatalogReader(List.copyOf(facets));
    }

    /** Throws on every read, as an unreachable repository would. */
    public static StubCatalogReader failing() {
        return new StubCatalogReader(null);
    }

    @Override
    public Facets read(final HstRequestContext context, final boolean refresh) {
        if (facets == null) {
            throw new IllegalStateException("catalog unavailable");
        }
        return new Facets(facets, System.currentTimeMillis());
    }
}
