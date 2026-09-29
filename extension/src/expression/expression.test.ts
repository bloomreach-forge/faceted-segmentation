/**
 * Unit tests for the grammar layer.
 *
 * Facet names and values here are drawn from the value lists the plugin itself ships
 * (Day of the Week, Signed In, Size), so the examples are ones a fresh install can actually
 * author. Structural tests that are about tree shape rather than vocabulary use abstract
 * `A IN [1]` placeholders, which keeps the assertion about precedence rather than about data.
 */

import { describe, expect, it } from 'vitest';
import { and, condition, countConditions, facetsUsed, not, or } from './ast';
import { evaluate } from './evaluate';
import { formatExpression } from './format';
import { ParseError, parseExpression } from './parse';
import { validateAgainstCatalog } from './validate';
import type { FacetCatalog } from '../catalog/types';

/** Round-trip: parse -> format -> parse must reach a fixed point. */
const roundTrip = (src: string) => {
  const once = formatExpression(parseExpression(src));
  const twice = formatExpression(parseExpression(once));
  expect(twice, 'formatting must be idempotent').toBe(once);
  return once;
};

describe('parse: conditions', () => {
  it('parses a single condition', () => {
    expect(parseExpression('Size IN [Small, Medium]')).toEqual(
      condition('Size', 'IN', ['Small', 'Medium']),
    );
  });

  it('parses multi-word facet names', () => {
    expect(parseExpression('Day of the Week IN [Monday]')).toEqual(
      condition('Day of the Week', 'IN', ['Monday']),
    );
  });

  it('parses NOT IN', () => {
    expect(parseExpression('Size NOT IN [Small]')).toEqual(condition('Size', 'NOT IN', ['Small']));
  });

  it('treats an empty string as ungated', () => {
    expect(parseExpression('')).toBeNull();
    expect(parseExpression('   \n  ')).toBeNull();
  });
});

describe('parse: tokenizing hazards', () => {
  // The grammar leaves values unquoted and facet names unbracketed, so tokenizing is
  // context-sensitive. These are the cases that makes it safe.

  it('matches keywords only as standalone uppercase words', () => {
    // "Signed In" contains "In", and is both a facet name and one of its own labels. It must
    // never be read as the IN keyword: the scan requires word boundaries AND uppercase.
    expect(parseExpression('Signed In IN [Signed In]')).toEqual(
      condition('Signed In', 'IN', ['Signed In']),
    );
  });

  it('reads a facet name greedily, up to the operator', () => {
    // Nothing terminates "Day of the Week" but the operator keyword — there is no delimiter.
    expect(parseExpression('Day of the Week NOT IN [Saturday, Sunday]')).toEqual(
      condition('Day of the Week', 'NOT IN', ['Saturday', 'Sunday']),
    );
  });

  it('consumes bracket contents whole, before considering grouping', () => {
    const src = 'Signed In IN [Signed In] AND Size IN [Large]';
    expect(parseExpression(src)).toEqual(
      and(condition('Signed In', 'IN', ['Signed In']), condition('Size', 'IN', ['Large'])),
    );
  });

  it('preserves interior spaces in a label but trims the edges', () => {
    expect(parseExpression('Signed In IN [  Signed Out  ]')).toEqual(
      condition('Signed In', 'IN', ['Signed Out']),
    );
  });
});

