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
 * Mirrors extension/src/expression/expression.test.ts's "parse:" describe blocks. Kept in the
 * same order and covering the same cases, so a reviewer can diff the two suites side by side and
 * see that the Java parser has not drifted from the TypeScript one (D12 in the root README).
 *
 * Facet names and values are the ones the plugin itself ships (Day of the Week, Signed In, Size);
 * tests about tree shape rather than vocabulary use abstract `A IN [1]` placeholders.
 */
package org.bloomreach.forge.facetedsegmentation.expression;

import static org.bloomreach.forge.facetedsegmentation.expression.Expressions.and;
import static org.bloomreach.forge.facetedsegmentation.expression.Expressions.condition;
import static org.bloomreach.forge.facetedsegmentation.expression.Expressions.not;
import static org.bloomreach.forge.facetedsegmentation.expression.Expressions.or;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Binary;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.LogicalOperator;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Not;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Operator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExpressionParserTest {

    /** Round-trip: parse -> format -> parse must reach a fixed point. */
    private static String roundTrip(final String src) {
        final String once = ExpressionFormatter.format(ExpressionParser.parse(src));
        final String twice = ExpressionFormatter.format(ExpressionParser.parse(once));
        assertEquals(once, twice, "formatting must be idempotent");
        return once;
    }

    @Nested
    @DisplayName("parse: conditions")
    class Conditions {

        @Test
        void parsesASingleCondition() {
            assertEquals(
                    condition("Size", Operator.IN, List.of("Small", "Medium")),
                    ExpressionParser.parse("Size IN [Small, Medium]"));
        }

        @Test
        void parsesMultiWordFacetNames() {
            assertEquals(
                    condition("Day of the Week", Operator.IN, List.of("Monday")),
                    ExpressionParser.parse("Day of the Week IN [Monday]"));
        }

        @Test
        void parsesNotIn() {
            assertEquals(
                    condition("Size", Operator.NOT_IN, List.of("Small")),
                    ExpressionParser.parse("Size NOT IN [Small]"));
        }

        @Test
        void treatsAnEmptyStringAsUngated() {
            assertNull(ExpressionParser.parse(""));
            assertNull(ExpressionParser.parse("   \n  "));
            assertNull(ExpressionParser.parse(null));
        }
    }

    @Nested
    @DisplayName("parse: tokenizing hazards")
    class TokenizingHazards {

        @Test
        @DisplayName("matches keywords only as standalone uppercase words")
        void matchesKeywordsOnlyAsStandaloneUppercaseWords() {
            // "Signed In" contains "In", and is both a facet name and one of its own labels. It
            // must never be read as the IN keyword: the scan requires word boundaries AND uppercase.
            assertEquals(
                    condition("Signed In", Operator.IN, List.of("Signed In")),
                    ExpressionParser.parse("Signed In IN [Signed In]"));
        }

        @Test
        void readsAFacetNameGreedilyUpToTheOperator() {
            // Nothing terminates "Day of the Week" but the operator keyword.
            assertEquals(
                    condition("Day of the Week", Operator.NOT_IN, List.of("Saturday", "Sunday")),
                    ExpressionParser.parse("Day of the Week NOT IN [Saturday, Sunday]"));
        }

        @Test
        void consumesBracketContentsWholeBeforeConsideringGrouping() {
            final String src = "Signed In IN [Signed In] AND Size IN [Large]";
            assertEquals(
                    and(
                            condition("Signed In", Operator.IN, List.of("Signed In")),
                            condition("Size", Operator.IN, List.of("Large"))),
                    ExpressionParser.parse(src));
        }

        @Test
        void preservesInteriorSpacesInALabelButTrimsTheEdges() {
            assertEquals(
                    condition("Signed In", Operator.IN, List.of("Signed Out")),
                    ExpressionParser.parse("Signed In IN [  Signed Out  ]"));
        }
    }

    @Nested
    @DisplayName("parse: precedence and nesting")
    class PrecedenceAndNesting {

        @Test
        void bindsAndTighterThanOr() {
            // a OR b AND c == a OR (b AND c)
            final ExpressionNode node = ExpressionParser.parse(
                    "Size IN [Large] OR Size IN [Small] AND Signed In IN [Signed In]");
            assertEquals(
                    or(
                            condition("Size", Operator.IN, List.of("Large")),
                            and(
                                    condition("Size", Operator.IN, List.of("Small")),
                                    condition("Signed In", Operator.IN, List.of("Signed In")))),
                    node);
        }

        @Test
        void honoursExplicitParentheses() {
            final ExpressionNode node = ExpressionParser.parse(
                    "Signed In IN [Signed In] AND (Size IN [Large] OR Day of the Week IN [Saturday])");
            assertEquals(
                    and(
                            condition("Signed In", Operator.IN, List.of("Signed In")),
                            or(
                                    condition("Size", Operator.IN, List.of("Large")),
                                    condition("Day of the Week", Operator.IN, List.of("Saturday")))),
                    node);
        }

        @Test
        void flattensSameOperatorChains() {
            final ExpressionNode node = ExpressionParser.parse("A IN [1] AND B IN [2] AND C IN [3]");
            final Binary b = assertInstanceOf(Binary.class, node);
            assertEquals(LogicalOperator.AND, b.operator());
            assertEquals(3, b.operands().size());
        }

        @Test
        void parsesArbitraryDepth() {
            final String src = "((A IN [1] OR B IN [2]) AND C IN [3]) OR D IN [4]";
            assertTrue(!roundTrip(src).isEmpty());
        }
    }

    @Nested
    @DisplayName("parse: NOT (D5 group negation)")
    class GroupNegation {

        @Test
        void parsesNotBeforeAGroup() {
            final ExpressionNode node = ExpressionParser.parse(
                    "NOT (Size IN [Small] AND Day of the Week IN [Sunday])");
            assertEquals(
                    not(and(
                            condition("Size", Operator.IN, List.of("Small")),
                            condition("Day of the Week", Operator.IN, List.of("Sunday")))),
                    node);
        }

        @Test
        void distinguishesNotGroupFromNotIn() {
            final ExpressionNode node = ExpressionParser.parse(
                    "Size IN [Large] AND NOT (Day of the Week IN [Sunday] AND A IN [1])");
            final Binary b = assertInstanceOf(Binary.class, node);
            assertEquals(LogicalOperator.AND, b.operator());
            assertInstanceOf(Not.class, b.operands().get(1));
        }

        @Test
        void foldsNotOverABareConditionIntoNotIn() {
            assertEquals(
                    condition("Size", Operator.NOT_IN, List.of("Small")),
                    not(condition("Size", Operator.IN, List.of("Small"))));
        }

        @Test
        void collapsesDoubleNegation() {
            final ExpressionNode inner = and(condition("A", Operator.IN, List.of("1")), condition("B", Operator.IN, List.of("2")));
            assertEquals(inner, not(not(inner)));
        }
    }

    @Nested
    @DisplayName("parse: errors")
    class Errors {

        @ParameterizedTest(name = "rejects {0}")
        @ValueSource(strings = {
                "Size [Small]",           // missing operator
                "Size IN Small",           // missing brackets
                "Size IN [Small",          // unclosed bracket
                "(Size IN [Small]",        // unclosed paren
                "Size IN []",              // empty value list
                "AND Size IN [Small]",     // leading operator
                "Size IN [Small] AND",     // trailing operator
        })
        void rejectsMalformedInput(final String src) {
            assertThrows(ExpressionParseException.class, () -> ExpressionParser.parse(src));
        }

        @Test
        void reportsAPositionAndACaretDiagram() {
            final ExpressionParseException e = assertThrows(ExpressionParseException.class,
                    () -> ExpressionParser.parse("Size IN [Small] AND Day of the Week [Sunday]"));
            assertTrue(e.getPosition() > 0);
            assertTrue(e.describe().contains("^"));
        }
    }

    @Nested
    @DisplayName("round-trip stability")
    class RoundTripStability {

        @ParameterizedTest
        @ValueSource(strings = {
                "Size IN [Small, Medium, Large]",
                "Day of the Week IN [Saturday, Sunday] AND Size NOT IN [Small]",
                "Size NOT IN [Small]",
                "Signed In IN [Signed In] AND (Size IN [Large] OR Day of the Week IN [Saturday, Sunday])",
                "Size IN [Medium, Large] AND NOT (Signed In IN [Signed Out] AND Day of the Week IN [Sunday])",
                "((A IN [1] OR B IN [2]) AND C IN [3]) OR NOT (D IN [4] AND E IN [5])",
                "Day of the Week IN [Monday, Tuesday, Wednesday, Thursday, Friday]",
                "Signed In IN [Signed In, Signed Out]",
        })
        void isStableAndSemanticsPreserving(final String src) {
            final String formatted = roundTrip(src);
            assertEquals(ExpressionParser.parse(src), ExpressionParser.parse(formatted));
        }
    }

    @Nested
    @DisplayName("ast helpers")
    class AstHelpers {

        @Test
        void listsFacetsInFirstSeenOrder() {
            final ExpressionNode node = ExpressionParser.parse(
                    "Size IN [Large] AND (Signed In IN [Signed In] OR Size IN [Medium])");
            assertEquals(List.of("Size", "Signed In"), List.copyOf(Expressions.facetsUsed(node)));
        }

        @Test
        void countsConditions() {
            final ExpressionNode node = ExpressionParser.parse("A IN [1] AND (B IN [2] OR NOT (C IN [3] AND D IN [4]))");
            assertEquals(4, Expressions.countConditions(node));
        }
    }

}
