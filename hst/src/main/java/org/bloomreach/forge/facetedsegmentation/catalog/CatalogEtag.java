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
 * Computes the catalog ETag.
 *
 * This has to agree EXACTLY with extension/scripts/generate-catalog.mjs, which does:
 *
 *     `sha256-${sha256(JSON.stringify({ facets })).hex.slice(0, 32)}`
 *
 * Three properties of that are deliberate and must be preserved here:
 *
 *   1. The hash covers ONLY `facets` — not `generatedAt`, and not `etag` itself.
 *      That makes the ETag stable across reads when the repository content has not changed, which
 *      is the whole point: a warm client gets a 304 rather than an identical payload.
 *   2. Key order matters, because it hashes serialized JSON rather than a canonical form. Jackson
 *      emits in @JsonPropertyOrder order, which is set on the DTOs to match the generator's literal
 *      object order.
 *   3. It is truncated to 32 hex characters.
 *
 * If the demo's value lists are unchanged, GET /api/facets and
 * extension/test/fixtures/facets.json will therefore carry the same etag — which is the cheapest
 * end-to-end check that this endpoint is contract-correct.
 */
package org.bloomreach.forge.facetedsegmentation.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

final class CatalogEtag {

    private static final int HEX_LENGTH = 32;

    private CatalogEtag() {
    }

    /** The subset of the payload the hash covers, in the generator's key order. */
    @JsonPropertyOrder({"facets"})
    private static final class Hashed {

        private final List<FacetCatalog.Facet> facets;

        Hashed(final List<FacetCatalog.Facet> facets) {
            this.facets = facets;
        }

        public List<FacetCatalog.Facet> getFacets() {
            return facets;
        }
    }

    static String compute(final ObjectMapper mapper, final List<FacetCatalog.Facet> facets) {
        try {
            final String json = mapper.writeValueAsString(new Hashed(facets));
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));

            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (final byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return "sha256-" + hex.substring(0, HEX_LENGTH);
        } catch (final JsonProcessingException | NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS and the DTOs are plain beans, so neither is reachable.
            throw new IllegalStateException("Could not compute the facet catalog ETag.", e);
        }
    }

    /**
     * Compares a client's If-None-Match against our ETag, tolerantly.
     *
     * We emit the quoted form (RFC 9110), but the extension's client stores whatever it received and
     * echoes it back verbatim — and an older build of the reference server emitted it unquoted. A
     * strict comparison would therefore silently never match, and every warm client would keep
     * downloading the full payload. Accepts the bare value, the quoted value, a weak validator, and
     * a comma-separated list.
     */
    static boolean matches(final String ifNoneMatch, final String etag) {
        if (ifNoneMatch == null || etag == null) {
            return false;
        }
        for (final String candidate : ifNoneMatch.split(",")) {
            String value = candidate.trim();
            if ("*".equals(value)) {
                return true;
            }
            if (value.startsWith("W/")) {
                value = value.substring(2).trim();
            }
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (etag.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
