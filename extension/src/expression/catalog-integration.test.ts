/**
 * Integration tests against the catalog the plugin actually ships.
 *
 * The fixture (`npm run catalog:generate`) is generated from the plugin's own bootstrapped Value
 * List YAML in `repository/` — Day of the Week, Signed In, Size. Asserting against the shipped
 * content, rather than a hand-written fixture, means these tests fail if the example value lists
 * and the grammar ever disagree: a label a fresh install offers but the parser cannot read would
 * make an editor's stored rule unreadable.
 *
 * Scope note: three small lists exercise the grammar's *structure* (multi-word facet names,
 * key≠label, whole-facet selections) but not its adversarial cases — labels containing
 * parentheses, `&`, `®`, `/`, or lists of 50+ values. The unit suites in `expression.test.ts`
 * cover tokenizing directly; see the README's grammar constraints for what a consuming project
 * must still validate in its own value lists.
 */

import { describe, expect, it } from 'vitest';
import catalogJson from '../../test/fixtures/facets.json';
import type { FacetCatalog } from '../catalog/types';
import { and, condition, or } from './ast';
import { evaluate } from './evaluate';
import { formatExpression } from './format';
import { parseExpression } from './parse';
import { validateAgainstCatalog } from './validate';

const catalog = catalogJson as FacetCatalog;

describe('shipped catalog shape', () => {
  it('has the facets the repository module bootstraps', () => {
    expect(catalog.facets).toHaveLength(3);
    const names = catalog.facets.map((f) => f.name);
    expect(names).toEqual(['Day of the Week', 'Signed In', 'Size']);
  });

  it('carries the full value sets', () => {
    const total = catalog.facets.reduce((n, f) => n + f.values.length, 0);
    expect(total).toBe(12);
    expect(catalog.facets.find((f) => f.id === 'day-of-the-week')!.values).toHaveLength(7);
    expect(catalog.facets.find((f) => f.id === 'signedin')!.values).toHaveLength(2);
    expect(catalog.facets.find((f) => f.id === 'size')!.values).toHaveLength(3);
  });

  it('every facet id is the value list node name, and every name its hippo:name', () => {
    // D3: the facet TOKEN in an expression is the display name, while `facets` in
    // frontend:config allow-lists by id. Conflating the two is an easy configuration error.
    const byId = new Map(catalog.facets.map((f) => [f.id, f.name]));
    expect(byId.get('day-of-the-week')).toBe('Day of the Week');
    expect(byId.get('signedin')).toBe('Signed In');
  });

  it('stores labels distinct from keys, so labels are what expressions carry', () => {
    // D2: every shipped value has key ≠ label, so a test that passed a key where a label
    // belongs would fail rather than accidentally agree.
    const everyValue = catalog.facets.flatMap((f) => f.values);
    expect(everyValue.every((v) => v.key !== v.label)).toBe(true);
    const size = catalog.facets.find((f) => f.id === 'size')!;
    expect(size.values.map((v) => v.key)).toEqual(['S', 'M', 'L']);
    expect(size.values.map((v) => v.label)).toEqual(['Small', 'Medium', 'Large']);
  });
});

describe('grammar safety across every shipped label', () => {
  const everyLabel = catalog.facets.flatMap((f) =>
    f.values.map((v) => ({ facet: f.name, label: v.label })),
  );

  it('covers every value in the catalog', () => {
    expect(everyLabel.length).toBe(12);
  });

  it('no label contains a comma or bracket, so values need no quoting', () => {
    const unsafe = everyLabel.filter(
      ({ label }) => label.includes(',') || label.includes('[') || label.includes(']'),
    );
    expect(unsafe).toEqual([]);
  });

  it('no facet display name collides with a grammar keyword', () => {
    const colliding = catalog.facets.filter((f) => /\b(AND|OR|NOT|IN)\b/.test(f.name));
    expect(colliding).toEqual([]);
  });

  it('parses a single-value condition for every shipped label', () => {
    const failures: string[] = [];
    for (const { facet, label } of everyLabel) {
      const src = `${facet} IN [${label}]`;
      try {
        const node = parseExpression(src);
        expect(node).toEqual(condition(facet, 'IN', [label]));
      } catch (error) {
        failures.push(`${src} -> ${(error as Error).message}`);
      }
    }
    expect(failures).toEqual([]);
  });

  it('round-trips a whole-facet selection for every facet', () => {
    for (const facet of catalog.facets) {
      const labels = facet.values.map((v) => v.label);
      const node = condition(facet.name, 'IN', labels);
      const formatted = formatExpression(node);
      expect(parseExpression(formatted), `${facet.name} must round-trip`).toEqual(node);
      // And formatting is a fixed point.
      expect(formatExpression(parseExpression(formatted))).toBe(formatted);
    }
  });

  it('validates a whole-facet selection against the catalog with no issues', () => {
    for (const facet of catalog.facets) {
      const node = condition(
        facet.name,
        'IN',
        facet.values.map((v) => v.label),
      );
      expect(validateAgainstCatalog(node, catalog), `${facet.name}`).toEqual([]);
    }
  });

  it('reads a multi-word facet name greedily, up to the operator keyword', () => {
    // "Day of the Week" is the shipped case for the context-sensitive tokenizing the grammar
    // needs: a facet name contains spaces, so it can only be terminated by IN / NOT IN.
    const node = parseExpression('Day of the Week NOT IN [Saturday, Sunday]');
    expect(node).toEqual(condition('Day of the Week', 'NOT IN', ['Saturday', 'Sunday']));
  });

  it('tolerates a label that is also a facet name', () => {
    // The "Signed In" value list has a label identical to its own display name, and one
    // ("Signed In") whose words include the IN keyword as a substring — neither may confuse
    // tokenizing, because bracket contents are consumed whole.
    const node = parseExpression('Signed In IN [Signed In]');
    expect(node).toEqual(condition('Signed In', 'IN', ['Signed In']));
    expect(formatExpression(node)).toBe('Signed In IN [Signed In]');
    expect(parseExpression(formatExpression(node))).toEqual(node);
  });
});

