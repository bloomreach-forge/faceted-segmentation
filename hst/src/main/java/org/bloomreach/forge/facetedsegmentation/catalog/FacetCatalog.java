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
/*
 * Payload DTOs for the facet catalog endpoint.
 *
 * These mirror the contract in extension/src/catalog/types.ts EXACTLY, because the
 * Open UI extension's test fixture (extension/test/fixtures/facets.json) and its test suite are
 * built against that shape. The fixture is generated from the same Value List YAML by
 * extension/scripts/generate-catalog.mjs, so a live response and the fixture should be identical
 * apart from `generatedAt`.
 *
 * @JsonPropertyOrder is load-bearing, not cosmetic: the ETag is a hash of the serialized JSON and
 * has to agree with the generator's `JSON.stringify`, which emits keys in literal order. Reordering
 * a field here changes every ETag and silently breaks 304 revalidation for warm clients.
 *
 * Tuples (formerly "pair groups") are not supported: facets whose values must be combined as
 * fixed pairs are managed manually as tuples in the Facet Builder rather than declared here.
 */
package org.bloomreach.forge.facetedsegmentation.catalog;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"etag", "generatedAt", "facets"})
public class FacetCatalog {

    private String etag;
    private String generatedAt;
    private List<Facet> facets = new ArrayList<>();

    public String getEtag() {
        return etag;
    }

    public void setEtag(final String etag) {
        this.etag = etag;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(final String generatedAt) {
        this.generatedAt = generatedAt;
    }

    public List<Facet> getFacets() {
        return facets;
    }

    public void setFacets(final List<Facet> facets) {
        this.facets = facets;
    }

    /**
     * One Value List.
     * `id` is the document node name (the slug, e.g. "license-state");
     * `name` is the handle's hippo:name (e.g. "Day of the Week"),
     * which is the facet token that appears in stored expressions.
     */
    @JsonPropertyOrder({"id", "name", "path", "values"})
    public static class Facet {

        private String id;
        private String name;
        private String path;
        private List<FacetValue> values = new ArrayList<>();

        public Facet() {
        }

        public Facet(final String id, final String name, final String path, final List<FacetValue> values) {
            this.id = id;
            this.name = name;
            this.path = path;
            this.values = values;
        }

        public String getId() {
            return id;
        }

        public void setId(final String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public String getPath() {
            return path;
        }

        public void setPath(final String path) {
            this.path = path;
        }

        public List<FacetValue> getValues() {
            return values;
        }

        public void setValues(final List<FacetValue> values) {
            this.values = values;
        }
    }

    /**
     * One list item within a Value List.
     */
    @JsonPropertyOrder({"key", "label"})
    public static class FacetValue {

        private String key;
        private String label;

        public FacetValue() {
        }

        public FacetValue(final String key, final String label) {
            this.key = key;
            this.label = label;
        }

        public String getKey() {
            return key;
        }

        public void setKey(final String key) {
            this.key = key;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(final String label) {
            this.label = label;
        }
    }
}
