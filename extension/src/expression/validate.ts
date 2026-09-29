/**
 * Validates a parsed expression against the live facet catalog.
 *
 * Because expressions store *labels* (D2), a label rename or removal in a Value List leaves
 * previously stored expressions referencing something that no longer resolves. Those must be
 * surfaced, never silently dropped — an editor who cannot see the stale value cannot fix it,
 * and a silent drop would quietly widen the audience for gated content.
 */

import type { FacetCatalog } from '../catalog/types';
import { type Expression, isCondition, walk } from './ast';

export type IssueSeverity = 'error' | 'warning';

export interface ValidationIssue {
  severity: IssueSeverity;
  /** 'unknown-facet' | 'unknown-value' | 'duplicate-value' | 'empty-values' */
  kind: string;
  facet: string;
  value?: string;
  message: string;
}

export function validateAgainstCatalog(
  expression: Expression,
  catalog: FacetCatalog,
): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  const byName = new Map(catalog.facets.map((f) => [f.name, f]));

  walk(expression, (node) => {
    if (!isCondition(node)) return;
    const facet = byName.get(node.facet);

    if (!facet) {
      issues.push({
        severity: 'error',
        kind: 'unknown-facet',
        facet: node.facet,
        message: `No value list is named "${node.facet}". It may have been renamed or removed.`,
      });
      return;
    }

    if (node.values.length === 0) {
      issues.push({
        severity: 'error',
        kind: 'empty-values',
        facet: node.facet,
        message: `"${node.facet}" has no values selected.`,
      });
    }

    const labels = new Set(facet.values.map((v) => v.label));
    const seen = new Set<string>();
    for (const value of node.values) {
      if (!labels.has(value)) {
        issues.push({
          severity: 'error',
          kind: 'unknown-value',
          facet: node.facet,
          value,
          message: `"${value}" is not a current value of "${node.facet}".`,
        });
      }
      if (seen.has(value)) {
        issues.push({
          severity: 'warning',
          kind: 'duplicate-value',
          facet: node.facet,
          value,
          message: `"${value}" is listed more than once in "${node.facet}".`,
        });
      }
      seen.add(value);
    }
  });

  return issues;
}

export const hasErrors = (issues: ValidationIssue[]) =>
  issues.some((i) => i.severity === 'error');
