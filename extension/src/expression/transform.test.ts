import { describe, expect, it } from 'vitest';
import { and, condition, or } from './ast';
import { formatExpression } from './format';
import { parseExpression } from './parse';
import {
  addSibling,
  blankCondition,
  facetsInDestinationGroup,
  facetsInGroup,
  nextUnusedFacet,
  hasEmptyCondition,
  nodeAt,
  pruneEmpty,
  replaceAt,
  toggleNegate,
  wrapInGroup,
} from './transform';

const inline = (e: ReturnType<typeof parseExpression>) => formatExpression(e, { inline: true });

describe('nodeAt', () => {
  const root = parseExpression('A IN [1] AND (B IN [2] OR C IN [3])');

  it('resolves the root', () => {
    expect(nodeAt(root, [])).toBe(root);
  });

  it('resolves nested paths', () => {
    expect(nodeAt(root, [0])).toEqual(condition('A', 'IN', ['1']));
    expect(nodeAt(root, [1, 1])).toEqual(condition('C', 'IN', ['3']));
  });

  it('returns undefined for a bad path', () => {
    expect(nodeAt(root, [9])).toBeUndefined();
    expect(nodeAt(root, [0, 0])).toBeUndefined();
  });
});

describe('replaceAt', () => {
  it('replaces a nested node', () => {
    const root = parseExpression('A IN [1] AND B IN [2]');
    const next = replaceAt(root, [1], condition('Z', 'IN', ['9']));
    expect(inline(next)).toBe('A IN [1] AND Z IN [9]');
  });

  it('deletes a node when passed null', () => {
    const root = parseExpression('A IN [1] AND B IN [2] AND C IN [3]');
    expect(inline(replaceAt(root, [1], null))).toBe('A IN [1] AND C IN [3]');
  });

  it('collapses a group left with one operand', () => {
    const root = parseExpression('A IN [1] AND B IN [2]');
    expect(inline(replaceAt(root, [1], null))).toBe('A IN [1]');
  });

  it('collapses nested groups recursively', () => {
    const root = parseExpression('A IN [1] AND (B IN [2] OR C IN [3])');
    const next = replaceAt(root, [1, 1], null);
    expect(inline(next)).toBe('A IN [1] AND B IN [2]');
  });

  it('returns null when everything is removed', () => {
    const root = parseExpression('A IN [1]');
    expect(replaceAt(root, [], null)).toBeNull();
  });
});

describe('addSibling', () => {
  it('joins an existing chain of the same operator', () => {
    const root = parseExpression('A IN [1] AND B IN [2]');
    const next = addSibling(root, [0], 'AND', condition('Z', 'IN', ['9']));
    expect(inline(next)).toBe('A IN [1] AND Z IN [9] AND B IN [2]');
  });

  it('wraps in a new node when the operator differs', () => {
    const root = parseExpression('A IN [1] AND B IN [2]');
    const next = addSibling(root, [1], 'OR', condition('Z', 'IN', ['9']));
    expect(inline(next)).toBe('A IN [1] AND (B IN [2] OR Z IN [9])');
  });

  it('builds Example 1 from a single condition', () => {
    let expr = parseExpression('Size IN [Small, Medium, Large]');
    expr = addSibling(expr, [], 'AND', condition('Signed In', 'IN', ['Signed In']));
    expr = addSibling(expr, [1], 'OR', condition('Day of the Week', 'IN', ['Saturday', 'Sunday']));
    expect(inline(expr)).toBe(
      'Size IN [Small, Medium, Large] AND (Signed In IN [Signed In] OR Day of the Week IN [Saturday, Sunday])',
    );
    expect(parseExpression(formatExpression(expr))).toEqual(expr);
  });

  it('seeds an empty tree', () => {
    expect(inline(addSibling(null, [], 'AND', condition('A', 'IN', ['1'])))).toBe('A IN [1]');
  });
});

describe('toggleNegate', () => {
  it('flips a condition operator instead of wrapping it', () => {
    const root = parseExpression('A IN [1]');
    expect(inline(toggleNegate(root, []))).toBe('A NOT IN [1]');
    expect(inline(toggleNegate(toggleNegate(root, []), []))).toBe('A IN [1]');
  });

  it('wraps a group in NOT (D5)', () => {
    const root = parseExpression('A IN [1] AND B IN [2]');
    expect(inline(toggleNegate(root, []))).toBe('NOT (A IN [1] AND B IN [2])');
  });

  it('unwraps a negated group', () => {
    const root = parseExpression('NOT (A IN [1] AND B IN [2])');
    expect(inline(toggleNegate(root, []))).toBe('A IN [1] AND B IN [2]');
  });

  it('negates a nested group only', () => {
    const root = parseExpression('A IN [1] AND (B IN [2] OR C IN [3])');
    expect(inline(toggleNegate(root, [1]))).toBe('A IN [1] AND NOT (B IN [2] OR C IN [3])');
  });
});

