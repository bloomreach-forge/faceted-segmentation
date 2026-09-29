/**
 * The builder dialog.
 *
 * Receives the current expression string via ui.dialog.options().value, edits it as an AST,
 * and returns the canonically formatted string via ui.dialog.close().
 *
 * Guided builder only — no raw text editing (D7), so every string this produces is
 * machine-generated and therefore always parseable.
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import UiExtension, { type UiScope } from '@bloomreach/ui-extension';
import { loadCatalog } from './catalog/client';
import type { CatalogSource, Facet, FacetCatalog } from './catalog/types';
import { parseConfig, restrictFacets, type ExtensionConfig } from './config';
import type { Expression, ExpressionNode } from './expression/ast';
import { countConditions, facetsUsed } from './expression/ast';
import { formatExpression } from './expression/format';
import { tryParseExpression } from './expression/parse';
import {
  addSibling,
  blankCondition,
  facetsInDestinationGroup,
  nextUnusedFacet,
  hasEmptyCondition,
  pruneEmpty,
  replaceAt,
  toggleNegate,
  wrapInGroup,
  type Path,
} from './expression/transform';
import { validateAgainstCatalog } from './expression/validate';
import { NodeEditor } from './ui/NodeEditor';

export function DialogApp() {
  const [ui, setUi] = useState<UiScope | null>(null);
  const [config, setConfig] = useState<ExtensionConfig | null>(null);
  const [expression, setExpression] = useState<Expression>(null);
  const [catalog, setCatalog] = useState<FacetCatalog | null>(null);
  const [source, setSource] = useState<CatalogSource | null>(null);
  const [fetchedAt, setFetchedAt] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  /** Two-step confirm for Clear all — discarding a built rule is easy to do by accident. */
  const [confirmClear, setConfirmClear] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [initialError, setInitialError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const scope = await UiExtension.register();
        if (cancelled) return;
        const cfg = parseConfig(scope.extension.config);
        const options = await scope.dialog.options();
        const incoming = typeof options.value === 'string' ? options.value : '';

        const parsed = tryParseExpression(incoming);
        if (parsed.ok) {
          setExpression(parsed.value);
        } else {
          setExpression(null);
          setInitialError(
            `The existing rule could not be read, so the builder started empty. The stored value was: ${incoming}`,
          );
        }

        setUi(scope);
        setConfig(cfg);
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : 'Could not connect to the CMS.');
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const fetchCatalog = useCallback(
    async (refresh: boolean) => {
      if (!config) return;
      refresh ? setSyncing(true) : setLoading(true);
      try {
        const result = await loadCatalog({
          catalogUrl: config.catalogUrl,
          maxAgeSeconds: config.cacheTtlSeconds,
          refresh,
        });
        setCatalog(result.catalog);
        setSource(result.source);
        setFetchedAt(result.fetchedAt);
        setError(null);
      } catch (e) {
        setError(
          e instanceof Error
            ? `Could not load facet values: ${e.message}`
            : 'Could not load facet values.',
        );
      } finally {
        setLoading(false);
        setSyncing(false);
      }
    },
    [config],
  );

  useEffect(() => {
    if (config) void fetchCatalog(false);
  }, [config, fetchCatalog]);

  const facets: Facet[] = useMemo(
    () => (catalog ? restrictFacets(catalog.facets, config?.facets) : []),
    [catalog, config],
  );

  const defaultFacet = useMemo(() => facets[0]?.name ?? '', [facets]);

  useEffect(() => {
    if (!confirmClear) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        setConfirmClear(false);
      }
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [confirmClear]);

  useEffect(() => {
    setConfirmClear(false);
  }, [expression]);

  const onReplace = useCallback(
    (path: Path, next: ExpressionNode) => setExpression((e) => replaceAt(e, path, next)),
    [],
  );
  const onRemove = useCallback(
    (path: Path) => setExpression((e) => replaceAt(e, path, null)),
    [],
  );
  const facetNames = useMemo(() => facets.map((f) => f.name), [facets]);
  const onAddSibling = useCallback(
    (path: Path, operator: 'AND' | 'OR') =>
      setExpression((e) => {
        const taken = facetsInDestinationGroup(e, path, operator);
        return addSibling(e, path, operator, blankCondition(nextUnusedFacet(facetNames, taken)));
      }),
    [facetNames],
  );
  const onWrapInGroup = useCallback(
    (path: Path) =>
      setExpression((e) => {
        const taken = facetsInDestinationGroup(e, path, 'AND');
        return wrapInGroup(e, path, blankCondition(nextUnusedFacet(facetNames, taken)));
      }),
    [facetNames],
  );
  const onToggleNegate = useCallback(
    (path: Path) => setExpression((e) => toggleNegate(e, path)),
    [],
  );

  const addFirst = useCallback(
    () => setExpression(blankCondition(defaultFacet)),
    [defaultFacet],
  );

  const cleaned = useMemo(() => pruneEmpty(expression), [expression]);
  const preview = useMemo(() => formatExpression(cleaned), [cleaned]);
  const incomplete = hasEmptyCondition(expression);
  const conditionCount = useMemo(() => countConditions(cleaned), [cleaned]);
  const validation = useMemo(
    () => (catalog ? validateAgainstCatalog(cleaned, catalog) : []),
    [cleaned, catalog],
  );
  const blockingErrors = validation.filter((i) => i.severity === 'error');

  const apply = useCallback(async () => {
    if (!ui) return;
    try {
      await ui.dialog.close(preview);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not save.');
    }
  }, [ui, preview]);

  const cancel = useCallback(async () => {
    if (!ui) return;
    try {
      await ui.dialog.cancel();
    } catch {
      /* dialog already gone */
    }
  }, [ui]);

  if (error && !catalog) {
    return (
      <div className="fs-dialog">
        <div className="fs-alert fs-alert--error">{error}</div>
        <div className="fs-dialog__footer">
          <button className="fs-btn" type="button" onClick={() => void fetchCatalog(true)}>
            Retry
          </button>
          <button className="fs-btn" type="button" onClick={cancel}>
            Close
          </button>
        </div>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="fs-dialog">
        <div className="fs-meta">
          <span className="fs-spinner" /> Loading facet values…
        </div>
      </div>
    );
  }

  return (
    <div className="fs-dialog">
      {initialError && <div className="fs-alert fs-alert--warning">{initialError}</div>}
      {error && <div className="fs-alert fs-alert--warning">{error}</div>}

      <div className="fs-dialog__body">
        <div className="fs-dialog__tree">
          <p className="fs-section-title">Segmentation rule</p>
          {expression ? (
            <NodeEditor
              node={expression}
              path={[]}
              facets={facets}
              isRoot
              onReplace={onReplace}
              onRemove={onRemove}
              onAddSibling={onAddSibling}
              onWrapInGroup={onWrapInGroup}
              onToggleNegate={onToggleNegate}
            />
          ) : (
            <div className="fs-empty">
              <p>
                No rules yet. This content is shown to <strong>everyone</strong>.
              </p>
              <div className="fs-field-actions" style={{ justifyContent: 'center' }}>
                <button
                  className="fs-btn fs-btn--primary"
                  type="button"
                  onClick={addFirst}
                  disabled={!facets.length}
                >
                  Add a condition
                </button>
              </div>
            </div>
          )}
        </div>

        <aside className="fs-dialog__aside">
          <div>
            <p className="fs-section-title">Result</p>
            <pre className="fs-preview">
              {preview || 'No segmentation — shown to everyone.'}
            </pre>
            {cleaned && (
              <div className="fs-meta" style={{ marginTop: 6 }}>
                {conditionCount} condition{conditionCount === 1 ? '' : 's'} across{' '}
                {facetsUsed(cleaned).length} facet{facetsUsed(cleaned).length === 1 ? '' : 's'}
              </div>
            )}
          </div>

          {incomplete && (
            <div className="fs-alert fs-alert--warning">
              A condition has no values selected. It will be dropped unless you choose values.
            </div>
          )}

          {blockingErrors.length > 0 && (
            <div className="fs-alert fs-alert--error">
              <div>
                <strong>Fix before applying</strong>
                <ul>
                  {blockingErrors.slice(0, 5).map((i, idx) => (
                    <li key={idx}>{i.message}</li>
                  ))}
                </ul>
              </div>
            </div>
          )}

          <div>
            <p className="fs-section-title">Evaluation</p>
            <div className="fs-meta">
              When a visitor has no value for a facet,{' '}
              <strong>
                {config?.absentFacetPolicy === 'permissive'
                  ? 'exclusions still apply'
                  : 'the facet must be present'}
              </strong>
              {config?.absentFacetPolicy === 'permissive'
                ? ' — a NOT IN rule matches them.'
                : ' — both IN and NOT IN rules fail.'}
            </div>
          </div>

          <div>
            <p className="fs-section-title">Facet values</p>
            <div className="fs-meta">
              {facets.length} facet{facets.length === 1 ? '' : 's'} available
              {source && (
                <>
                  {' · '}
                  {source === 'network' ? 'freshly loaded' : 'from cache'}
                </>
              )}
              {fetchedAt && <> · synced {new Date(fetchedAt).toLocaleTimeString()}</>}
            </div>
            <button
              className="fs-btn fs-btn--sm"
              type="button"
              style={{ marginTop: 8 }}
              onClick={() => void fetchCatalog(true)}
              disabled={syncing}
            >
              {syncing ? <span className="fs-spinner" /> : null}
              Resync facet values
            </button>
          </div>
        </aside>
      </div>

      <div className="fs-dialog__footer">
        {confirmClear ? (
          <div className="fs-confirm">
            <span className="fs-confirm__label">
              Remove {conditionCount === 1 ? 'the condition' : `all ${conditionCount} conditions`}?
            </span>
            <button
              className="fs-btn fs-btn--sm fs-btn--danger"
              type="button"
              onClick={() => {
                setExpression(null);
                setConfirmClear(false);
              }}
            >
              Yes, clear
            </button>
            <button
              className="fs-btn fs-btn--sm"
              type="button"
              onClick={() => setConfirmClear(false)}
            >
              Keep
            </button>
          </div>
        ) : (
          <button
            className="fs-btn fs-btn--ghost"
            type="button"
            onClick={() => setConfirmClear(true)}
            disabled={conditionCount === 0}
          >
            Clear all
          </button>
        )}
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="fs-btn" type="button" onClick={cancel}>
            Cancel
          </button>
          <button
            className="fs-btn fs-btn--primary"
            type="button"
            onClick={apply}
            disabled={blockingErrors.length > 0}
          >
            Apply
          </button>
        </div>
      </div>
    </div>
  );
}
