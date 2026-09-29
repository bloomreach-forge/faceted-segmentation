/**
 * Catalog client — the browser half of the two-layer cache (D8).
 *
 * Layer 1 (here): localStorage, so reopening the dialog is instant and a brief backend
 * outage is survivable.
 * Layer 2 (backend): in-memory TTL cache, so one repository read serves every editor.
 *
 * The SDK cannot read value lists at all (D8 in the root README), so this fetch is
 * the only route to the facet data.
 */

import type { CatalogSource, FacetCatalog } from './types';

const STORAGE_KEY = 'brxm.facetedSegmentation.catalog.v1';

/** In-page memoization, so N field instances on one document share a single fetch. */
let inflight: Promise<FacetCatalog> | null = null;
let memory: { catalog: FacetCatalog; fetchedAt: number } | null = null;

export interface LoadResult {
  catalog: FacetCatalog;
  source: CatalogSource;
  fetchedAt: string;
}

export interface LoadOptions {
  catalogUrl: string;
  /** How long a cached copy is served before revalidating. Default 15 minutes. */
  maxAgeSeconds?: number;
  /** Bypass every cache layer and re-read from the repository. The resync button. */
  refresh?: boolean;
}

export async function loadCatalog(options: LoadOptions): Promise<LoadResult> {
  const { catalogUrl, maxAgeSeconds = 900, refresh = false } = options;

  if (refresh) {
    inflight = null;
    memory = null;
    clearStored();
  } else {
    // Layer 1a: this page's memory.
    if (memory && ageSeconds(memory.fetchedAt) < maxAgeSeconds) {
      return {
        catalog: memory.catalog,
        source: 'memory',
        fetchedAt: new Date(memory.fetchedAt).toISOString(),
      };
    }
    // Layer 1b: localStorage, shared across reloads and dialog open/close cycles.
    const stored = readStored();
    if (stored && ageSeconds(stored.fetchedAt) < maxAgeSeconds) {
      memory = stored;
      return {
        catalog: stored.catalog,
        source: 'localStorage',
        fetchedAt: new Date(stored.fetchedAt).toISOString(),
      };
    }
  }

  // Layer 2: the backend.
  if (!inflight) {
    inflight = fetchCatalog(catalogUrl, refresh).finally(() => {
      inflight = null;
    });
  }

  try {
    const catalog = await inflight;
    const fetchedAt = Date.now();
    memory = { catalog, fetchedAt };
    writeStored(catalog, fetchedAt);
    return { catalog, source: 'network', fetchedAt: new Date(fetchedAt).toISOString() };
  } catch (error) {
    // Prefer stale-but-real data over failing the editor outright — but only data that came
    // from the real endpoint at some point. There is NO bundled fallback: the catalog endpoint
    // is the single source of facet values, so when it is unreachable and nothing is cached the
    // failure must surface rather than be papered over with fixture data (which would let an
    // editor build rules against facets the repository may no longer have).
    const stored = readStored();
    if (stored) {
      memory = stored;
      console.warn(
        `[faceted-segmentation] Facet catalog fetch failed; serving a cached copy from ` +
          `${new Date(stored.fetchedAt).toISOString()}. Values may be stale.`,
        error,
      );
      return {
        catalog: stored.catalog,
        source: 'localStorage',
        fetchedAt: new Date(stored.fetchedAt).toISOString(),
      };
    }

    // Nothing cached and the endpoint is unusable: the extension cannot function. Log the full
    // diagnosis, since the UI shows the message but the console carries the underlying cause.
    if (error instanceof CatalogError) {
      console.error(
        `[faceted-segmentation] Facet catalog unavailable (${error.kind}) at ${error.url}\n` +
          `${error.message}` +
          (error.detail ? `\nUnderlying error: ${error.detail}` : ''),
      );
    } else {
      console.error('[faceted-segmentation] Facet catalog unavailable:', error);
    }
    throw error;
  }
}

/**
 * The catalog endpoint is a hard dependency, so every failure mode gets its own message naming
 * the URL and what to check. These strings are shown to CMS editors *and* logged, so they have
 * to be actionable by someone who cannot read this code.
 */
export class CatalogError extends Error {
  constructor(
    message: string,
    readonly url: string,
    readonly kind:
      | 'unreachable'
      | 'not-found'
      | 'unauthorized'
      | 'server-error'
      | 'not-json'
      | 'malformed',
    readonly detail?: string,
  ) {
    super(message);
    this.name = 'CatalogError';
  }
}

