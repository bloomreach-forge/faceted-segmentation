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
 * Mirrors extension/src/expression/catalog-integration.test.ts.
 *
 * Reads the SAME fixture the TypeScript integration test reads — generated from the plugin's own
 * bootstrapped Value List YAML in `repository/` by extension/scripts/generate-catalog.mjs
 * (3 facets, 12 values), then copied here:
 *
 *   cp extension/test/fixtures/facets.json \
 *      common/src/test/resources/org/bloomreach/forge/facetedsegmentation/expression/facets.json
 *
 * The point of duplicating the assertions in Java is D12: one grammar, two implementations, and
 * the delivery tier is the one that actually gates content. A label the TypeScript builder can
 * author but the Java parser cannot read would produce a rule that silently fails at request time.
 */
package org.bloomreach.forge.facetedsegmentation.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Operator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ShippedCatalogIntegrationTest {

    private static final Pattern GRAMMAR_KEYWORD = Pattern.compile("\\b(AND|OR|NOT|IN)\\b");

    private static JsonNode catalog;

    private record LabeledValue(String facetName, String label) {
        @Override
        public String toString() {
            return facetName + ": " + label;
        }
    }

    @BeforeAll
    static void loadFixture() throws IOException {
        final ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = ShippedCatalogIntegrationTest.class.getResourceAsStream("facets.json")) {
            if (in == null) {
                throw new IllegalStateException(
                        "facets.json not found on the test classpath — see the copy instructions "
                                + "in this test's class comment.");
            }
            catalog = mapper.readTree(in);
        }
    }

    private static List<LabeledValue> everyLabel() {
        final List<LabeledValue> all = new ArrayList<>();
        for (final JsonNode facet : catalog.get("facets")) {
            final String name = facet.get("name").asText();
            for (final JsonNode value : facet.get("values")) {
                all.add(new LabeledValue(name, value.get("label").asText()));
            }
        }
        return all;
    }

    static Stream<LabeledValue> everyLabelProvider() {
        return everyLabel().stream();
    }

    private static JsonNode facetById(final String id) {
        for (final JsonNode facet : catalog.get("facets")) {
            if (id.equals(facet.get("id").asText())) {
                return facet;
            }
        }
        throw new IllegalStateException("no facet with id " + id + " in the fixture");
    }

    private static List<String> labelsOf(final String facetId) {
        final List<String> labels = new ArrayList<>();
        facetById(facetId).get("values").forEach(v -> labels.add(v.get("label").asText()));
        return labels;
    }

    @Nested
    @DisplayName("shipped catalog shape")
    class ShippedCatalogShape {

        @Test
        void hasTheFacetsTheRepositoryModuleBootstraps() {
            assertEquals(3, catalog.get("facets").size());
            final List<String> names = new ArrayList<>();
            catalog.get("facets").forEach(f -> names.add(f.get("name").asText()));
            assertEquals(List.of("Day of the Week", "Signed In", "Size"), names);
        }

        @Test
        void carriesTheFullValueSets() {
            assertEquals(12, everyLabel().size());
            assertEquals(7, labelsOf("day-of-the-week").size());
            assertEquals(2, labelsOf("signedin").size());
            assertEquals(3, labelsOf("size").size());
        }

        @Test
        @DisplayName("stores labels distinct from keys, so labels are what expressions carry")
        void storesLabelsDistinctFromKeys() {
            // D2: every shipped value has key != label, so a test passing a key where a label
            // belongs fails rather than accidentally agreeing.
            for (final JsonNode facet : catalog.get("facets")) {
                for (final JsonNode value : facet.get("values")) {
                    assertNotEquals(value.get("key").asText(), value.get("label").asText());
                }
            }
            assertEquals(List.of("Small", "Medium", "Large"), labelsOf("size"));
        }
    }

    @Nested
    @DisplayName("grammar safety across every shipped label")
    class GrammarSafety {

        @Test
        void noLabelContainsACommaOrBracket() {
            final List<LabeledValue> unsafe = everyLabel().stream()
                    .filter(v -> v.label().contains(",") || v.label().contains("[") || v.label().contains("]"))
                    .toList();
            assertEquals(List.of(), unsafe);
        }

        @Test
        void noFacetDisplayNameCollidesWithAGrammarKeyword() {
            final List<String> colliding = new ArrayList<>();
            catalog.get("facets").forEach(f -> {
                final String name = f.get("name").asText();
                if (GRAMMAR_KEYWORD.matcher(name).find()) {
                    colliding.add(name);
                }
            });
            assertEquals(List.of(), colliding);
        }

        @Test
        @DisplayName("reads a multi-word facet name greedily, up to the operator keyword")
        void readsAMultiWordFacetNameGreedily() {
            final ExpressionNode node = ExpressionParser.parse("Day of the Week NOT IN [Saturday, Sunday]");
            assertEquals(
                    Expressions.condition("Day of the Week", Operator.NOT_IN, List.of("Saturday", "Sunday")),
                    node);
        }

        @Test
        @DisplayName("tolerates a label identical to its own facet name")
        void toleratesALabelIdenticalToItsFacetName() {
            // The "Signed In" value list has a label identical to its display name, whose words
            // include the IN keyword — neither may confuse tokenizing, because bracket contents
            // are consumed whole.
            final ExpressionNode node = ExpressionParser.parse("Signed In IN [Signed In]");
            assertEquals(
                    Expressions.condition("Signed In", Operator.IN, List.of("Signed In")),
                    node);
            assertEquals("Signed In IN [Signed In]", ExpressionFormatter.format(node));
            assertEquals(node, ExpressionParser.parse(ExpressionFormatter.format(node)));
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("everyLabelProvider")
    @DisplayName("parses a single-value condition for every shipped label")
    void parsesASingleValueConditionForEveryShippedLabel(final LabeledValue lv) {
        final String src = lv.facetName() + " IN [" + lv.label() + "]";
        final ExpressionNode node = ExpressionParser.parse(src);
        assertEquals(
                Expressions.condition(lv.facetName(), Operator.IN, List.of(lv.label())),
                node);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("everyLabelProvider")
    @DisplayName("round-trips every shipped label through format -> parse")
    void roundTripsEveryShippedLabel(final LabeledValue lv) {
        final String src = lv.facetName() + " IN [" + lv.label() + "]";
        final ExpressionNode node = ExpressionParser.parse(src);
        final String formatted = ExpressionFormatter.format(node);
        assertEquals(node, ExpressionParser.parse(formatted));
    }

    @Test
    @DisplayName("each facet's full-selection expression round-trips")
    void eachFacetsFullSelectionExpressionRoundTrips() {
        for (final JsonNode facet : catalog.get("facets")) {
            final String name = facet.get("name").asText();
            final List<String> labels = new ArrayList<>();
            facet.get("values").forEach(v -> labels.add(v.get("label").asText()));

            final ExpressionNode node = Expressions.condition(name, Operator.IN, labels);
            final String formatted = ExpressionFormatter.format(node);
            assertEquals(node, ExpressionParser.parse(formatted),
                    "facet '" + name + "' with all " + labels.size() + " values failed to round-trip");
        }
    }

    @Test
    @DisplayName("evaluates true for a visitor holding any one shipped value per facet")
    void evaluatesTrueForAVisitorHoldingAnyOneShippedValue() {
        for (final JsonNode facet : catalog.get("facets")) {
            final String name = facet.get("name").asText();
            final String label = facet.get("values").get(0).get("label").asText();

            final ExpressionNode node = ExpressionParser.parse(name + " IN [" + label + "]");
            final Audience audience = Audience.builder().put(name, label).build();
            assertTrue(ExpressionEvaluator.evaluate(node, audience),
                    "facet '" + name + "' failed to evaluate true for its own held value");
        }
    }

    @Nested
    @DisplayName("inverting a majority selection (D11 interaction)")
    class MajorityInversion {

        private final ExpressionNode asIn = Expressions.condition("Day of the Week", Operator.IN,
                List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday"));
        private final ExpressionNode asNotIn = Expressions.condition("Day of the Week", Operator.NOT_IN,
                List.of("Saturday", "Sunday"));

        @Test
        @DisplayName("agrees with the IN form for every real value, under both policies")
        void agreesWithTheInFormForEveryRealValue() {
            for (final AbsentFacetPolicy policy : AbsentFacetPolicy.values()) {
                final EvaluateOptions options = EvaluateOptions.builder().absentFacetPolicy(policy).build();
                for (final String label : labelsOf("day-of-the-week")) {
                    final Audience audience = Audience.builder().put("Day of the Week", label).build();
                    assertEquals(
                            ExpressionEvaluator.evaluate(asIn, audience, options),
                            ExpressionEvaluator.evaluate(asNotIn, audience, options),
                            label + " @ " + policy);
                }
            }
        }

        @Test
        void isMateriallyShorter() {
            assertTrue(ExpressionFormatter.format(asNotIn).length()
                    < ExpressionFormatter.format(asIn).length());
        }

        @Test
        @DisplayName("is only equivalent under STRICT when the facet is absent")
        void isOnlyEquivalentUnderStrictWhenTheFacetIsAbsent() {
            final Audience empty = Audience.empty();

            final EvaluateOptions strict =
                    EvaluateOptions.builder().absentFacetPolicy(AbsentFacetPolicy.STRICT).build();
            assertFalse(ExpressionEvaluator.evaluate(asIn, empty, strict));
            assertFalse(ExpressionEvaluator.evaluate(asNotIn, empty, strict));

            final EvaluateOptions permissive =
                    EvaluateOptions.builder().absentFacetPolicy(AbsentFacetPolicy.PERMISSIVE).build();
            assertFalse(ExpressionEvaluator.evaluate(asIn, empty, permissive));
            assertTrue(ExpressionEvaluator.evaluate(asNotIn, empty, permissive));
        }
    }
}
