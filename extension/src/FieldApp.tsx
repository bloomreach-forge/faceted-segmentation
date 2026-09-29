/**
 * The inline document field.
 *
 * Renders the full expression and grows to fit via setHeight('auto') (D6), with a button that
 * opens the builder dialog. Read-only in 'view' mode; shows a side-by-side diff in 'compare'.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import UiExtension, { DialogSize, type UiScope } from '@bloomreach/ui-extension';
import { loadCatalog } from './catalog/client';
import type { FacetCatalog } from './catalog/types';
import { parseConfig, restrictFacets, type ExtensionConfig } from './config';
import { formatExpression } from './expression/format';
import { tryParseExpression } from './expression/parse';
import { validateAgainstCatalog, type ValidationIssue } from './expression/validate';
import { ExpressionView } from './ui/ExpressionView';

type Mode = 'view' | 'edit' | 'compare';

/** The SDK types `size` as its DialogSize enum, so map our plain config string onto it. */
const DIALOG_SIZES: Record<'small' | 'medium' | 'large', DialogSize> = {
  small: DialogSize.Small,
  medium: DialogSize.Medium,
  large: DialogSize.Large,
};

export function FieldApp() {
  const [ui, setUi] = useState<UiScope | null>(null);
  const [config, setConfig] = useState<ExtensionConfig | null>(null);
  const [mode, setMode] = useState<Mode>('view');
  const [value, setValue] = useState('');
  const [compareValue, setCompareValue] = useState<string | null>(null);
  const [catalog, setCatalog] = useState<FacetCatalog | null>(null);
  const [issues, setIssues] = useState<ValidationIssue[]>([]);
  const [parseError, setParseError] = useState<string | null>(null);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [fatal, setFatal] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const rootRef = useRef<HTMLDivElement>(null);

  // --- register with the CMS ------------------------------------------------
  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const scope = await UiExtension.register();
        if (cancelled) return;

        const cfg = parseConfig(scope.extension.config);
        const doc = await scope.document.get();
        const current = (await scope.document.field.getValue()) ?? '';

        setUi(scope);
        setConfig(cfg);
        setMode(doc.mode as Mode);
        setValue(current);

        if (doc.mode === 'compare') {
          try {
            setCompareValue((await scope.document.field.getCompareValue()) ?? '');
          } catch {
            setCompareValue(null); // non-fatal: just skip the diff
          }
        }
      } catch (error) {
        if (!cancelled) {
          setFatal(
            error instanceof Error
              ? `Could not connect to the CMS: ${error.message}`
              : 'Could not connect to the CMS.',
          );
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  // --- catalog, for validating stored labels -------------------------------
  useEffect(() => {
    if (!config) return;
    let cancelled = false;
    (async () => {
      try {
        const result = await loadCatalog({
          catalogUrl: config.catalogUrl,
          maxAgeSeconds: config.cacheTtlSeconds,
        });
        if (!cancelled) {
          setCatalog(result.catalog);
          setCatalogError(null);
        }
      } catch (e) {
        // The stored expression still renders — it is just a string — but without a catalog we
        // cannot validate its labels and the builder cannot be opened. Surface it rather than
        // degrading silently, since an editor otherwise sees a working-looking field whose
        // "Modify" button then fails.
        if (!cancelled) {
          setCatalog(null);
          setCatalogError(
            e instanceof Error ? e.message : 'The facet catalog could not be loaded.',
          );
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [config]);

  // --- validate + normalize the stored string ------------------------------
  useEffect(() => {
    const parsed = tryParseExpression(value);
    if (!parsed.ok) {
      setParseError(parsed.error.describe());
      setIssues([]);
      return;
    }
    setParseError(null);
    setIssues(catalog ? validateAgainstCatalog(parsed.value, catalog) : []);
  }, [value, catalog]);

  // --- keep the iframe sized to content (D6) -------------------------------
  useEffect(() => {
    if (!ui) return;
    ui.document.field.setHeight('auto').catch(() => {
      // Fall back to a measured height if 'auto' is unavailable.
      const h = rootRef.current?.scrollHeight;
      if (h) ui.document.field.setHeight(Math.min(2000, Math.max(10, h + 8))).catch(() => {});
    });
  }, [ui, value, issues.length, parseError, compareValue]);

  const openBuilder = useCallback(async () => {
    if (!ui || !config) return;
    setBusy(true);
    try {
      const next = await ui.dialog.open({
        title: 'Modify Segmentation Rules',
        url: './dialog.html',
        size: DIALOG_SIZES[config.dialogSize],
        value: value ?? '',
      });

      if (typeof next !== 'string') return;

      // Normalize before storing so compare-mode diffs stay meaningful.
      const parsed = tryParseExpression(next);
      const normalized = parsed.ok ? formatExpression(parsed.value) : next;

      await ui.document.field.setValue(normalized);
      setValue(normalized);
    } catch (error) {
      const code = (error as { code?: string })?.code;
      if (code === 'DialogCanceled') return;
      if (code === 'DialogExists') return;
      setFatal(
        error instanceof Error ? `Could not open the builder: ${error.message}` : 'Could not open the builder.',
      );
    } finally {
      setBusy(false);
    }
  }, [ui, config, value]);

  if (fatal) {
    return (
      <div className="fs-field">
        <div className="fs-alert fs-alert--error">{fatal}</div>
      </div>
    );
  }

  if (!ui) {
    return (
      <div className="fs-field fs-meta">
        <span className="fs-spinner" /> Loading segmentation field…
      </div>
    );
  }

  const errors = issues.filter((i) => i.severity === 'error');
  const warnings = issues.filter((i) => i.severity === 'warning');
  const staleValues = new Set(
    errors.filter((i) => i.kind === 'unknown-value' && i.value).map((i) => i.value as string),
  );
  const editable = mode === 'edit';

  return (
    <div className="fs-field" ref={rootRef}>
      {catalogError && (
        <div className="fs-alert fs-alert--error">
          <div>
            <strong>Facet values are unavailable.</strong>
            <div style={{ marginTop: 4 }}>{catalogError}</div>
            <div className="fs-meta" style={{ marginTop: 6 }}>
              The rule below is still shown as stored, but it cannot be validated or edited until
              the catalog endpoint responds. Full diagnostics are in the browser console.
            </div>
          </div>
        </div>
      )}

      {parseError && (
        <div className="fs-alert fs-alert--error">
          <div>
            <strong>This rule could not be read.</strong>
            <pre className="fs-preview" style={{ marginTop: 6 }}>
              {parseError}
            </pre>
            {editable && ' Open the builder to rebuild it.'}
          </div>
        </div>
      )}

      {errors.length > 0 && (
        <div className="fs-alert fs-alert--error">
          <div>
            <strong>
              {errors.length === 1 ? '1 value or facet no longer exists' : `${errors.length} values or facets no longer exist`}
            </strong>
            <ul>
              {errors.slice(0, 6).map((issue, i) => (
                <li key={i}>{issue.message}</li>
              ))}
            </ul>
            {errors.length > 6 && <div className="fs-meta">…and {errors.length - 6} more.</div>}
          </div>
        </div>
      )}

      {warnings.length > 0 && (
        <div className="fs-alert fs-alert--warning">
          <div>
            {warnings.slice(0, 4).map((issue, i) => (
              <div key={i}>{issue.message}</div>
            ))}
          </div>
        </div>
      )}

      {mode === 'compare' && compareValue !== null ? (
        <div style={{ display: 'grid', gap: 12 }}>
          <div>
            <p className="fs-section-title">Previous</p>
            <ExpressionView expression={compareValue} />
          </div>
          <div>
            <p className="fs-section-title">Current</p>
            <ExpressionView expression={value} staleValues={staleValues} />
          </div>
        </div>
      ) : (
        <ExpressionView expression={value} staleValues={staleValues} />
      )}

      {editable && (
        <div className="fs-field-actions">
          <button
            className="fs-btn fs-btn--primary"
            type="button"
            onClick={openBuilder}
            disabled={busy || !!catalogError}
            title={
              catalogError
                ? 'The builder needs the facet catalog, which could not be loaded.'
                : undefined
            }
          >
            {busy ? <span className="fs-spinner" /> : null}
            Modify Segmentation Rules
          </button>

          {/* The error alert above already explains a failed catalog, so only say this in the
              quieter case where there is no catalog and no error to show. */}
          {!catalog && !catalogError && (
            <span className="fs-meta">Facet catalog unavailable — values cannot be checked.</span>
          )}
        </div>
      )}
    </div>
  );
}

/** Restricts a catalog to the facets this field is configured to offer. */
export function visibleFacets(catalog: FacetCatalog | null, config: ExtensionConfig | null) {
  if (!catalog) return [];
  return restrictFacets(catalog.facets, config?.facets);
}
