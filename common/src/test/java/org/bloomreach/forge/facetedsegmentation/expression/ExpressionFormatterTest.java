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
 * Mirrors extension/src/expression/expression.test.ts's "format:" describe block.
 */
package org.bloomreach.forge.facetedsegmentation.expression;

import static org.bloomreach.forge.facetedsegmentation.expression.Expressions.and;
import static org.bloomreach.forge.facetedsegmentation.expression.Expressions.condition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Operator;
import org.junit.jupiter.api.Test;

import static org.bloomreach.forge.facetedsegmentation.expression.Expressions.or;

class ExpressionFormatterTest {

    private static String roundTrip(final String src) {
        return ExpressionFormatter.format(ExpressionParser.parse(src));
    }

    @Test
    void formatsASingleConditionOnOneLine() {
        final ExpressionNode node = condition("Size", Operator.IN, List.of("Small", "Medium", "Large"));
        assertEquals("Size IN [Small, Medium, Large]", ExpressionFormatter.format(node));
    }

    @Test
    void formatsATwoConditionAndTheCommonestRealShape() {
        final ExpressionNode node = and(
                condition("Size", Operator.IN, List.of("Medium", "Large")),
                condition("Signed In", Operator.IN, List.of("Signed In")));
        assertEquals(
                "Size IN [Medium, Large]\n  AND Signed In IN [Signed In]",
                ExpressionFormatter.format(node));
    }

    @Test
    void statesASharedConditionOnceWithAnOrBranchNested() {
        final ExpressionNode node = and(
                condition("Signed In", Operator.IN, List.of("Signed In")),
                or(
                        condition("Size", Operator.IN, List.of("Large")),
                        condition("Day of the Week", Operator.IN, List.of("Saturday", "Sunday"))));
        final String out = ExpressionFormatter.format(node);
        assertTrue(out.contains("AND ("));
        assertTrue(out.contains("OR "));

        // The shared condition appears exactly once — the whole point of D4.
        final Matcher m = Pattern.compile("Signed In IN").matcher(out);
        int count = 0;
        while (m.find()) {
            count++;
        }
        assertEquals(1, count);
        assertEquals(out, roundTrip(out));
    }

    @Test
    void wrapsAWideValueList() {
        final ExpressionNode node = condition("Day of the Week", Operator.IN, List.of(
                "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"));
        final String out = ExpressionFormatter.format(node);
        assertTrue(out.split("\n").length > 1);
        assertTrue(out.contains("["));
        assertTrue(out.contains("]"));
        assertEquals(out, roundTrip(out));
    }

    @Test
    void parenthesizesOrInsideAndButNotAndInsideOr() {
        final ExpressionNode orInAnd = and(
                condition("A", Operator.IN, List.of("1")),
                or(condition("B", Operator.IN, List.of("2")), condition("C", Operator.IN, List.of("3"))));
        assertEquals("A IN [1] AND (B IN [2] OR C IN [3])", ExpressionFormatter.inline(orInAnd));

        final ExpressionNode andInOr = or(
                condition("A", Operator.IN, List.of("1")),
                and(condition("B", Operator.IN, List.of("2")), condition("C", Operator.IN, List.of("3"))));
        assertEquals("A IN [1] OR B IN [2] AND C IN [3]", ExpressionFormatter.inline(andInOr));
    }

    @Test
    void emitsAnEmptyStringForAnUngatedExpression() {
        assertEquals("", ExpressionFormatter.format(null));
    }
}
