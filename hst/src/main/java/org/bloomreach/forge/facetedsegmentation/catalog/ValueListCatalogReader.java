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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.hippoecm.hst.content.beans.standard.HippoFolderBean;
import org.hippoecm.hst.core.request.HstRequestContext;
import org.onehippo.forge.selection.hst.contentbean.ValueList;
import org.onehippo.forge.selection.hst.contentbean.ValueListItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads facet definitions from brXM value lists, with a TTL cache.
 *
 * Delivers to:
 * {@link FacetCatalogResource} — serves the catalog over REST to the OpenUI field.
 * The delivery-tier component — needs the id -> name and key -> label maps to turn a request into an {@code Audience}.
 */
public class ValueListCatalogReader {

    private static final Logger log = LoggerFactory.getLogger(ValueListCatalogReader.class);

    /**
     * WARNING:
     * A facet display name containing a grammar keyword as a standalone word will break the parser's
     * greedy "read up to the operator" facet-name scan.
     */
    private static final Pattern GRAMMAR_KEYWORD = Pattern.compile("\\b(AND|OR|NOT|IN)\\b");

    private final AtomicReference<Facets> cache = new AtomicReference<>();

    private String valueListsPath;
    private long ttlSeconds;

    public ValueListCatalogReader(final String valueListsPath, final long ttlSeconds) {
        this.valueListsPath = valueListsPath;
        this.ttlSeconds = ttlSeconds;
    }

    public void setValueListsPath(final String valueListsPath) {
        this.valueListsPath = valueListsPath;
    }

