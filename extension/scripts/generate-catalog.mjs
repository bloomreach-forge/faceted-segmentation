#!/usr/bin/env node
/**
 * Generates a facet catalog fixture from the plugin's own bootstrapped Value List YAML.
 *
 * This produces the TEST FIXTURE only. It is NOT a runtime data source: the extension always
 * fetches its catalog from the configured `catalogUrl` (the HST REST resource), and has no
 * built-in fallback — see the README's "Requirements" section.
 *
 * Output lives in test/fixtures/, deliberately NOT in public/. Anything in public/ is copied
 * into dist/ by Vite and would ship a stale catalog inside the deployed artifact.
 * In production the catalog service reads the repository directly.
 *
 * Deliberately hand-rolled rather than pulling in a YAML dependency: the HCM YAML here is a
 * narrow, predictable subset (value lists only), and the extension should stay dependency-light.
 *
 *   node scripts/generate-catalog.mjs [--out test/fixtures/facets.json]
 */

import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';

const REPO_ROOT = resolve(import.meta.dirname, '..', '..');

/**
 * The plugin's own example value lists, bootstrapped by the `repository` module. Using the
 * plugin's own content — rather than any consuming project's — keeps the fixture reproducible
 * from this repository alone, and means the tests assert against exactly what a fresh install
 * actually offers an editor.
 */
const VALUE_LISTS = join(
  REPO_ROOT,
  'repository/src/main/resources/hcm-content/content/documents/administration/faceted-segmentation',
);
const CONTENT_PATH = '/content/documents/administration/faceted-segmentation';

const outArgIndex = process.argv.indexOf('--out');
const OUT = resolve(
  import.meta.dirname,
  '..',
  outArgIndex !== -1 ? process.argv[outArgIndex + 1] : 'test/fixtures/facets.json',
);

/**
 * Extracts `hippo:name` and every `selection:key` / `selection:label` pair.
 *
 * Values may be plain, single-quoted, or double-quoted; YAML single-quoting doubles interior
 * quotes, so `''` unescapes to `'`.
 */
function parseValueList(yaml) {
  const nameMatch = yaml.match(/^ {2}hippo:name:\s*(.+)$/m);
  const displayName = nameMatch ? unquote(nameMatch[1]) : null;

  const values = [];
  const pattern = /selection:key:\s*(.*)\r?\n\s*selection:label:\s*(.*)/g;
  let match;
  while ((match = pattern.exec(yaml)) !== null) {
    const key = unquote(match[1]);
    const label = unquote(match[2]);
    if (key === '' && label === '') continue;
    values.push({ key, label });
  }
  return { displayName, values };
}

function unquote(raw) {
  const text = raw.trim();
  if (text.startsWith("'") && text.endsWith("'") && text.length >= 2) {
    return text.slice(1, -1).replace(/''/g, "'");
  }
  if (text.startsWith('"') && text.endsWith('"') && text.length >= 2) {
    return text.slice(1, -1).replace(/\"/g, '"');
  }
  return text;
}

const files = readdirSync(VALUE_LISTS)
  .filter((f) => f.endsWith('.yaml'))
  .sort();

const facets = [];
const warnings = [];

for (const file of files) {
  const id = file.replace(/\.yaml$/, '');
  const yaml = readFileSync(join(VALUE_LISTS, file), 'utf8');
  const { displayName, values } = parseValueList(yaml);

  if (!displayName) {
    warnings.push(`${file}: no hippo:name found — skipped`);
    continue;
  }
  if (values.length === 0) {
    warnings.push(`${file}: no selection:listitem entries found — skipped`);
    continue;
  }

  for (const { label } of values) {
    if (label.includes(',') || label.includes('[') || label.includes(']')) {
      warnings.push(`${file}: label ${JSON.stringify(label)} contains , [ or ] — NOT SAFE for the grammar`);
    }
  }
  if (/(AND|OR|NOT|IN)/.test(displayName)) {
    warnings.push(`${file}: display name ${JSON.stringify(displayName)} contains a grammar keyword — NOT SAFE`);
  }

  facets.push({
    id,
    name: displayName,
    path: `${CONTENT_PATH}/${id}`,
    values,
  });
}

const payload = {
  etag: '',
  generatedAt: new Date().toISOString(),
  facets,
};
payload.etag = `sha256-${createHash('sha256')
  .update(JSON.stringify({ facets }))
  .digest('hex')
  .slice(0, 32)}`;

mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, `${JSON.stringify(payload, null, 2)}
`);

const total = facets.reduce((n, f) => n + f.values.length, 0);
console.log(`Wrote ${OUT}`);
console.log(`  ${facets.length} facets, ${total} values, etag ${payload.etag}`);
for (const facet of facets) {
  const divergent = facet.values.filter((v) => v.key !== v.label).length;
  console.log(
    `  ${facet.id.padEnd(24)} ${JSON.stringify(facet.name).padEnd(26)} ${String(facet.values.length).padStart(3)} values` +
      (divergent ? `  (${divergent} key≠label)` : ''),
  );
}
if (warnings.length) {
  console.warn('\nWarnings:');
  for (const w of warnings) console.warn(`  ! ${w}`);
}
