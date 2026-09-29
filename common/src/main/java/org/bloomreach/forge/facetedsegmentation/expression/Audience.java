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
 * Java port of extension/src/expression/evaluate.ts's Audience type.
 *
 * TypeScript models this as Record<string, string[] | string | undefined> — a facet maps to
 * either a single value or a list of values, and an absent key means the request carries no
 * value at all for that facet. Java has no natural string-or-list union, so this wraps a
 * Map<String, List<String>> and normalizes single values to a one-element list at construction,
 * collapsing the TS union down to one shape immediately rather than carrying it through the
 * evaluator.
 */
package org.bloomreach.forge.facetedsegmentation.expression;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The segmentation values a request carries, keyed by facet display name. A facet absent from
 * this map (or mapped to an empty/null collection) means the request carries no value for it.
 */
public final class Audience {

    private static final Audience EMPTY = new Audience(Map.of());

    private final Map<String, List<String>> values;

    private Audience(final Map<String, List<String>> values) {
        this.values = values;
    }

    public static Audience empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The held values for {@code facet}, or an empty list if the request carries none. */
    public List<String> get(final String facet) {
        final List<String> held = values.get(facet);
        return held == null ? List.of() : held;
    }

    public boolean isAbsent(final String facet) {
        return get(facet).isEmpty();
    }

    public static final class Builder {

        private final Map<String, List<String>> values = new LinkedHashMap<>();

        /** Sets a single value for {@code facet}. Overwrites any previous value(s). */
        public Builder put(final String facet, final String value) {
            final List<String> list = new ArrayList<>(1);
            if (value != null) {
                list.add(value);
            }
            values.put(facet, list);
            return this;
        }

        /** Sets multiple values for {@code facet}. Overwrites any previous value(s). */
        public Builder putAll(final String facet, final Collection<String> facetValues) {
            values.put(facet, facetValues == null ? List.of() : List.copyOf(facetValues));
            return this;
        }

        public Audience build() {
            return new Audience(Map.copyOf(values));
        }
    }
}