    public void setTtlSeconds(final long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public String getValueListsPath() {
        return valueListsPath;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    /**
     * The facets, from cache when fresh.
     *
     * @param refresh bypass the cache and re-read the repository
     */
    public Facets read(final HstRequestContext context, final boolean refresh) {
        if (!refresh) {
            final Facets cached = cache.get();
            if (cached != null && cached.ageSeconds() < ttlSeconds) {
                return cached;
            }
        }
        final Facets fresh = new Facets(readFacets(context), System.currentTimeMillis());
        cache.set(fresh);
        log.info("Read {} facet(s) from '{}'{}.", fresh.all().size(), valueListsPath,
                refresh ? " (cache bypassed)" : "");
        return fresh;
    }

    /** Whether a cached copy is currently being served — for the endpoint's diagnostic header. */
    public boolean isFresh() {
        final Facets cached = cache.get();
        return cached != null && cached.ageSeconds() < ttlSeconds;
    }

    private List<FacetCatalog.Facet> readFacets(final HstRequestContext context) {
        final Object bean;
        try {
            bean = context.getObjectBeanManager().getObject(valueListsPath);
        } catch (final Exception e) {
            throw new IllegalStateException("Could not read '" + valueListsPath + "': " + e.getMessage(), e);
        }

        if (!(bean instanceof HippoFolderBean)) {
            throw new IllegalStateException(bean == null
                    ? "No folder found at '" + valueListsPath + "'. Check the path and that the "
                      + "folder is published."
                    : "Expected a folder at '" + valueListsPath + "' but found "
                      + bean.getClass().getName() + ".");
        }

        final HippoFolderBean folder = (HippoFolderBean) bean;
        // Non-recursive by design: it reads the value lists directly in this folder, so a deployment
        // can scope the plugin to a dedicated subfolder rather than exposing every value list a
        // project happens to own.
        final List<ValueList> valueLists = folder.getDocuments(ValueList.class);

        if (valueLists.isEmpty()) {
            // An unpublished value list is simply not readable through the live session, so a missing
            // list looks like absence rather than an error. Say so, since "zero facets" is otherwise
            // indistinguishable from a wrong path.
            log.warn("No selection:valuelist documents found under '{}'. If value lists exist there, "
                    + "check they are PUBLISHED — the live session filters on hippo:availability.",
                    valueListsPath);
        }

        final List<FacetCatalog.Facet> facets = new ArrayList<>(valueLists.size());
        for (final ValueList valueList : valueLists) {
            final String id = valueList.getName();
            final String name = valueList.getDisplayName();

            final List<ValueListItem> items = valueList.getItems();
            if (items == null || items.isEmpty()) {
                log.warn("Value list '{}' has no selection:listitem entries — skipped.", id);
                continue;
            }

            final List<FacetCatalog.FacetValue> values = new ArrayList<>(items.size());
            for (final ValueListItem item : items) {
                final String key = item.getKey();
                final String label = item.getLabel();
                if (key == null && label == null) {
                    continue;
                }
                values.add(new FacetCatalog.FacetValue(key, label));
            }

            if (values.isEmpty()) {
                log.warn("Value list '{}' yielded no usable key/label pairs — skipped.", id);
                continue;
            }

            warnIfGrammarUnsafe(id, name, values);
            facets.add(new FacetCatalog.Facet(id, name, valueList.getCanonicalHandlePath(), values));
        }

        return facets;
    }

    /**
     * Warns about content that would break the expression grammar. Deliberately does NOT fail:
     * the catalog is still usable for every other facet, and an editor cannot fix a value list from
     * the document editor. These are the two ways new repository content can silently invalidate
     * stored expressions.
     */
    private void warnIfGrammarUnsafe(final String id, final String name,
                                     final List<FacetCatalog.FacetValue> values) {
        if (name != null && GRAMMAR_KEYWORD.matcher(name).find()) {
            log.warn("Value list '{}' has display name '{}', which contains a grammar keyword "
                    + "(AND/OR/NOT/IN) as a standalone word. Expressions using this facet will not "
                    + "parse — rename the value list.", id, name);
        }
        for (final FacetCatalog.FacetValue value : values) {
            final String label = value.getLabel();
            if (label != null && (label.indexOf(',') >= 0 || label.indexOf('[') >= 0 || label.indexOf(']') >= 0)) {
                log.warn("Value list '{}' has label '{}', which contains a comma or square bracket. "
                        + "Expression values are unquoted, so this value cannot be represented.",
                        id, label);
            }
        }
    }

    /**
     * An immutable snapshot of the facets, plus the lookups a delivery-tier caller needs.
     */
    public static final class Facets {

        private final List<FacetCatalog.Facet> facets;
        private final long readAtMillis;
        private final Map<String, FacetCatalog.Facet> byId;

        Facets(final List<FacetCatalog.Facet> facets, final long readAtMillis) {
            this.facets = List.copyOf(facets);
            this.readAtMillis = readAtMillis;
            final Map<String, FacetCatalog.Facet> index = new LinkedHashMap<>();
            for (final FacetCatalog.Facet facet : facets) {
                index.put(facet.getId(), facet);
            }
            this.byId = Map.copyOf(index);
        }

        public List<FacetCatalog.Facet> all() {
            return facets;
        }

        /** Keyed by facet id (the value list's node name) — what a request carries. */
        public Map<String, FacetCatalog.Facet> byId() {
            return byId;
        }

        /**
         * The display name a stored expression uses for this facet id, or {@code null} if no such
         * facet exists — in which case a caller should ignore it rather than guess.
         */
        public String displayNameForId(final String facetId) {
            final FacetCatalog.Facet facet = byId.get(facetId);
            return facet == null ? null : facet.getName();
        }

        /**
         * The label a stored expression uses for this value, or {@code null} if the facet has no
         * item with this {@code selection:key}.
         *
         * <p>The comparison is case-insensitive: a request key's casing is not guaranteed to match
         * the catalog's stored {@code selection:key} casing (e.g. a URL built by hand), so matching
         * exactly would silently drop otherwise-valid values.
         */
        public String labelForKey(final String facetId, final String key) {
            final FacetCatalog.Facet facet = byId.get(facetId);
            if (facet == null || key == null) {
                return null;
            }
            for (final FacetCatalog.FacetValue value : facet.getValues()) {
                if (key.equalsIgnoreCase(value.getKey())) {
                    return value.getLabel();
                }
            }
            return null;
        }

        long ageSeconds() {
            return (System.currentTimeMillis() - readAtMillis) / 1000L;
        }
    }
}
