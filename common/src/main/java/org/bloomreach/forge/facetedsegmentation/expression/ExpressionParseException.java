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
 * Java port of extension/src/expression/parse.ts's ParseError.
 */
package org.bloomreach.forge.facetedsegmentation.expression;

/**
 * Thrown for malformed expression syntax. Carries the byte offset where the problem was detected
 * so a caller (a log line, or eventually a UI) can point at the exact spot rather than just saying
 * "invalid".
 */
public class ExpressionParseException extends RuntimeException {

    private final int position;
    private final String source;

    public ExpressionParseException(final String message, final int position, final String source) {
        super(message);
        this.position = position;
        this.source = source;
    }

    /** Zero-based offset into {@link #getSource()} where the problem was detected. */
    public int getPosition() {
        return position;
    }

    public String getSource() {
        return source;
    }

    /** Single-line caret diagram, for surfacing the failure in a log or an admin-facing message. */
    public String describe() {
        final String upto = source.substring(0, Math.min(position, source.length()));
        final int line = countLines(upto);
        final int lastNewline = upto.lastIndexOf('\n');
        final int col = position - (lastNewline + 1);
        final String[] lines = source.split("\n", -1);
        final String text = line - 1 < lines.length ? lines[line - 1] : "";
        return getMessage() + " (line " + line + ", column " + (col + 1) + ")\n"
                + text + "\n" + " ".repeat(Math.max(0, col)) + "^";
    }

    private static int countLines(final String s) {
        int lines = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