describe('parse: precedence and nesting', () => {
  it('binds AND tighter than OR', () => {
    // a OR b AND c  ==  a OR (b AND c)
    const node = parseExpression('Size IN [Large] OR Size IN [Small] AND Signed In IN [Signed In]');
    expect(node).toEqual(
      or(
        condition('Size', 'IN', ['Large']),
        and(condition('Size', 'IN', ['Small']), condition('Signed In', 'IN', ['Signed In'])),
      ),
    );
  });

  it('honours explicit parentheses', () => {
    const node = parseExpression(
      'Signed In IN [Signed In] AND (Size IN [Large] OR Day of the Week IN [Saturday])',
    );
    expect(node).toEqual(
      and(
        condition('Signed In', 'IN', ['Signed In']),
        or(
          condition('Size', 'IN', ['Large']),
          condition('Day of the Week', 'IN', ['Saturday']),
        ),
      ),
    );
  });

  it('flattens same-operator chains', () => {
    const node = parseExpression('A IN [1] AND B IN [2] AND C IN [3]');
    expect(node).toMatchObject({ type: 'binary', operator: 'AND' });
    expect((node as any).operands).toHaveLength(3);
  });

  it('parses arbitrary depth', () => {
    const src = '((A IN [1] OR B IN [2]) AND C IN [3]) OR D IN [4]';
    expect(roundTrip(src)).toBeTruthy();
  });
});

describe('parse: NOT (D5 group negation)', () => {
  it('parses NOT before a group', () => {
    const node = parseExpression('NOT (Size IN [Small] AND Day of the Week IN [Sunday])');
    expect(node).toEqual(
      not(and(condition('Size', 'IN', ['Small']), condition('Day of the Week', 'IN', ['Sunday']))),
    );
  });

  it('distinguishes NOT-group from NOT IN', () => {
    const node = parseExpression('Size IN [Large] AND NOT (Day of the Week IN [Sunday] AND A IN [1])');
    expect(node).toMatchObject({ type: 'binary', operator: 'AND' });
    expect((node as any).operands[1].type).toBe('not');
  });

  it('folds NOT over a bare condition into NOT IN', () => {
    expect(not(condition('Size', 'IN', ['Small']))).toEqual(condition('Size', 'NOT IN', ['Small']));
  });

  it('collapses double negation', () => {
    const inner = and(condition('A', 'IN', ['1']), condition('B', 'IN', ['2']));
    expect(not(not(inner))).toEqual(inner);
  });
});

describe('parse: errors', () => {
  it.each([
    ['Size [Small]', 'missing operator'],
    ['Size IN Small', 'missing brackets'],
    ['Size IN [Small', 'unclosed bracket'],
    ['(Size IN [Small]', 'unclosed paren'],
    ['Size IN []', 'empty value list'],
    ['AND Size IN [Small]', 'leading operator'],
    ['Size IN [Small] AND', 'trailing operator'],
  ])('rejects %j (%s)', (src) => {
    expect(() => parseExpression(src)).toThrow(ParseError);
  });

  it('reports a position and a caret diagram', () => {
    try {
      parseExpression('Size IN [Small] AND Day of the Week [Sunday]');
      expect.unreachable();
    } catch (e) {
      expect(e).toBeInstanceOf(ParseError);
      const err = e as ParseError;
      expect(err.position).toBeGreaterThan(0);
      expect(err.describe()).toContain('^');
    }
  });
});

describe('format: canonical output', () => {
  it('formats a single condition on one line', () => {
    expect(formatExpression(condition('Size', 'IN', ['Small', 'Medium', 'Large']))).toBe(
      'Size IN [Small, Medium, Large]',
    );
  });

  it('formats a two-condition AND, the commonest real shape', () => {
    const node = and(
      condition('Size', 'IN', ['Medium', 'Large']),
      condition('Signed In', 'IN', ['Signed In']),
    );
    expect(formatExpression(node)).toBe(
      'Size IN [Medium, Large]\n  AND Signed In IN [Signed In]',
    );
  });

  it('states a shared condition once, with an OR branch nested (D4)', () => {
    const node = and(
      condition('Signed In', 'IN', ['Signed In']),
      or(
        condition('Size', 'IN', ['Large']),
        condition('Day of the Week', 'IN', ['Saturday', 'Sunday']),
      ),
    );
    const out = formatExpression(node);
    expect(out).toContain('AND (');
    expect(out).toContain('OR ');
    // The shared condition appears exactly once — the whole point of D4.
    expect(out.match(/Signed In IN/g)).toHaveLength(1);
    expect(roundTrip(out)).toBe(out);
  });

  it('wraps a wide value list', () => {
    const node = condition('Day of the Week', 'IN', [
      'Sunday',
      'Monday',
      'Tuesday',
      'Wednesday',
      'Thursday',
      'Friday',
      'Saturday',
    ]);
    const out = formatExpression(node);
    expect(out.split('\n').length).toBeGreaterThan(1);
    expect(out).toContain('[');
    expect(out).toContain(']');
    expect(roundTrip(out)).toBe(out);
  });

  it('parenthesizes OR inside AND but not AND inside OR', () => {
    const orInAnd = and(condition('A', 'IN', ['1']), or(condition('B', 'IN', ['2']), condition('C', 'IN', ['3'])));
    expect(formatExpression(orInAnd, { inline: true })).toBe('A IN [1] AND (B IN [2] OR C IN [3])');

    const andInOr = or(condition('A', 'IN', ['1']), and(condition('B', 'IN', ['2']), condition('C', 'IN', ['3'])));
    expect(formatExpression(andInOr, { inline: true })).toBe('A IN [1] OR B IN [2] AND C IN [3]');
  });

  it('emits an empty string for an ungated expression', () => {
    expect(formatExpression(null)).toBe('');
  });
});

