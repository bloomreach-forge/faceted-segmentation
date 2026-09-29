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
 * Reference evaluator — Java port of extension/src/expression/evaluate.ts.
 *
 * THIS IS THE AUTHORITATIVE EVALUATION for the delivery tier. Unlike the TypeScript copy (which
 * only powers the builder's preview), a mismatch here is not a cosmetic inconsistency — it is
 * either an editor who is shown a preview that lies about what will actually gate content, or a
 * visitor who sees content they should not, or is denied content they should see.
 *
 * Semantics (see "The grammar" in the root README):
 *   F IN [a, b]      -> the request's values for F intersect {a, b}
 *   F NOT IN [a, b]  -> they do not intersect
 *   empty expression -> always true (ungated)
 *
 * IN is INTERSECTION, not subset: a visitor holding both Small and Large matches Size IN [Large].
 * That
 * matches how the multi-select palettes behave today.
 */
package org.bloomreach.forge.facetedsegmentation.expression;

import java.util.List;

import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Binary;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Condition;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.LogicalOperator;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Not;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Operator;

public final class ExpressionEvaluator {

    private ExpressionEvaluator() {
    }

    /** Evaluates {@code expression} against {@code audience} under the default options (strict). */
    public static boolean evaluate(final ExpressionNode expression, final Audience audience) {
        return evaluate(expression, audience, EvaluateOptions.DEFAULT);
    }

    /**
     * Evaluates {@code expression} against {@code audience}.
     *
     * @param expression the parsed tree, or {@code null} for an ungated (empty-string) rule,
     *                    which always evaluates true
     */
    public static boolean evaluate(final ExpressionNode expression, final Audience audience,
                                   final EvaluateOptions options) {
        if (expression == null) {
            return true; // ungated
        }
        final Context ctx = new Context(audience, options.absentFacetPolicy());
        return visit(expression, ctx);
    }

    private record Context(Audience audience, AbsentFacetPolicy policy) {
    }

    private static boolean visit(final ExpressionNode node, final Context ctx) {
        if (node instanceof Condition c) {
            return evaluateCondition(c, ctx);
        }
        if (node instanceof Not n) {
            return !visit(n.operand(), ctx);
        }
        final Binary b = (Binary) node;
        if (b.operator() == LogicalOperator.OR) {
            for (final ExpressionNode operand : b.operands()) {
                if (visit(operand, ctx)) {
                    return true;
                }
            }
            return false;
        }
        for (final ExpressionNode operand : b.operands()) {
            if (!visit(operand, ctx)) {
                return false;
            }
        }
        return true;
    }

    private static boolean evaluateCondition(final Condition node, final Context ctx) {
        final List<String> held = ctx.audience().get(node.facet());

        if (held.isEmpty()) {
            // IN can never be satisfied without a value. NOT IN depends on the policy.
            if (node.operator() == Operator.IN) {
                return false;
            }
            return ctx.policy() == AbsentFacetPolicy.PERMISSIVE;
        }

        boolean intersects = false;
        for (final String value : node.values()) {
            if (held.contains(value)) {
                intersects = true;
                break;
            }
        }
        return node.operator() == Operator.IN ? intersects : !intersects;
    }
}
