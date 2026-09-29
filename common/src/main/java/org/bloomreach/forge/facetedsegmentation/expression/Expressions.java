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
 * Constructors and traversal helpers for ExpressionNode trees.
 *
 * Java port of the free functions in extension/src/expression/ast.ts (condition/binary/and/or/
 * not/walk/facetsUsed/countConditions). Kept as static methods on a separate class rather than on
 * the sealed interface itself, matching the TS module's shape: ast.ts has constructors as plain
 * exported functions, not methods on the node types.
 */
package org.bloomreach.forge.facetedsegmentation.expression;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Binary;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Condition;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.LogicalOperator;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Not;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Operator;

public final class Expressions {

    private Expressions() {
    }

    public static Condition condition(final String facet, final Operator operator, final List<String> values) {
        return new Condition(facet, operator, values);
    }

    /**
     * Builds an n-ary AND/OR, flattening same-operator children so that {@code and(and(a, b), c)}
     * yields one three-operand node. Keeps formatted output flat and avoids gratuitous
     * parentheses. A single operand is returned unwrapped, matching {@code ast.ts}'s {@code binary}.
     *
     * @throws IllegalArgumentException if operands is empty
     */
    public static ExpressionNode binary(final LogicalOperator operator, final List<ExpressionNode> operands) {
        final List<ExpressionNode> flat = new ArrayList<>(operands.size());
        for (final ExpressionNode operand : operands) {
            if (operand instanceof Binary b && b.operator() == operator) {
                flat.addAll(b.operands());
            } else {
                flat.add(operand);
            }
        }
        if (flat.isEmpty()) {
            throw new IllegalArgumentException(operator + " requires at least one operand");
        }
        if (flat.size() == 1) {
            return flat.get(0);
        }
        return new Binary(operator, flat);
    }

    public static ExpressionNode and(final ExpressionNode... operands) {
        return binary(LogicalOperator.AND, List.of(operands));
    }

    public static ExpressionNode or(final ExpressionNode... operands) {
        return binary(LogicalOperator.OR, List.of(operands));
    }

    /**
     * Negation. Collapses {@code NOT NOT x} to {@code x}, and flips a condition's operator in
     * place rather than wrapping it — so {@code not(F IN [x])} is the {@code Condition}
     * {@code F NOT IN [x]}, never a {@code Not} node around a condition. This mirrors
     * {@code ast.ts}'s {@code not()} exactly, and callers that pattern-match on {@code Not} may
     * assume its operand is never itself a bare condition of the operator it would collapse to.
     */
    public static ExpressionNode not(final ExpressionNode operand) {
        if (operand instanceof Not n) {
            return n.operand();
        }
        if (operand instanceof Condition c) {
            return condition(c.facet(), c.operator().negate(), c.values());
        }
        return new Not(operand);
    }

    /** Depth-first pre-order visit of every node, including {@code root} itself. Null is a no-op. */
    public static void walk(final ExpressionNode root, final Consumer<ExpressionNode> visit) {
        if (root == null) {
            return;
        }
        visit.accept(root);
        if (root instanceof Binary b) {
            for (final ExpressionNode operand : b.operands()) {
                walk(operand, visit);
            }
        } else if (root instanceof Not n) {
            walk(n.operand(), visit);
        }
    }

    /** Every distinct facet referenced, in first-seen order. */
    public static Set<String> facetsUsed(final ExpressionNode root) {
        final Set<String> seen = new LinkedHashSet<>();
        walk(root, node -> {
            if (node instanceof Condition c) {
                seen.add(c.facet());
            }
        });
        return seen;
    }

    /** Total condition count. */
    public static int countConditions(final ExpressionNode root) {
        final int[] count = {0};
        walk(root, node -> {
            if (node instanceof Condition) {
                count[0]++;
            }
        });
        return count[0];
    }
}
