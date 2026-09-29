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
 * Recursive-descent parser for faceted segmentation expressions.
 *
 * Java port of extension/src/expression/parse.ts. This MUST stay behaviorally identical to that
 * file — see D12 in the root README,
 * which is explicit that the grammar has exactly one normative implementation per language, kept
 * in lockstep, rather than letting Java and TypeScript drift into two dialects.
 *
 * Grammar (see "The grammar" in the root README):
 *
 *   expression = or_expr
 *   or_expr    = and_expr { "OR" and_expr }
 *   and_expr   = unary { "AND" unary }
 *   unary      = [ "NOT" ] primary
 *   primary    = "(" expression ")" | condition
 *   condition  = facet ("NOT IN" | "IN") "[" value_list "]"
 *
 * Facet names and values are human-readable strings containing spaces and parentheses, so there
 * is no separate lexer — tokenizing is context-sensitive and folded into the parser:
 *
 *   1. Bracket contents are scanned to the closing ']' before anything else is considered. This is
 *      what makes "Index IN [Multi-Asset Index (MAX)]" unambiguous — a label may contain
 *      parentheses, and they are not grouping.
 *   2. A facet name is read greedily up to a standalone "IN" / "NOT IN" keyword.
 *   3. "NOT" is group negation when followed by '(', and part of "NOT IN" otherwise.
 */
package org.bloomreach.forge.facetedsegmentation.expression;

import java.util.ArrayList;
import java.util.List;

import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.LogicalOperator;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode.Operator;

public final class ExpressionParser {

    private final String src;
    private int pos;

    private ExpressionParser(final String src) {
        this.src = src;
    }

    /**
     * Parses an expression string into a tree.
     *
     * @return {@code null} for an empty/whitespace-only string, meaning the section is ungated
     * @throws ExpressionParseException on malformed input
     */
    public static ExpressionNode parse(final String source) {
        return new ExpressionParser(source == null ? "" : source).parseTop();
    }

    // --- top level -----------------------------------------------------------------------------

    private ExpressionNode parseTop() {
        skipWhitespace();
        if (pos >= src.length()) {
            return null; // empty expression = ungated
        }
        final ExpressionNode node = parseOr();
        skipWhitespace();
        if (pos < src.length()) {
            throw error("Unexpected input: " + quote(rest(24)));
        }
        return node;
    }

    // --- grammar rules ---------------------------------------------------------------------------

    private ExpressionNode parseOr() {
        final List<ExpressionNode> operands = new ArrayList<>();
        operands.add(parseAnd());
        while (tryKeyword("OR")) {
            operands.add(parseAnd());
        }
        return Expressions.binary(LogicalOperator.OR, operands);
    }

    private ExpressionNode parseAnd() {
        final List<ExpressionNode> operands = new ArrayList<>();
        operands.add(parseUnary());
        while (tryKeyword("AND")) {
            operands.add(parseUnary());
        }
        return Expressions.binary(LogicalOperator.AND, operands);
    }

    private ExpressionNode parseUnary() {
        skipWhitespace();
        // "NOT" here is group negation. "NOT IN" belongs to a condition and is handled there, so
        // only treat NOT as a prefix when it is not immediately followed by IN.
        if (peekKeyword("NOT") && !peekKeyword("NOT IN")) {
            consume("NOT".length());
            return Expressions.not(parsePrimary());
        }
        return parsePrimary();
    }

    private ExpressionNode parsePrimary() {
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == '(') {
            consume(1);
            final ExpressionNode inner = parseOr();
            skipWhitespace();
            if (pos >= src.length() || src.charAt(pos) != ')') {
                throw error("Expected ')'");
            }
            consume(1);
            return inner;
        }
        return parseCondition();
    }

    private ExpressionNode parseCondition() {
        skipWhitespace();
        final int start = pos;
        final FacetAndOperator fo = readFacetAndOperator();
        if (fo.facet.isEmpty()) {
            throw new ExpressionParseException("Expected a facet name", start, src);
        }

        skipWhitespace();
        if (pos >= src.length() || src.charAt(pos) != '[') {
            throw error("Expected '[' after " + fo.operator.token());
        }
        final List<String> values = readBracketedValues();
        return Expressions.condition(fo.facet, fo.operator, values);
    }

    // --- scanning primitives --------------------------------------------------------------------

    private record FacetAndOperator(String facet, Operator operator) {
    }

    /**
     * Rule 2: read greedily until a standalone "IN" or "NOT IN". Everything before it is the facet
     * name, which is why multi-word names like "Day of the Week" parse correctly.
     */
    private FacetAndOperator readFacetAndOperator() {
        final int start = pos;
        while (pos < src.length()) {
            if (peekKeyword("NOT IN")) {
                final String facet = src.substring(start, pos).trim();
                consume("NOT IN".length());
                return new FacetAndOperator(facet, Operator.NOT_IN);
            }
            if (peekKeyword("IN")) {
                final String facet = src.substring(start, pos).trim();
                consume("IN".length());
                return new FacetAndOperator(facet, Operator.IN);
            }
            // A bare AND/OR or a bracket before any operator means this is not a condition.
            if (peekKeyword("AND") || peekKeyword("OR")) {
                throw error("Expected IN or NOT IN before a logical operator");
            }
            final char c = src.charAt(pos);
            if (c == '(' || c == ')' || c == '[') {
                throw error("Expected IN or NOT IN, found " + quote(String.valueOf(c)));
            }
            pos++;
        }
        throw new ExpressionParseException("Expected IN or NOT IN", start, src);
    }

    /**
     * Rule 1: consume from '[' to the matching ']' wholesale, then split on commas. Because the
     * whole span is taken first, parentheses and other punctuation inside labels are inert.
     */
    private List<String> readBracketedValues() {
        final int open = pos;
        consume(1); // '['
        final int close = src.indexOf(']', pos);
        if (close == -1) {
            throw new ExpressionParseException("Unclosed '[' — expected ']'", open, src);
        }

        final String raw = src.substring(pos, close);
        pos = close + 1;

        final List<String> values = new ArrayList<>();
        for (final String piece : raw.split(",", -1)) {
            final String value = piece.replaceAll("\\s+", " ").trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }

        if (values.isEmpty()) {
            throw new ExpressionParseException("Empty value list", open, src);
        }
        return values;
    }

    /**
     * True when {@code word} sits at the cursor as a standalone token. The boundary check is what
     * prevents matching "IN" inside "Index" or "INdemnity".
     */
    private boolean peekKeyword(final String word) {
        if (!src.regionMatches(pos, word, 0, word.length())) {
            return false;
        }
        final char before = pos == 0 ? ' ' : src.charAt(pos - 1);
        final int afterIndex = pos + word.length();
        final char after = afterIndex < src.length() ? src.charAt(afterIndex) : ' ';
        return !isWordChar(before) && !isWordChar(after);
    }

    private boolean tryKeyword(final String word) {
        skipWhitespace();
        if (!peekKeyword(word)) {
            return false;
        }
        consume(word.length());
        return true;
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private void consume(final int n) {
        pos += n;
    }

    private String rest(final int maxLen) {
        final String r = src.substring(pos);
        return r.length() > maxLen ? r.substring(0, maxLen) : r;
    }

    private ExpressionParseException error(final String message) {
        return new ExpressionParseException(message, pos, src);
    }

    private static boolean isWordChar(final char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String quote(final String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
