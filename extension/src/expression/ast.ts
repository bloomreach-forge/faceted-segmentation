/**
 * AST for faceted segmentation expressions.
 *
 * The stored JCR value is the expression *string* (decision D1) — this tree is an in-memory
 * working form only. It is never serialized as JSON to the repository.
 *
 * Grammar and precedence: see "The grammar" in the root README
 */

/** `Size IN [Small, Medium]` / `Day of the Week NOT IN [Sunday]` */
export interface ConditionNode {
  type: 'condition';
  /** Value list display name, e.g. "Day of the Week" (D3). */
  facet: string;
  operator: 'IN' | 'NOT IN';
  /** Value list item *labels*, e.g. ["Small", "Medium"] (D2). */
  values: string[];
}

/** `A AND B` / `A OR B`, n-ary so a flat chain stays flat. */
export interface BinaryNode {
  type: 'binary';
  operator: 'AND' | 'OR';
  operands: ExpressionNode[];
}

/** `NOT ( ... )` — group negation (D5). */
export interface NotNode {
  type: 'not';
  operand: ExpressionNode;
}

export type ExpressionNode = ConditionNode | BinaryNode | NotNode;

/**
 * An empty expression is represented as `null` and means "no segmentation" — the section is
 * ungated. It serializes to the empty string.
 */
export type Expression = ExpressionNode | null;

export const isCondition = (n: ExpressionNode): n is ConditionNode => n.type === 'condition';
export const isBinary = (n: ExpressionNode): n is BinaryNode => n.type === 'binary';
export const isNot = (n: ExpressionNode): n is NotNode => n.type === 'not';

/** Precedence, higher binds tighter. Drives where the formatter must emit parentheses. */
export const PRECEDENCE = { OR: 1, AND: 2, NOT: 3 } as const;

export function precedenceOf(node: ExpressionNode): number {
  switch (node.type) {
    case 'binary':
      return PRECEDENCE[node.operator];
    case 'not':
      return PRECEDENCE.NOT;
    case 'condition':
      return 4; // atomic — never needs wrapping
  }
}

// ---------------------------------------------------------------------------
// Constructors
// ---------------------------------------------------------------------------

export function condition(
  facet: string,
  operator: 'IN' | 'NOT IN',
  values: string[],
): ConditionNode {
  return { type: 'condition', facet, operator, values };
}

/**
 * Builds an n-ary AND/OR, flattening same-operator children so that
 * `and(and(a, b), c)` yields a single three-operand node. Keeps formatted output flat
 * and prevents gratuitous parentheses.
 */
export function binary(operator: 'AND' | 'OR', operands: ExpressionNode[]): ExpressionNode {
  const flat = operands.flatMap((op) =>
    isBinary(op) && op.operator === operator ? op.operands : [op],
  );
  if (flat.length === 0) throw new Error(`${operator} requires at least one operand`);
  if (flat.length === 1) return flat[0];
  return { type: 'binary', operator, operands: flat };
}

export const and = (...operands: ExpressionNode[]) => binary('AND', operands);
export const or = (...operands: ExpressionNode[]) => binary('OR', operands);

/** Negation. Collapses `NOT NOT x` to `x`, and flips a condition's operator in place. */
export function not(operand: ExpressionNode): ExpressionNode {
  if (isNot(operand)) return operand.operand;
  if (isCondition(operand)) {
    return condition(
      operand.facet,
      operand.operator === 'IN' ? 'NOT IN' : 'IN',
      operand.values,
    );
  }
  return { type: 'not', operand };
}

// ---------------------------------------------------------------------------
// Traversal
// ---------------------------------------------------------------------------

export function walk(node: Expression, visit: (n: ExpressionNode) => void): void {
  if (!node) return;
  visit(node);
  if (isBinary(node)) node.operands.forEach((o) => walk(o, visit));
  else if (isNot(node)) walk(node.operand, visit);
}

/** Every distinct facet referenced, in first-seen order. */
export function facetsUsed(node: Expression): string[] {
  const seen = new Set<string>();
  walk(node, (n) => {
    if (isCondition(n)) seen.add(n.facet);
  });
  return [...seen];
}

/** Total condition count — used for UI summaries. */
export function countConditions(node: Expression): number {
  let n = 0;
  walk(node, (x) => {
    if (isCondition(x)) n++;
  });
  return n;
}