describe('realistic rules over the shipped facets', () => {
  it('expresses a shared condition with an OR branch', () => {
    // The compactness claim behind D4: the shared condition is stated once.
    const node = and(
      condition('Signed In', 'IN', ['Signed In']),
      or(
        condition('Size', 'IN', ['Large']),
        condition('Day of the Week', 'IN', ['Saturday', 'Sunday']),
      ),
    );
    expect(validateAgainstCatalog(node, catalog)).toEqual([]);

    const formatted = formatExpression(node);
    expect(formatted.match(/Signed In IN/g)).toHaveLength(1);
    expect(parseExpression(formatted)).toEqual(node);

    expect(evaluate(node, { 'Signed In': ['Signed In'], Size: ['Large'] })).toBe(true);
    expect(
      evaluate(node, { 'Signed In': ['Signed In'], 'Day of the Week': ['Sunday'] }),
    ).toBe(true);
    expect(evaluate(node, { 'Signed In': ['Signed In'], Size: ['Small'] })).toBe(false);
    expect(evaluate(node, { Size: ['Large'] })).toBe(false);
  });

  it('expresses an IN combined with a NOT IN', () => {
    const node = and(
      condition('Size', 'IN', ['Medium', 'Large']),
      condition('Day of the Week', 'NOT IN', ['Sunday']),
    );
    expect(validateAgainstCatalog(node, catalog)).toEqual([]);
    expect(formatExpression(node)).toBe(
      'Size IN [Medium, Large]\n  AND Day of the Week NOT IN [Sunday]',
    );
    expect(evaluate(node, { Size: ['Medium'], 'Day of the Week': ['Monday'] })).toBe(true);
    expect(evaluate(node, { Size: ['Medium'], 'Day of the Week': ['Sunday'] })).toBe(false);
  });

  it('expresses a bare exclusion', () => {
    const node = condition('Size', 'NOT IN', ['Small']);
    expect(validateAgainstCatalog(node, catalog)).toEqual([]);
    expect(formatExpression(node)).toBe('Size NOT IN [Small]');
    expect(evaluate(node, { Size: ['Large'] })).toBe(true);
    expect(evaluate(node, { Size: ['Small'] })).toBe(false);
  });

  it('an ungated expression matches every audience', () => {
    expect(evaluate(parseExpression(''), {})).toBe(true);
    expect(evaluate(parseExpression(''), { Size: ['Small'] })).toBe(true);
  });
});

describe('inverting a majority selection (D11 interaction)', () => {
  // Selecting most of a facet's values is shorter to express as NOT IN over the remainder.
  // That rewrite is audience-equivalent whenever the request carries a value for the facet —
  // and diverges when it does not, which is what absentFacetPolicy governs.
  const weekdays = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday'];
  const weekend = ['Saturday', 'Sunday'];
  const asIn = condition('Day of the Week', 'IN', weekdays);
  const asNotIn = condition('Day of the Week', 'NOT IN', weekend);

  it('agrees with the IN form for every real value, under both policies', () => {
    const allDays = catalog.facets
      .find((f) => f.id === 'day-of-the-week')!
      .values.map((v) => v.label);
    expect(allDays).toHaveLength(7);

    for (const policy of ['strict', 'permissive'] as const) {
      for (const label of allDays) {
        const audience = { 'Day of the Week': [label] };
        expect(
          evaluate(asIn, audience, { absentFacetPolicy: policy }),
          `${label} @ ${policy}`,
        ).toBe(evaluate(asNotIn, audience, { absentFacetPolicy: policy }));
      }
    }
  });

  it('is materially shorter, which is the point', () => {
    expect(formatExpression(asNotIn).length).toBeLessThan(formatExpression(asIn).length);
  });

  it('is only equivalent under STRICT when the facet is absent', () => {
    //   strict     -> IN fails, NOT IN fails      => equivalent
    //   permissive -> IN fails, NOT IN SUCCEEDS   => diverges
    //
    // So a project on `permissive` must either skip the inversion or accept that inverted
    // rules widen to include audiences carrying no value for that facet.
    expect(evaluate(asIn, {}, { absentFacetPolicy: 'strict' })).toBe(false);
    expect(evaluate(asNotIn, {}, { absentFacetPolicy: 'strict' })).toBe(false);

    expect(evaluate(asIn, {}, { absentFacetPolicy: 'permissive' })).toBe(false);
    expect(evaluate(asNotIn, {}, { absentFacetPolicy: 'permissive' })).toBe(true);
  });
});

describe('stale label handling (D2 risk)', () => {
  it('flags a renamed label instead of dropping it', () => {
    // Simulates someone renaming "Small" in the value list after rules were stored.
    const node = condition('Size', 'IN', ['Small', 'Extra Small']);
    const issues = validateAgainstCatalog(node, catalog);
    expect(issues).toHaveLength(1);
    expect(issues[0]).toMatchObject({ kind: 'unknown-value', value: 'Extra Small' });
    // The value survives in the expression — a silent drop would widen the audience.
    expect(formatExpression(node)).toContain('Extra Small');
  });

  it('flags a renamed facet, not just a renamed value', () => {
    const node = condition('Shirt Size', 'IN', ['Small']);
    const issues = validateAgainstCatalog(node, catalog);
    expect(issues).toHaveLength(1);
    expect(issues[0]).toMatchObject({ kind: 'unknown-facet' });
  });
});
