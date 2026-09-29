/**
 * Extension configuration, read from the `frontend:config` property on the
 * `frontend:uiExtension` node. See extension/README.md#configuration.
 *
 * Since brXM 14.7 that property supports container-config variable references, so
 * `catalogUrl` can be `${facetCatalog.url}` and resolve per environment without a rebuild.
 */

import type { AbsentFacetPolicy } from './expression/evaluate';

export interface ExtensionConfig {
  /** Where the iframe fetches the facet catalog. */
  catalogUrl: string;
  /** Seconds a cached catalog is served before revalidating. */
  cacheTtlSeconds: number;
  /**
   * Optional allow-list of value-list ids this *particular field* offers. Omit for all.
   * Lets one deployed extension serve several fields with different facet profiles, by
   * registering it once per profile — see extension/README.md#configuration.
   *
   * Note these are value-list IDS (`day-of-the-week`), while expressions reference facets by
   * DISPLAY NAME (`Day of the Week`) — see D3 in the root README.
   */
  facets?: string[];
  /** Dialog size passed to ui.dialog.open. */
  dialogSize: 'small' | 'medium' | 'large';
  /**
   * How a condition behaves when the request carries no value for that facet (D11).
   *
   * Defaults to `'strict'` — fail closed. A deployment wanting `'permissive'` must set it
   * explicitly; see D11 in the root README.
   *
   * The delivery tier is the authority on this at request time. The value here only drives the
   * builder's rule preview, so the two must be configured consistently or the preview will
   * disagree with production.
   */
  absentFacetPolicy: AbsentFacetPolicy;
}

const DEFAULTS: ExtensionConfig = {
  catalogUrl: '/api/facets',
  cacheTtlSeconds: 900,
  dialogSize: 'large',
  absentFacetPolicy: 'strict',
};

const POLICIES: AbsentFacetPolicy[] = ['strict', 'permissive'];

/**
 * Parses the config string leniently — a malformed or empty value must not break the field,
 * since an editor cannot fix repository config from the document editor.
 */
export function parseConfig(raw: string | undefined): ExtensionConfig {
  if (!raw || !raw.trim()) return { ...DEFAULTS };
  try {
    const parsed = JSON.parse(raw) as Partial<ExtensionConfig>;

    const policy = parsed.absentFacetPolicy;
    if (policy !== undefined && !POLICIES.includes(policy)) {
      console.warn(
        `[faceted-segmentation] absentFacetPolicy ${JSON.stringify(policy)} is not one of ` +
          `${POLICIES.join(' | ')}; falling back to ${DEFAULTS.absentFacetPolicy}.`,
      );
    }

    return {
      catalogUrl: parsed.catalogUrl?.trim() || DEFAULTS.catalogUrl,
      cacheTtlSeconds:
        typeof parsed.cacheTtlSeconds === 'number' && parsed.cacheTtlSeconds > 0
          ? parsed.cacheTtlSeconds
          : DEFAULTS.cacheTtlSeconds,
      facets: Array.isArray(parsed.facets) && parsed.facets.length ? parsed.facets : undefined,
      dialogSize: parsed.dialogSize ?? DEFAULTS.dialogSize,
      absentFacetPolicy:
        policy && POLICIES.includes(policy) ? policy : DEFAULTS.absentFacetPolicy,
    };
  } catch {
    console.warn('[faceted-segmentation] frontend:config is not valid JSON; using defaults.');
    return { ...DEFAULTS };
  }
}

/** Applies the per-field facet allow-list. */
export function restrictFacets<T extends { id: string }>(facets: T[], allow?: string[]): T[] {
  if (!allow?.length) return facets;
  const set = new Set(allow);
  return facets.filter((f) => set.has(f.id));
}
