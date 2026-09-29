/**
 * Facet catalog types. Mirrors the `GET /api/facets` contract served by FacetCatalogResource
 * in the hst module; see extension/README.md#1-a-facet-catalog-endpoint.
 */

export interface FacetValue {
  /** selection:key — carried for the eventual label->key mapping; never appears in expressions. */
  key: string;
  /** selection:label — what appears inside [...] in an expression (D2). */
  label: string;
}

export interface Facet {
  /** Value list node name, e.g. "license-state". Stable identifier. */
  id: string;
  /** Value list hippo:name, e.g. "Day of the Week". The facet token in expressions (D3). */
  name: string;
  /** JCR path of the value list, for diagnostics. */
  path?: string;
  values: FacetValue[];
}

export interface FacetCatalog {
  /** Content hash, used for If-None-Match and as the localStorage cache key. */
  etag: string;
  generatedAt: string;
  facets: Facet[];
}

/** Where a catalog came from — surfaced in the UI so editors know if data may be stale. */
/**
 * Where a loaded catalog came from. There is deliberately no 'fallback' — the extension has no
 * bundled catalog and cannot function without the endpoint (D14 in the root README).
 */
export type CatalogSource = 'memory' | 'localStorage' | 'network';

export interface CatalogState {
  catalog: FacetCatalog | null;
  source: CatalogSource | null;
  loading: boolean;
  error: string | null;
  /** When this copy was obtained, for the "last synced" display. */
  fetchedAt: string | null;
}
