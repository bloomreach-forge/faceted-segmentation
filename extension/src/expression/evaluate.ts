/**
 * Reference evaluator.
 *
 * The authoritative evaluation happens in Java on the delivery tier. This mirrors those
 * semantics so the builder can preview a rule against a sample audience, and so the semantics
 * are pinned by executable tests rather than prose alone.
 *
 * Semantics (see "The grammar" in the root README):
 *   F IN [a, b]      -> the request's values for F intersect {a, b}
 *   F NOT IN [a, b]  -> they do not intersect
 *   empty expression -> always true (ungated)
 *
 * IN is *intersection*, not subset: a visitor holding both Small and Large matches `Size IN [Large]`.
 * That matches how the multi-select palettes behave today.
 */

import {
  type ConditionNode,
  type Expression,
  type ExpressionNode,
  isBinary,
  isCondition,
  isNot,
} from './ast';

/**
 * The segmentation values a request carries, keyed by facet display name.
 * A facet absent from this map means the request carries no value for it.
 */
export type Audience = Record<string, string[] | string | undefined>;

/**
 * How a condition behaves when the request carries no value at all for that facet.
 *
 * `'strict'`     — the facet must be present to be evaluated; both IN and NOT IN fail.
 * `'permissive'` — IN still fails, but NOT IN passes: there is nothing to exclude, so the
 *                  exclusion is satisfied.
 *
 * Decision D11: the default is `'strict'` (fail closed). A deployment wanting `'permissive'`
 * must set it explicitly — see D11 in the root README.
 */
export type AbsentFacetPolicy = 'strict' | 'permissive';

export interface EvaluateOptions {
  /** Defaults to 'strict' (D11). */
  absentFacetPolicy?: AbsentFacetPolicy;
}

interface Context {
  audience: Audience;
  policy: AbsentFacetPolicy;
}

export function evaluate(
  expression: Expression,
  audience: Audience,
  options: EvaluateOptions = {},
): boolean {
  if (!expression) return true; // ungated

  const ctx: Context = {
    audience,
    policy: options.absentFacetPolicy ?? 'strict',
  };

  return visit(expression, ctx);
}

function visit(node: ExpressionNode, ctx: Context): boolean {
  if (isCondition(node)) return evaluateCondition(node, ctx);
  if (isNot(node)) return !visit(node.operand, ctx);
  if (node.operator === 'OR') return node.operands.some((o) => visit(o, ctx));
  return node.operands.every((o) => visit(o, ctx));
}

function evaluateCondition(node: ConditionNode, ctx: Context): boolean {
  const held = normalize(ctx.audience[node.facet]);

  if (held.length === 0) {
    if (node.operator === 'IN') return false;
    return ctx.policy === 'permissive';
  }

  const intersects = node.values.some((v) => held.includes(v));
  return node.operator === 'IN' ? intersects : !intersects;
}

function normalize(value: string[] | string | undefined): string[] {
  if (value == null) return [];
  return Array.isArray(value) ? value : [value];
}

/**
 * Explains an evaluation condition by condition — powers the builder's rule tester so an
 * editor can see *why* a section did or did not match.
 */
export interface Trace {
  facet: string;
  operator: 'IN' | 'NOT IN';
  expected: string[];
  actual: string[];
  result: boolean;
}

export function trace(
  expression: Expression,
  audience: Audience,
  options: EvaluateOptions = {},
): Trace[] {
  const policy = options.absentFacetPolicy ?? 'strict';
  const out: Trace[] = [];

  const walk = (node: ExpressionNode): void => {
    if (isCondition(node)) {
      const actual = normalize(audience[node.facet]);
      out.push({
        facet: node.facet,
        operator: node.operator,
        expected: node.values,
        actual,
        result: evaluateCondition(node, { audience, policy }),
      });
      return;
    }
    if (isNot(node)) return walk(node.operand);
    if (isBinary(node)) node.operands.forEach(walk);
  };

  if (expression) walk(expression);
  return out;
}
