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
 * Mirrors extension/src/expression/expression.test.ts's "evaluate" describe block plus the
 * absent-facet policy sub-block. This is the security-critical suite: it pins the exact
 * semantics that gate access to secured content.
 */
package org.bloomreach.forge.facetedsegmentation.expression;

import static org.bloomreach.forge.facetedsegmentation.expression.ExpressionEvaluator.evaluate;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExpressionEvaluatorTest {

    @Nested
    @DisplayName("evaluate")
    class Evaluate {

        private final ExpressionNode expr = ExpressionParser.parse(
                "Size IN [Medium, Large] AND (Signed In IN [Signed In] OR Day of the Week IN [Saturday])");

        @Test
        void matchesWhenAndAndOneOrBranchHold() {
            assertTrue(evaluate(expr, Audience.builder()
                    .put("Size", "Medium").put("Signed In", "Signed In").build()));
            assertTrue(evaluate(expr, Audience.builder()
                    .put("Size", "Large").put("Day of the Week", "Saturday").build()));
        }

        @Test
        void failsWhenTheAndSideFails() {
            assertFalse(evaluate(expr, Audience.builder()
                    .put("Size", "Small").put("Signed In", "Signed In").build()));
        }

        @Test
        void failsWhenNeitherOrBranchHolds() {
            assertFalse(evaluate(expr, Audience.builder()
                    .put("Size", "Large").put("Signed In", "Signed Out").build()));
        }

        @Test
        void treatsInAsIntersectionNotSubset() {
            // A visitor holding both Small and Large still matches Size IN [Large].
            final ExpressionNode c = ExpressionParser.parse("Size IN [Large]");
            assertTrue(evaluate(c, Audience.builder().putAll("Size", List.of("Small", "Large")).build()));
        }

        @Test
        void anUngatedExpressionAlwaysMatches() {
            assertTrue(evaluate(null, Audience.empty()));
        }

        @Test
        void ignoresFacetsTheExpressionDoesNotMention() {
            final ExpressionNode c = ExpressionParser.parse("Size IN [Large]");
            assertTrue(evaluate(c, Audience.builder()
                    .put("Size", "Large").put("Day of the Week", "Sunday").build()));
        }

        @Test
        void handlesNotIn() {
            final ExpressionNode c = ExpressionParser.parse("Size NOT IN [Small]");
            assertTrue(evaluate(c, Audience.builder().put("Size", "Large").build()));
            assertFalse(evaluate(c, Audience.builder().put("Size", "Small").build()));
        }

        @Test
        void negatesAGroup() {
            final ExpressionNode c = ExpressionParser.parse(
                    "NOT (Size IN [Large] AND Day of the Week IN [Sunday])");
            assertFalse(evaluate(c, Audience.builder()
                    .put("Size", "Large").put("Day of the Week", "Sunday").build()));
            assertTrue(evaluate(c, Audience.builder()
                    .put("Size", "Large").put("Day of the Week", "Monday").build()));
        }
    }

    @Nested
    @DisplayName("absent-facet policy (D11)")
    class AbsentFacetPolicyTests {

        private final ExpressionNode notIn = ExpressionParser.parse("Size NOT IN [Small]");
        private final ExpressionNode isIn = ExpressionParser.parse("Size IN [Small]");

        @Test
        void defaultsToStrictAnAbsentFacetFailsNotIn() {
            assertFalse(evaluate(notIn, Audience.empty()));
        }

        @Test
        void permissiveLetsAnAbsentFacetPassNotIn() {
            final EvaluateOptions opts = EvaluateOptions.builder()
                    .absentFacetPolicy(AbsentFacetPolicy.PERMISSIVE).build();
            assertTrue(evaluate(notIn, Audience.empty(), opts));
        }

        @Test
        void inAlwaysFailsWhenTheFacetIsAbsentUnderEitherPolicy() {
            assertFalse(evaluate(isIn, Audience.empty(),
                    EvaluateOptions.builder().absentFacetPolicy(AbsentFacetPolicy.STRICT).build()));
            assertFalse(evaluate(isIn, Audience.empty(),
                    EvaluateOptions.builder().absentFacetPolicy(AbsentFacetPolicy.PERMISSIVE).build()));
        }

        @Test
        void treatsAnEmptyListTheSameAsAnAbsentKey() {
            final Audience emptyList = Audience.builder().putAll("Size", List.of()).build();
            assertFalse(evaluate(notIn, emptyList));
            assertTrue(evaluate(notIn, emptyList,
                    EvaluateOptions.builder().absentFacetPolicy(AbsentFacetPolicy.PERMISSIVE).build()));
        }

        @Test
        void policyIsIrrelevantOnceTheFacetHasAValue() {
            for (final AbsentFacetPolicy policy : AbsentFacetPolicy.values()) {
                final EvaluateOptions opts = EvaluateOptions.builder().absentFacetPolicy(policy).build();
                assertTrue(evaluate(notIn, Audience.builder().put("Size", "Large").build(), opts));
                assertFalse(evaluate(notIn, Audience.builder().put("Size", "Small").build(), opts));
            }
        }
    }
}
