/**
 * Read-only rendering of an expression string with light syntax tinting.
 *
 * Tokenizes off the *canonical formatted output* rather than re-parsing, so it stays cheap
 * and degrades gracefully: unrecognized text is emitted verbatim rather than dropped.
 */

import { Fragment, type ReactNode } from 'react';

interface Props {
  expression: string;
  /** Labels that no longer resolve against the catalog — tinted as errors. */
  staleValues?: Set<string>;
}

export function ExpressionView({ expression, staleValues }: Props) {
  if (!expression.trim()) {
    return (
      <div className="fs-expression fs-expression--empty">
        No segmentation rules — this content is shown to everyone.
      </div>
    );
  }
  return <div className="fs-expression">{tint(expression, staleValues)}</div>;
}

/**
 * Splits into: bracketed value lists, logical keywords, IN/NOT IN operators, parens, and
 * everything else (facet names). Order matters — brackets are consumed first so that
 * parentheses inside labels are never mistaken for grouping.
 */
function tint(source: string, stale?: Set<string>): ReactNode[] {
  const out: ReactNode[] = [];
  let i = 0;
  let key = 0;

  while (i < source.length) {
    const ch = source[i];

    // Bracketed value list — consume whole, then tint each comma-separated value.
    if (ch === '[') {
      const close = source.indexOf(']', i);
      if (close === -1) {
        out.push(<Fragment key={key++}>{source.slice(i)}</Fragment>);
        break;
      }
      const inner = source.slice(i + 1, close);
      out.push(
        <span className="fs-tok-punct" key={key++}>
          [
        </span>,
      );
      inner.split(',').forEach((part, idx, arr) => {
        const label = part.trim();
        const isStale = !!label && stale?.has(label);
        // Preserve original spacing/newlines around the label.
        const leading = part.slice(0, part.indexOf(label) === -1 ? 0 : part.indexOf(label));
        const trailing = part.slice(leading.length + label.length);
        out.push(<Fragment key={key++}>{leading}</Fragment>);
        out.push(
          <span className={isStale ? 'fs-tok-value fs-chip--stale' : 'fs-tok-value'} key={key++}>
            {label}
          </span>,
        );
        out.push(<Fragment key={key++}>{trailing}</Fragment>);
        if (idx < arr.length - 1) {
          out.push(
            <span className="fs-tok-punct" key={key++}>
              ,
            </span>,
          );
        }
      });
      out.push(
        <span className="fs-tok-punct" key={key++}>
          ]
        </span>,
      );
      i = close + 1;
      continue;
    }

    if (ch === '(' || ch === ')') {
      out.push(
        <span className="fs-tok-punct" key={key++}>
          {ch}
        </span>,
      );
      i++;
      continue;
    }

    // Keywords, longest first so NOT IN wins over NOT.
    const kw = matchKeyword(source, i);
    if (kw) {
      const cls =
        kw === 'IN' || kw === 'NOT IN' ? 'fs-tok-op' : 'fs-tok-logical';
      out.push(
        <span className={cls} key={key++}>
          {kw}
        </span>,
      );
      i += kw.length;
      continue;
    }

    // Facet name / whitespace: accumulate until the next significant character.
    let j = i;
    while (j < source.length) {
      const c = source[j];
      if (c === '[' || c === '(' || c === ')' || matchKeyword(source, j)) break;
      j++;
    }
    const text = source.slice(i, j === i ? i + 1 : j);
    out.push(
      <span className="fs-tok-facet" key={key++}>
        {text}
      </span>,
    );
    i += text.length;
  }

  return out;
}

const KEYWORDS = ['NOT IN', 'AND', 'NOT', 'OR', 'IN'] as const;

function matchKeyword(src: string, at: number): string | null {
  for (const kw of KEYWORDS) {
    if (!src.startsWith(kw, at)) continue;
    const before = at === 0 ? ' ' : src[at - 1];
    const after = src[at + kw.length] ?? ' ';
    if (!/[A-Za-z0-9_]/.test(before) && !/[A-Za-z0-9_]/.test(after)) return kw;
  }
  return null;
}
