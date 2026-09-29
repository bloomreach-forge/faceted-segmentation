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
 * Canonical formatter.
 *
 * Java port of extension/src/expression/format.ts. Used here for logging and diagnostics — e.g.
 * echoing back the parsed form of a stored expression in a trace/debug log — not for anything the
 * CMS reads, since the canonical *authoring* format is produced by the extension.
 *
 * Rules (see "Canonical formatting" in the root README):
 *   - two spaces of indent per nesting level
 *   - a binary operator starts the line of its right-hand operand
 *   - a group's '(' ends its introducing line; its ')' sits alone at the opening indent
 *   - values joined with ", " and wrapped at a soft margin, aligned inside '['
 */
package org.bloomreach.forge.facetedsegmentation.expression;

import java.util.List;

import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Binary;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Condition;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Not;

public final class ExpressionFormatter {

    private static final int DEFAULT_INDENT = 2;
    private static final int DEFAULT_WRAP_AT = 64;

    private ExpressionFormatter() {
    }

    /** Formats an expression as its canonical multi-line string. {@code null} yields {@code ""}. */
    public static String format(final ExpressionNode node) {
        return format(node, DEFAULT_INDENT, DEFAULT_WRAP_AT);
    }

    public static String format(final ExpressionNode node, final int indent, final int wrapAt) {
        if (node == null) {
            return "";
        }
        return render(node, 0, indent, wrapAt).stripTrailing();
    }

    /** Single-line form — no wrapping. Used for compact logging. */
    public static String inline(final ExpressionNode node) {
        if (node == null) {
            return "";
        }
        return inlineNode(node);
    }

    private static String inlineNode(final ExpressionNode node) {
        if (node instanceof Condition c) {
            return c.facet() + " " + c.operator().token() + " [" + String.join(", ", c.values()) + "]";
        }
        if (node instanceof Not n) {
            // NOT always parenthesizes its operand: a bare condition under NOT is impossible
            // because Expressions.not() folds it into NOT IN.
            return "NOT (" + inlineNode(n.operand()) + ")";
        }
        final Binary b = (Binary) node;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.operands().size(); i++) {
            final ExpressionNode operand = b.operands().get(i);
            if (i > 0) {
                sb.append(' ').append(b.operator()).append(' ');
            }
            if (needsParens(operand, b.operator())) {
                sb.append('(').append(inlineNode(operand)).append(')');
            } else {
                sb.append(inlineNode(operand));
            }
        }
        return sb.toString();
    }

    // --- indented form ---------------------------------------------------------------------------

    private static String render(final ExpressionNode node, final int depth, final int indent, final int wrapAt) {
        final String pad = " ".repeat(depth * indent);

        if (node instanceof Condition c) {
            return pad + condition(c, depth, indent, wrapAt);
        }
        if (node instanceof Not n) {
            return pad + group("NOT ", n.operand(), depth, indent, wrapAt);
        }

        final Binary b = (Binary) node;
        final StringBuilder out = new StringBuilder();
        final List<ExpressionNode> operands = b.operands();
        for (int i = 0; i < operands.size(); i++) {
            final ExpressionNode op = operands.get(i);
            final boolean wrap = needsParens(op, b.operator());
            if (i > 0) {
                out.append('\n');
            }
            if (i == 0) {
                out.append(wrap ? pad + group("", op, depth, indent, wrapAt) : render(op, depth, indent, wrapAt));
                continue;
            }
            // The operator sits at one extra level of indent, so continuation lines read as
            // subordinate to the first operand.
            final String opPad = " ".repeat((depth + 1) * indent);
            final String prefix = opPad + b.operator() + " ";
            if (wrap) {
                out.append(stripTrailingSpace(prefix)).append(' ')
                        .append(stripLeadingSpace(group("", op, depth + 1, indent, wrapAt)));
            } else {
                final String body = render(op, depth + 1, indent, wrapAt);
                out.append(prefix).append(stripLeadingSpace(body));
            }
        }
        return out.toString();
    }

    /** {@code NOT (} / {@code (} ... {@code )} with the body one level deeper. */
    private static String group(final String prefix, final ExpressionNode inner, final int depth,
                                final int indent, final int wrapAt) {
        final String pad = " ".repeat(depth * indent);
        final String body = render(inner, depth + 1, indent, wrapAt);
        return prefix + "(\n" + body + "\n" + pad + ")";
    }

    /** {@code Facet IN [a, b]}, wrapping the value list when it would overrun the margin. */
    private static String condition(final Condition node, final int depth, final int indent, final int wrapAt) {
        final String head = node.facet() + " " + node.operator().token() + " ";
        final String oneLine = head + "[" + String.join(", ", node.values()) + "]";
        final int budget = wrapAt - depth * indent;
        if (oneLine.length() <= budget) {
            return oneLine;
        }

        // Wrapped: values indented one level inside the brackets, greedily packed.
        final String pad = " ".repeat(depth * indent);
        final String valuePad = " ".repeat((depth + 1) * indent);
        final List<String> values = node.values();
        final StringBuilder rows = new StringBuilder();
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            final String piece = i == values.size() - 1 ? values.get(i) : values.get(i) + ",";
            if (row.isEmpty()) {
                row.append(piece);
            } else if (row.length() + 1 + piece.length() + valuePad.length() <= wrapAt) {
                row.append(' ').append(piece);
            } else {
                if (!rows.isEmpty()) {
                    rows.append('\n');
                }
                rows.append(valuePad).append(row);
                row = new StringBuilder(piece);
            }
        }
        if (!row.isEmpty()) {
            if (!rows.isEmpty()) {
                rows.append('\n');
            }
            rows.append(valuePad).append(row);
        }

        return head + "[\n" + rows + "\n" + pad + "]";
    }

    /**
     * A child needs parentheses when it binds LESS tightly than its parent — the only case being
     * an OR nested inside an AND. AND inside OR needs none, since AND already binds tighter. NOT
     * children are self-parenthesizing.
     */
    private static boolean needsParens(final ExpressionNode child, final ExpressionNode.LogicalOperator parentOperator) {
        if (child instanceof Not) {
            return false;
        }
        return child.precedence() < parentOperator.precedence();
    }

    private static String stripLeadingSpace(final String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return s.substring(i);
    }

    private static String stripTrailingSpace(final String s) {
        int i = s.length();
        while (i > 0 && s.charAt(i - 1) == ' ') {
            i--;
        }
        return s.substring(0, i);
    }
}
