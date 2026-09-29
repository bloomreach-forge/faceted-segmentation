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
 * AST for faceted segmentation expressions.
 *
 * This is the Java port of extension/src/expression/ast.ts, kept structurally identical on
 * purpose: the two implementations must never drift, because the grammar is a stored contract
 * (D1 in the root README) and this evaluator
 * is what actually enforces access to secured content.
 *
 * The stored JCR value is the expression STRING. This tree is an in-memory working form built
 * fresh from that string on every evaluation — it is never persisted.
 *
 * Grammar and precedence: see "The grammar" in the root README
 */
package org.bloomreach.forge.facetedsegmentation.expression;

import java.util.List;

/**
 * A node in the expression tree. Sealed to the three shapes the grammar allows, so a switch over
 * subtypes is exhaustive and the compiler catches a missed case if the grammar ever grows.
 *
 * There is no explicit "empty expression" node: an empty/ungated expression is represented as a
 * null {@code ExpressionNode} reference, matching the TypeScript {@code Expression = ExpressionNode
 * | null} — see {@link ExpressionEvaluator#evaluate}.
 */
public sealed interface ExpressionNode {

    /** Precedence, higher binds tighter. NOT > AND > OR. A condition is atomic and never wraps. */
    int precedence();

    /** {@code Size IN [Small, Medium]} / {@code Day of the Week NOT IN [Sunday]}. */
    record Condition(String facet, Operator operator, List<String> values) implements ExpressionNode {

        public Condition {
            values = List.copyOf(values);
        }

        @Override
        public int precedence() {
            return 4; // atomic — never needs wrapping
        }
    }

    /** {@code A AND B} / {@code A OR B}, n-ary so a flat chain stays flat. */
    record Binary(LogicalOperator operator, List<ExpressionNode> operands) implements ExpressionNode {

        public Binary {
            operands = List.copyOf(operands);
        }

        @Override
        public int precedence() {
            return operator.precedence();
        }
    }

    /** {@code NOT ( ... )} — group negation (D5). */
    record Not(ExpressionNode operand) implements ExpressionNode {

        @Override
        public int precedence() {
            return LogicalOperator.NOT_PRECEDENCE;
        }
    }

    /** The membership operator on a condition. */
    enum Operator {
        IN("IN"),
        NOT_IN("NOT IN");

        private final String token;

        Operator(final String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        public Operator negate() {
            return this == IN ? NOT_IN : IN;
        }
    }

    /** The logical operator joining a {@link Binary} node's operands. */
    enum LogicalOperator {
        AND(2),
        OR(1);

        static final int NOT_PRECEDENCE = 3;

        private final int precedence;

        LogicalOperator(final int precedence) {
            this.precedence = precedence;
        }

        public int precedence() {
            return precedence;
        }
    }
}
