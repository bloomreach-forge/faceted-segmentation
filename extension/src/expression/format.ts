/**
 * Canonical formatter.
 *
 * The stored string is the only representation (D1), and version *comparison* in the CMS
 * diffs those strings — so formatting must be deterministic. Two semantically identical
 * expressions built by different routes must format byte-identically, or editors see phantom
 * diffs in compare mode.
 *
 * Rules (see "Canonical formatting" in the root README):
 *   - two spaces of indent per nesting level
 *   - a binary operator starts the line of its right-hand operand
 *   - a group's `(` ends its introducing line; its `)` sits alone at the opening indent
 *   - values joined with ", " and wrapped at a soft margin, aligned inside `[`
 */

import {
  type Expression,
  type ExpressionNode,
  isCondition,
  isNot,
  precedenceOf,
  PRECEDENCE,
} from './ast';

export interface FormatOptions {
  /** Spaces per nesting level. */
  indent?: number;
  /** Soft column limit before a value list wraps onto multiple lines. */
  wrapAt?: number;
  /** Emit on one line with no wrapping — for the collapsed summary and for tests. */
  inline?: boolean;
}

const DEFAULTS = { indent: 2, wrapAt: 64, inline: false } as const;

/** Formats an expression as its canonical string. `null` yields `''` (ungated). */
export function formatExpression(node: Expression, options: FormatOptions = {}): string {
  if (!node) return '';
  const opts = { ...DEFAULTS, ...options };
  return opts.inline ? inline(node) : render(node, 0, opts).trimEnd();
}

// ---------------------------------------------------------------------------
// Single-line form
// ---------------------------------------------------------------------------

function inline(node: ExpressionNode): string {
  if (isCondition(node)) {
    return `${node.facet} ${node.operator} [${node.values.join(', ')}]`;
  }
  if (isNot(node)) {
    // NOT always parenthesizes its operand: `NOT (...)`. A bare condition under NOT is
    // impossible because ast.not() folds it into NOT IN.
    return `NOT (${inline(node.operand)})`;
  }
  return node.operands
    .map((op) => (needsParens(op, node.operator) ? `(${inline(op)})` : inline(op)))
    .join(` ${node.operator} `);
}

// ---------------------------------------------------------------------------
// Indented form
// ---------------------------------------------------------------------------

function render(node: ExpressionNode, depth: number, opts: Required<FormatOptions>): string {
  const pad = ' '.repeat(depth * opts.indent);

  if (isCondition(node)) return pad + condition(node, depth, opts);
  if (isNot(node)) return pad + group('NOT ', node.operand, depth, opts);

  // Binary: first operand plain, each subsequent one prefixed by the operator.
  const lines: string[] = [];
  node.operands.forEach((op, i) => {
    const wrap = needsParens(op, node.operator);
    if (i === 0) {
      lines.push(wrap ? pad + group('', op, depth, opts) : render(op, depth, opts));
      return;
    }
    // The operator sits at one extra level of indent, so continuation lines read as
    // subordinate to the first operand.
    const opPad = ' '.repeat((depth + 1) * opts.indent);
    const prefix = `${opPad}${node.operator} `;
    if (wrap) {
      lines.push(prefix.trimEnd() + ' ' + group('', op, depth + 1, opts).trimStart());
    } else {
      const body = render(op, depth + 1, opts);
      // Splice the operator onto the operand's own first line.
      lines.push(prefix + body.trimStart());
    }
  });
  return lines.join('\n');
}

/** `NOT (` / `(` ... `)` with the body one level deeper. */
function group(
  prefix: string,
  inner: ExpressionNode,
  depth: number,
  opts: Required<FormatOptions>,
): string {
  const pad = ' '.repeat(depth * opts.indent);
  const body = render(inner, depth + 1, opts);
  return `${prefix}(\n${body}\n${pad})`;
}

/** `Facet IN [a, b]`, wrapping the value list when it would overrun the margin. */
function condition(
  node: { facet: string; operator: string; values: string[] },
  depth: number,
  opts: Required<FormatOptions>,
): string {
  const head = `${node.facet} ${node.operator} `;
  const oneLine = `${head}[${node.values.join(', ')}]`;
  const budget = opts.wrapAt - depth * opts.indent;
  if (oneLine.length <= budget) return oneLine;

  // Wrapped: values indented one level inside the brackets, greedily packed.
  const pad = ' '.repeat(depth * opts.indent);
  const valuePad = ' '.repeat((depth + 1) * opts.indent);
  const rows: string[] = [];
  let row = '';
  node.values.forEach((v, i) => {
    const piece = i === node.values.length - 1 ? v : `${v},`;
    if (row === '') {
      row = piece;
    } else if (`${row} ${piece}`.length + valuePad.length <= opts.wrapAt) {
      row += ` ${piece}`;
    } else {
      rows.push(row);
      row = piece;
    }
  });
  if (row) rows.push(row);

  return `${head}[\n${rows.map((r) => valuePad + r).join('\n')}\n${pad}]`;
}

/**
 * A child needs parentheses when it binds *less* tightly than its parent — the only case
 * being an OR nested inside an AND. AND inside OR needs none, since AND already binds
 * tighter. NOT children are self-parenthesizing.
 */
function needsParens(child: ExpressionNode, parentOperator: 'AND' | 'OR'): boolean {
  if (isNot(child)) return false;
  return precedenceOf(child) < PRECEDENCE[parentOperator];
}