async function fetchCatalog(url: string, refresh: boolean): Promise<FacetCatalog> {
  let target: URL;
  try {
    target = new URL(url, window.location.href);
  } catch {
    throw new CatalogError(
      `The configured catalogUrl is not a valid URL: "${url}". Fix catalogUrl in frontend:config ` +
        `on the frontend:uiExtension node.`,
      url,
      'unreachable',
    );
  }
  if (refresh) target.searchParams.set('refresh', 'true');

  const stored = refresh ? null : readStored();
  const headers: HeadersInit = {};
  if (stored?.catalog.etag) headers['If-None-Match'] = stored.catalog.etag;

  let response: Response;
  try {
    response = await fetch(target.toString(), {
      headers,
      // The catalog is not user-specific; credentials are unnecessary and would widen CORS.
      credentials: 'omit',
    });
  } catch (cause) {
    // fetch() rejects for network failure, DNS, refused connection, CORS rejection AND CSP
    // blocking — the browser deliberately does not distinguish them to the page. CSP is the
    // most likely cause in a fresh brXM install, so name it first.
    throw new CatalogError(
      `Could not reach the facet catalog at ${target.origin}${target.pathname}. Check, in order: ` +
        `(1) the CMS Content-Security-Policy allows this origin in connect-src; ` +
        `(2) the catalog endpoint is deployed and running; ` +
        `(3) it returns CORS headers permitting this extension's origin. ` +
        `The browser console shows which of these it was — a CSP violation is reported there ` +
        `but not to this page.`,
      target.toString(),
      'unreachable',
      cause instanceof Error ? cause.message : String(cause),
    );
  }

  // Unchanged — reuse what we already hold.
  if (response.status === 304 && stored) return stored.catalog;

  if (response.status === 304) {
    // 304 with nothing cached: we sent no If-None-Match, so this is a server bug.
    throw new CatalogError(
      `The facet catalog returned 304 Not Modified, but nothing is cached to reuse. The endpoint ` +
        `should only send 304 in response to an If-None-Match header.`,
      target.toString(),
      'server-error',
    );
  }

  if (!response.ok) {
    const kind =
      response.status === 404
        ? 'not-found'
        : response.status === 401 || response.status === 403
          ? 'unauthorized'
          : 'server-error';
    const hint =
      kind === 'not-found'
        ? ` Check catalogUrl in frontend:config — the path may be wrong, or the REST resource may ` +
          `not be mapped in the site webapp.`
        : kind === 'unauthorized'
          ? ` The endpoint requires credentials this extension does not send. Facet values are not ` +
            `user-specific, so the endpoint should be readable without authentication.`
          : ` The endpoint is reachable but failed. Check the site webapp logs.`;
    throw new CatalogError(
      `The facet catalog request failed: ${response.status} ${response.statusText}.${hint}`,
      target.toString(),
      kind,
    );
  }

  let payload: unknown;
  try {
    payload = await response.json();
  } catch (cause) {
    const contentType = response.headers.get('content-type') ?? 'none';
    throw new CatalogError(
      `The facet catalog did not return JSON (content-type: ${contentType}). This usually means ` +
        `the URL resolved to something other than the catalog endpoint — an HTML error page or a ` +
        `login redirect, for instance.`,
      target.toString(),
      'not-json',
      cause instanceof Error ? cause.message : String(cause),
    );
  }

  assertCatalog(payload, target.toString());
  return payload;
}

function assertCatalog(value: unknown, url: string): asserts value is FacetCatalog {
  const c = value as FacetCatalog;
  if (!c || typeof c !== 'object') {
    throw new CatalogError(
      `The facet catalog response was not a JSON object.`,
      url,
      'malformed',
    );
  }
  if (!Array.isArray(c.facets)) {
    throw new CatalogError(
      `The facet catalog response is missing its "facets" array. Expected ` +
        `{ etag, generatedAt, facets: [...] } — see extension/README.md#1-a-facet-catalog-endpoint.`,
      url,
      'malformed',
    );
  }
  if (c.facets.length === 0) {
    throw new CatalogError(
      `The facet catalog returned zero facets. The endpoint is working but found no value lists — ` +
        `check the repository path it reads from.`,
      url,
      'malformed',
    );
  }
}

// --- localStorage plumbing (defensive: storage can be disabled or full) ----

interface Stored {
  catalog: FacetCatalog;
  fetchedAt: number;
}

function readStored(): Stored | null {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Stored;
    if (!parsed?.catalog?.facets) return null;
    return parsed;
  } catch {
    return null;
  }
}

function writeStored(catalog: FacetCatalog, fetchedAt: number): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ catalog, fetchedAt }));
  } catch {
    // Quota or private-mode failure is non-fatal — the memory layer still works.
  }
}

function clearStored(): void {
  try {
    window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* ignore */
  }
}

const ageSeconds = (since: number) => (Date.now() - since) / 1000;