describe('round-trip stability', () => {
  it.each([
    'Size IN [Small, Medium, Large]',
    'Day of the Week IN [Saturday, Sunday] AND Size NOT IN [Small]',
    'Size NOT IN [Small]',
    'Signed In IN [Signed In] AND (Size IN [Large] OR Day of the Week IN [Saturday, Sunday])',
    'Size IN [Medium, Large] AND NOT (Signed In IN [Signed Out] AND Day of the Week IN [Sunday])',
    '((A IN [1] OR B IN [2]) AND C IN [3]) OR NOT (D IN [4] AND E IN [5])',
    'Day of the Week IN [Monday, Tuesday, Wednesday, Thursday, Friday]',
    'Signed In IN [Signed In, Signed Out]',
  ])('is stable and semantics-preserving for %j', (src) => {
    const formatted = roundTrip(src);
    // Formatting must not change meaning.
    expect(parseExpression(formatted)).toEqual(parseExpression(src));
  });
});

describe('evaluate', () => {
  const expr = parseExpression(
    'Size IN [Medium, Large] AND (Signed In IN [Signed In] OR Day of the Week IN [Saturday])',
  );

  it('matches when AND and one OR branch hold', () => {
    expect(evaluate(expr, { Size: ['Medium'], 'Signed In': ['Signed In'] })).toBe(true);
    expect(evaluate(expr, { Size: ['Large'], 'Day of the Week': ['Saturday'] })).toBe(true);
  });

  it('fails when the AND side fails', () => {
    expect(evaluate(expr, { Size: ['Small'], 'Signed In': ['Signed In'] })).toBe(false);
  });

  it('fails when neither OR branch holds', () => {
    expect(evaluate(expr, { Size: ['Large'], 'Signed In': ['Signed Out'] })).toBe(false);
  });

  it('treats IN as intersection, not subset', () => {
    // A visitor holding both Small and Large still matches Size IN [Large].
    const c = parseExpression('Size IN [Large]');
    expect(evaluate(c, { Size: ['Small', 'Large'] })).toBe(true);
  });

  it('an ungated expression always matches', () => {
    expect(evaluate(null, {})).toBe(true);
  });

  it('ignores facets the expression does not mention', () => {
    const c = parseExpression('Size IN [Large]');
    expect(evaluate(c, { Size: ['Large'], 'Day of the Week': ['Sunday'] })).toBe(true);
  });

  it('handles NOT IN', () => {
    const c = parseExpression('Size NOT IN [Small]');
    expect(evaluate(c, { Size: ['Large'] })).toBe(true);
    expect(evaluate(c, { Size: ['Small'] })).toBe(false);
  });

  describe('absent-facet policy (D11)', () => {
    const notIn = parseExpression('Size NOT IN [Small]');
    const isIn = parseExpression('Size IN [Small]');

    it('defaults to strict — an absent facet fails NOT IN', () => {
      expect(evaluate(notIn, {})).toBe(false);
    });

    it('permissive lets an absent facet pass NOT IN', () => {
      expect(evaluate(notIn, {}, { absentFacetPolicy: 'permissive' })).toBe(true);
    });

    it('IN always fails when the facet is absent, under either policy', () => {
      expect(evaluate(isIn, {}, { absentFacetPolicy: 'strict' })).toBe(false);
      expect(evaluate(isIn, {}, { absentFacetPolicy: 'permissive' })).toBe(false);
    });

    it('treats an empty array the same as an absent key', () => {
      expect(evaluate(notIn, { Size: [] })).toBe(false);
      expect(evaluate(notIn, { Size: [] }, { absentFacetPolicy: 'permissive' })).toBe(true);
    });

    it('policy is irrelevant once the facet has a value', () => {
      for (const policy of ['strict', 'permissive'] as const) {
        expect(evaluate(notIn, { Size: ['Large'] }, { absentFacetPolicy: policy })).toBe(true);
        expect(evaluate(notIn, { Size: ['Small'] }, { absentFacetPolicy: policy })).toBe(false);
      }
    });
  });

  it('negates a group', () => {
    const c = parseExpression('NOT (Size IN [Large] AND Day of the Week IN [Sunday])');
    expect(evaluate(c, { Size: ['Large'], 'Day of the Week': ['Sunday'] })).toBe(false);
    expect(evaluate(c, { Size: ['Large'], 'Day of the Week': ['Monday'] })).toBe(true);
  });
});

