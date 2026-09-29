/**
 * Multi-select for one facet's values, with search — needed because the widest value list in
 * the demo has 72 items and license-state has 51.
 *
 * Selected values that no longer exist in the catalog are shown as stale chips rather than
 * dropped, per D2: a silent drop would quietly widen the audience for gated content.
 */

import { useMemo, useState } from 'react';
import type { Facet } from '../catalog/types';

interface Props {
  facet: Facet | undefined;
  selected: string[];
  onChange: (values: string[]) => void;
  disabled?: boolean;
}

export function ValuePicker({ facet, selected, onChange, disabled }: Props) {
  const [query, setQuery] = useState('');

  const known = useMemo(() => new Set(facet?.values.map((v) => v.label) ?? []), [facet]);
  const stale = selected.filter((v) => !known.has(v));

  const options = useMemo(() => {
    if (!facet) return [];
    const q = query.trim().toLowerCase();
    if (!q) return facet.values;
    return facet.values.filter(
      (v) => v.label.toLowerCase().includes(q) || v.key.toLowerCase().includes(q),
    );
  }, [facet, query]);

  const toggle = (label: string) => {
    onChange(selected.includes(label) ? selected.filter((v) => v !== label) : [...selected, label]);
  };

  if (!facet) {
    return (
      <div className="fs-values">
        <div className="fs-alert fs-alert--error">
          This facet is not in the current catalog, so its values cannot be edited. Choose a
          different facet, or restore the value list.
        </div>
        {selected.length > 0 && (
          <div className="fs-chips">
            {selected.map((v) => (
              <span className="fs-chip fs-chip--stale" key={v}>
                {v}
                {!disabled && (
                  <button type="button" onClick={() => toggle(v)} aria-label={`Remove ${v}`}>
                    ×
                  </button>
                )}
              </span>
            ))}
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="fs-values">
      {selected.length > 0 && (
        <div className="fs-chips">
          {selected.map((v) => (
            <span className={known.has(v) ? 'fs-chip' : 'fs-chip fs-chip--stale'} key={v}>
              {v}
              {!known.has(v) && ' (no longer available)'}
              {!disabled && (
                <button type="button" onClick={() => toggle(v)} aria-label={`Remove ${v}`}>
                  ×
                </button>
              )}
            </span>
          ))}
        </div>
      )}

      {stale.length > 0 && (
        <div className="fs-alert fs-alert--error">
          {stale.length === 1
            ? `"${stale[0]}" is no longer a value of ${facet.name}.`
            : `${stale.length} selected values are no longer values of ${facet.name}.`}{' '}
          They are still stored. Remove them or update the value list.
        </div>
      )}

      {!disabled && (
        <>
          <input
            className="fs-search"
            type="search"
            placeholder={`Search ${facet.values.length} ${facet.name} values…`}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <div className="fs-options" role="group" aria-label={`${facet.name} values`}>
            {options.length === 0 ? (
              <div className="fs-option fs-meta">No values match “{query}”.</div>
            ) : (
              options.map((v) => (
                <label className="fs-option" key={v.key}>
                  <input
                    type="checkbox"
                    checked={selected.includes(v.label)}
                    onChange={() => toggle(v.label)}
                  />
                  <span>{v.label}</span>
                  {v.key !== v.label && <span className="fs-option__key">{v.key}</span>}
                </label>
              ))
            )}
          </div>
          <div className="fs-meta" style={{ marginTop: 4 }}>
            {selected.length} of {facet.values.length} selected
            {selected.length > facet.values.length / 2 && (
              <> — consider inverting to <strong>NOT IN</strong> to shorten the rule</>
            )}
          </div>
        </>
      )}
    </div>
  );
}