describe('wrapInGroup', () => {
  it('wraps a condition alongside a blank', () => {
    const root = parseExpression('A IN [1]');
    const next = wrapInGroup(root, [], blankCondition('Size'));
    expect(nodeAt(next, [])).toMatchObject({ type: 'binary', operator: 'AND' });
    expect(hasEmptyCondition(next)).toBe(true);
  });
});

describe('pruneEmpty', () => {
  it('drops valueless conditions', () => {
    const root = and(condition('A', 'IN', ['1']), condition('B', 'IN', []));
    expect(inline(pruneEmpty(root))).toBe('A IN [1]');
  });

  it('returns null when nothing survives', () => {
    expect(pruneEmpty(and(condition('A', 'IN', []), condition('B', 'IN', [])))).toBeNull();
  });

  it('collapses groups emptied by pruning', () => {
    const root = and(
      condition('A', 'IN', ['1']),
      or(condition('B', 'IN', []), condition('C', 'IN', ['3'])),
    );
    expect(inline(pruneEmpty(root))).toBe('A IN [1] AND C IN [3]');
  });

  it('never emits an invalid empty bracket list', () => {
    const root = and(condition('A', 'IN', ['1']), condition('B', 'IN', []));
    const formatted = formatExpression(pruneEmpty(root));
    expect(formatted).not.toContain('[]');
    expect(() => parseExpression(formatted)).not.toThrow();
  });
});

describe('hasEmptyCondition', () => {
  it('detects a blank anywhere in the tree', () => {
    expect(hasEmptyCondition(parseExpression('A IN [1]'))).toBe(false);
    expect(hasEmptyCondition(and(condition('A', 'IN', ['1']), condition('B', 'IN', [])))).toBe(true);
    expect(hasEmptyCondition(null)).toBe(false);
  });
});

describe('facetsInGroup', () => {
  it('reports the direct children of a group', () => {
    const root = parseExpression('A IN [1] AND B IN [2] AND C IN [3]');
    expect(facetsInGroup(root, [])).toEqual(new Set(['A', 'B', 'C']));
  });

  it('does NOT descend into nested groups', () => {
    const root = parseExpression('A IN [1] AND (B IN [2] OR C IN [3])');
    expect(facetsInGroup(root, [])).toEqual(new Set(['A']));
    expect(facetsInGroup(root, [1])).toEqual(new Set(['B', 'C']));
  });

  it('unwraps a NOT so the inner chain is inspected', () => {
    const root = parseExpression('NOT (A IN [1] AND B IN [2])');
    expect(facetsInGroup(root, [])).toEqual(new Set(['A', 'B']));
  });

  it('handles a lone condition and an empty tree', () => {
    expect(facetsInGroup(parseExpression('A IN [1]'), [])).toEqual(new Set(['A']));
    expect(facetsInGroup(null, [])).toEqual(new Set());
    expect(facetsInGroup(parseExpression('A IN [1]'), [5])).toEqual(new Set());
  });
});

describe('facetsInDestinationGroup', () => {
  it('returns the whole chain when the new node joins an existing same-operator group', () => {
    const root = parseExpression('A IN [1] AND B IN [2]');
    expect(facetsInDestinationGroup(root, [1], 'AND')).toEqual(new Set(['A', 'B']));
  });

  it('returns only the target when a new group will be created', () => {
    const root = parseExpression('A IN [1] AND B IN [2]');
    expect(facetsInDestinationGroup(root, [1], 'OR')).toEqual(new Set(['B']));
  });

  it('mirrors addSibling: the seeded facet is never a duplicate of a real sibling', () => {
    const root = parseExpression('A IN [1] AND B IN [2]');
    for (const operator of ['AND', 'OR'] as const) {
      const taken = facetsInDestinationGroup(root, [1], operator);
      const seed = nextUnusedFacet(['A', 'B', 'C'], taken);
      const next = addSibling(root, [1], operator, blankCondition(seed));
      const groupPath = operator === 'AND' ? [] : [1];
      const facetsThere = [...facetsInGroup(next, groupPath)];
      expect(new Set(facetsThere).size).toBe(facetsThere.length);
    }
  });

  it('is empty for an empty tree', () => {
    expect(facetsInDestinationGroup(null, [0], 'AND')).toEqual(new Set());
  });
});

describe('nextUnusedFacet', () => {
  it('returns the first available facet', () => {
    expect(nextUnusedFacet(['Size', 'Day of the Week'], new Set())).toBe('Size');
  });

  it('skips facets already taken in the destination group', () => {
    expect(nextUnusedFacet(['Size', 'Day of the Week'], new Set(['Size']))).toBe('Day of the Week');
  });

  it('falls back to the first facet when everything is taken', () => {
    expect(nextUnusedFacet(['Size', 'Day of the Week'], new Set(['Size', 'Day of the Week']))).toBe('Size');
  });

  it('returns an empty string when there are no facets at all', () => {
    expect(nextUnusedFacet([], new Set())).toBe('');
  });
});