describe('validate against catalog', () => {
  const catalog: FacetCatalog = {
    etag: 'test',
    generatedAt: '2026-09-11T00:00:00Z',
    facets: [
      {
        id: 'size',
        name: 'Size',
        values: [
          { key: 'S', label: 'Small' },
          { key: 'M', label: 'Medium' },
          { key: 'L', label: 'Large' },
        ],
      },
      { id: 'signedin', name: 'Signed In', values: [{ key: 'in', label: 'Signed In' }] },
    ],
  };

  it('accepts a valid expression', () => {
    const node = parseExpression('Size IN [Small, Medium] AND Signed In IN [Signed In]');
    expect(validateAgainstCatalog(node, catalog)).toEqual([]);
  });

  it('flags an unknown facet', () => {
    const node = parseExpression('Nonexistent Facet IN [x]');
    const issues = validateAgainstCatalog(node, catalog);
    expect(issues).toHaveLength(1);
    expect(issues[0].kind).toBe('unknown-facet');
  });

  it('flags a stale value rather than dropping it', () => {
    // Simulates a label renamed in the value list after the expression was stored.
    const node = parseExpression('Size IN [Small, Smaller]');
    const issues = validateAgainstCatalog(node, catalog);
    expect(issues).toHaveLength(1);
    expect(issues[0].kind).toBe('unknown-value');
    expect(issues[0].value).toBe('Smaller');
  });

  it('warns on duplicates', () => {
    const node = parseExpression('Size IN [Small, Small]');
    const issues = validateAgainstCatalog(node, catalog);
    expect(issues.some((i) => i.kind === 'duplicate-value')).toBe(true);
  });

  it('matches facets by display name, not by id', () => {
    // D3: the token in an expression is the value list's hippo:name. Using the id must fail.
    expect(validateAgainstCatalog(parseExpression('signedin IN [Signed In]'), catalog)).toHaveLength(
      1,
    );
  });
});

describe('ast helpers', () => {
  it('lists facets in first-seen order', () => {
    const node = parseExpression('Size IN [Large] AND (Signed In IN [Signed In] OR Size IN [Medium])');
    expect(facetsUsed(node)).toEqual(['Size', 'Signed In']);
  });

  it('counts conditions', () => {
    const node = parseExpression('A IN [1] AND (B IN [2] OR NOT (C IN [3] AND D IN [4]))');
    expect(countConditions(node)).toBe(4);
  });
});
