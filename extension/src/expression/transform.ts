/**
 * Immutable structural edits on the AST, addressed by index path.
 *
 * The builder UI is a thin shell over these — keeping them pure and framework-free means the
 * tree semantics are unit-testable without rendering anything, and the same operations can be
 * reused by tooling later.
 */

import {
  type Expression,
  type ExpressionNode,
  binary,
  condition,
  isBinary,
  isCondition,
  isNot,
  not,
} from './ast';

export type Path = number[];

/** Reads the node at `path`, or undefined if the path does not resolve. */
export function nodeAt(root: Expression, path: Path): ExpressionNode | undefined {
  let current: ExpressionNode | undefined = root ?? undefined;
  for (const index of path) {
    if (!current) return undefined;
    if (isBinary(current)) current = current.operands[index];
    else if (isNot(current)) current = index === 0 ? current.operand : undefined;
    else return undefined;
  }
  return current;
}

/**
 * Rebuilds the tree with `path` replaced by `next`. Passing `null` deletes the node, which
 * collapses its parent when only one operand remains — that collapse is what keeps the tree
 * from accumulating single-child groups as an editor removes conditions.
 */
export function replaceAt(
  root: Expression,
  path: Path,
  next: ExpressionNode | null,
): Expression {
  if (path.length === 0) return next;
  if (!root) return root;

  const [index, ...rest] = path;

  if (isBinary(root)) {
    const child = root.operands[index];
    if (!child) return root;

    const updated = rest.length === 0 ? next : replaceAt(child, rest, next);
    const operands = [...root.operands];

    if (updated === null) {
      operands.splice(index, 1);
    } else {
      operands[index] = updated;
    }

    if (operands.length === 0) return null;
    if (operands.length === 1) return operands[0];
    return binary(root.operator, operands);
  }

  if (isNot(root)) {
    if (index !== 0) return root;
    const updated = rest.length === 0 ? next : replaceAt(root.operand, rest, next);
    if (updated === null) return null;
    return not(updated);
  }

  return root;
}

/**
 * Inserts a new blank condition next to `path`, joined by `operator`.
 *
 * When the sibling's parent already uses that operator the node joins the existing chain.
 * Otherwise the target is wrapped in a new two-operand node of the requested operator, which
 * is what lets an editor introduce an OR inside an AND chain without a separate "group" step.
 */
export function addSibling(
  root: Expression,
  path: Path,
  operator: 'AND' | 'OR',
  blank: ExpressionNode,
): Expression {
  if (!root) return blank;

  const parentPath = path.slice(0, -1);
  const index = path[path.length - 1];
  const parent = path.length === 0 ? undefined : nodeAt(root, parentPath);

  if (parent && isBinary(parent) && parent.operator === operator) {
    const operands = [...parent.operands];
    operands.splice(index + 1, 0, blank);
    return replaceAt(root, parentPath, binary(operator, operands));
  }

  const target = nodeAt(root, path);
  if (!target) return root;
  return replaceAt(root, path, binary(operator, [target, blank]));
}

/** Wraps the node at `path` in a group so further nesting can hang off it. */
export function wrapInGroup(root: Expression, path: Path, blank: ExpressionNode): Expression {
  const target = nodeAt(root, path);
  if (!target) return root;
  return replaceAt(root, path, binary('AND', [target, blank]));
}

/**
 * Toggles negation at `path`. On a condition this flips IN/NOT IN; on a group it adds or
 * removes a NOT wrapper (D5).
 */
export function toggleNegate(root: Expression, path: Path): Expression {
  const target = nodeAt(root, path);
  if (!target) return root;
  return replaceAt(root, path, not(target));
}

/** A fresh, empty condition for the first available facet. */
export function blankCondition(facetName: string): ExpressionNode {
  return condition(facetName, 'IN', []);
}

/**
 * Drops conditions with no values selected. The builder allows them transiently while an
 * editor is choosing values, but they must never reach the stored string — `Facet IN []` is
 * not valid per the grammar.
 */
export function pruneEmpty(root: Expression): Expression {
  if (!root) return null;

  if (isBinary(root)) {
    const kept = root.operands
      .map(pruneEmpty)
      .filter((n): n is ExpressionNode => n !== null);
    if (kept.length === 0) return null;
    if (kept.length === 1) return kept[0];
    return binary(root.operator, kept);
  }

  if (isNot(root)) {
    const inner = pruneEmpty(root.operand);
    return inner === null ? null : not(inner);
  }

  return root.values.length === 0 ? null : root;
}

/** True when the tree holds a condition with no values — used to gate the Apply button. */
export function hasEmptyCondition(root: Expression): boolean {
  if (!root) return false;
  if (isBinary(root)) return root.operands.some(hasEmptyCondition);
  if (isNot(root)) return hasEmptyCondition(root.operand);
  return root.values.length === 0;
}

// ---------------------------------------------------------------------------
// Which facets are already spoken for inside a group
// ---------------------------------------------------------------------------

/**
 * Facets used by the **direct children** of the group at `path` — deliberately not recursive.
 *
 * The builder uses this to stop an editor picking the same facet twice inside one group, which
 * only ever produces noise:
 *
 *     Size IN [Small] OR Size IN [Large]
 *
 * …should have been one condition holding both values. Nested groups are excluded from the set
 * because the same facet at a *different* nesting level is meaningful — `Size IN [Large] AND
 * (Size NOT IN [Small] OR ...)` is a legitimate shape, so only immediate siblings are blocked.
 *
 * A `NOT`-wrapped group is unwrapped first, since the editor renders the inner chain.
 */
export function facetsInGroup(root: Expression, path: Path): Set<string> {
  const node = path.length === 0 ? (root ?? undefined) : nodeAt(root, path);
  const used = new Set<string>();
  if (!node) return used;

  const group = isNot(node) ? node.operand : node;
  if (!isBinary(group)) {
    if (isCondition(group)) used.add(group.facet);
    return used;
  }
  for (const operand of group.operands) {
    if (isCondition(operand)) used.add(operand.facet);
  }
  return used;
}

/**
 * Facets already present in the group that `addSibling(root, path, operator)` would drop a new
 * node into. Mirrors `addSibling`'s own branching, so the two cannot disagree:
 *
 * - the sibling's parent already uses `operator` → the new node joins that chain, so everything
 *   currently in it is taken;
 * - otherwise the target is wrapped in a fresh two-operand group → only the target's own facet
 *   is taken.
 *
 * Used to seed a new condition with a facet nobody in the destination is using yet.
 */
export function facetsInDestinationGroup(
  root: Expression,
  path: Path,
  operator: 'AND' | 'OR',
): Set<string> {
  if (!root) return new Set();

  const parentPath = path.slice(0, -1);
  const parent = path.length === 0 ? undefined : nodeAt(root, parentPath);

  if (parent && isBinary(parent) && parent.operator === operator) {
    return facetsInGroup(root, parentPath);
  }

  const used = new Set<string>();
  const target = nodeAt(root, path);
  if (target && isCondition(target)) used.add(target.facet);
  return used;
}

/**
 * Picks a facet for a new blank condition: the first one not already used in `taken`.
 * Falls back to the first facet so the builder still functions when every facet is spoken for —
 * the UI disables the add controls in that case, this is only a backstop.
 */
export function nextUnusedFacet(
  facetNames: string[],
  taken: ReadonlySet<string>,
): string {
  return facetNames.find((n) => !taken.has(n)) ?? facetNames[0] ?? '';
}
