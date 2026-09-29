/**
 * Recursive-descent parser for faceted segmentation expressions.
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
 * Facet names and values are human-readable strings containing spaces and parentheses, so
 * there is no separate lexer — tokenizing is context-sensitive and folded into the parser:
 *
 *   1. Bracket contents are scanned to the closing `]` before anything else is considered.
 *      This is what makes `Index IN [Multi-Asset Index (MAX)]` unambiguous — a label may
 *      contain parentheses, and they are not grouping.
 *   2. A facet name is read greedily up to a standalone `IN` / `NOT IN` keyword.
 *   3. `NOT` is group negation when followed by `(`, and part of `NOT IN` otherwise.
 */

import {
  type Expression,
  type ExpressionNode,
  binary,
  condition,
  not,
} from './ast';

export class ParseError extends Error {
  constructor(
    message: string,
    /** Zero-based offset into the source string where the problem was detected. */
    readonly position: number,
    readonly source: string,
  ) {
    super(message);
    this.name = 'ParseError';
  }

  /** Single-line caret diagram, for surfacing the failure in the UI. */
  describe(): string {
    const upto = this.source.slice(0, this.position);
    const line = upto.split('\n').length;
    const col = this.position - (upto.lastIndexOf('\n') + 1);
    const text = this.source.split('\n')[line - 1] ?? '';
    return `${this.message} (line ${line}, column ${col + 1})\n${text}\n${' '.repeat(col)}^`;
  }
}

/** Keywords that terminate a greedy facet-name scan. */
const KEYWORDS = ['NOT IN', 'IN', 'AND', 'OR', 'NOT'] as const;

class Parser {
  private pos = 0;

  constructor(private readonly src: string) {}

  parse(): Expression {
    this.skipWhitespace();
    if (this.pos >= this.src.length) return null; // empty expression = ungated
    const node = this.parseOr();
    this.skipWhitespace();
    if (this.pos < this.src.length) {
      throw this.error(`Unexpected input: ${JSON.stringify(this.rest().slice(0, 24))}`);
    }
    return node;
  }

  // --- grammar rules ------------------------------------------------------

  private parseOr(): ExpressionNode {
    const operands = [this.parseAnd()];
    while (this.tryKeyword('OR')) operands.push(this.parseAnd());
    return binary('OR', operands);
  }

  private parseAnd(): ExpressionNode {
    const operands = [this.parseUnary()];
    while (this.tryKeyword('AND')) operands.push(this.parseUnary());
    return binary('AND', operands);
  }

  private parseUnary(): ExpressionNode {
    this.skipWhitespace();
    // `NOT` here is group negation. `NOT IN` belongs to a condition and is handled there,
    // so only treat NOT as a prefix when it is not immediately followed by IN.
    if (this.peekKeyword('NOT') && !this.peekKeyword('NOT IN')) {
      this.consume('NOT'.length);
      return not(this.parsePrimary());
    }
    return this.parsePrimary();
  }

  private parsePrimary(): ExpressionNode {
    this.skipWhitespace();
    if (this.src[this.pos] === '(') {
      this.consume(1);
      const inner = this.parseOr();
      this.skipWhitespace();
      if (this.src[this.pos] !== ')') throw this.error("Expected ')'");
      this.consume(1);
      return inner;
    }
    return this.parseCondition();
  }

  private parseCondition(): ExpressionNode {
    this.skipWhitespace();
    const start = this.pos;
    const { facet, operator } = this.readFacetAndOperator();
    if (!facet) throw new ParseError('Expected a facet name', start, this.src);

    this.skipWhitespace();
    if (this.src[this.pos] !== '[') throw this.error(`Expected '[' after ${operator}`);
    const values = this.readBracketedValues();
    return condition(facet, operator, values);
  }

  // --- scanning primitives -----------------------------------------------

  /**
   * Rule 2: read greedily until a standalone `IN` or `NOT IN`. Everything before it is the
   * facet name, which is why multi-word names like "Day of the Week" parse correctly.
   */
  private readFacetAndOperator(): { facet: string; operator: 'IN' | 'NOT IN' } {
    const start = this.pos;
    while (this.pos < this.src.length) {
      if (this.peekKeyword('NOT IN')) {
        const facet = this.src.slice(start, this.pos).trim();
        this.consume('NOT IN'.length);
        return { facet, operator: 'NOT IN' };
      }
      if (this.peekKeyword('IN')) {
        const facet = this.src.slice(start, this.pos).trim();
        this.consume('IN'.length);
        return { facet, operator: 'IN' };
      }
      // A bare AND/OR/NOT or a bracket before any operator means this is not a condition.
      if (this.peekKeyword('AND') || this.peekKeyword('OR')) {
        throw this.error('Expected IN or NOT IN before a logical operator');
      }
      if (this.src[this.pos] === '(' || this.src[this.pos] === ')' || this.src[this.pos] === '[') {
        throw this.error(`Expected IN or NOT IN, found ${JSON.stringify(this.src[this.pos])}`);
      }
      this.pos++;
    }
    throw new ParseError('Expected IN or NOT IN', start, this.src);
  }

  /**
   * Rule 1: consume from `[` to the matching `]` wholesale, then split on commas. Because the
   * whole span is taken first, parentheses and other punctuation inside labels are inert.
   */
  private readBracketedValues(): string[] {
    const open = this.pos;
    this.consume(1); // '['
    const close = this.src.indexOf(']', this.pos);
    if (close === -1) throw new ParseError("Unclosed '[' — expected ']'", open, this.src);

    const raw = this.src.slice(this.pos, close);
    this.pos = close + 1;

    const values = raw
      .split(',')
      .map((v) => v.replace(/\s+/g, ' ').trim())
      .filter((v) => v.length > 0);

    if (values.length === 0) throw new ParseError('Empty value list', open, this.src);
    return values;
  }

  /**
   * True when `word` sits at the cursor as a standalone token. The boundary check is what
   * prevents matching "IN" inside "Index" or "INdemnity".
   */
  private peekKeyword(word: string): boolean {
    if (!this.src.startsWith(word, this.pos)) return false;
    const before = this.pos === 0 ? ' ' : this.src[this.pos - 1];
    const after = this.src[this.pos + word.length] ?? ' ';
    return !isWordChar(before) && !isWordChar(after);
  }

  private tryKeyword(word: (typeof KEYWORDS)[number]): boolean {
    this.skipWhitespace();
    if (!this.peekKeyword(word)) return false;
    this.consume(word.length);
    return true;
  }

  private skipWhitespace(): void {
    while (this.pos < this.src.length && /\s/.test(this.src[this.pos])) this.pos++;
  }

  private consume(n: number): void {
    this.pos += n;
  }

  private rest(): string {
    return this.src.slice(this.pos);
  }

  private error(message: string): ParseError {
    return new ParseError(message, this.pos, this.src);
  }
}

const isWordChar = (c: string) => /[A-Za-z0-9_]/.test(c);

/**
 * Parses an expression string into an AST.
 *
 * @throws {ParseError} on malformed input.
 * @returns `null` for an empty/whitespace-only string, meaning the section is ungated.
 */
export function parseExpression(source: string): Expression {
  return new Parser(source ?? '').parse();
}

/** Non-throwing variant, for validating input as the user types. */
export function tryParseExpression(
  source: string,
): { ok: true; value: Expression } | { ok: false; error: ParseError } {
  try {
    return { ok: true, value: parseExpression(source) };
  } catch (e) {
    if (e instanceof ParseError) return { ok: false, error: e };
    throw e;
  }
}
