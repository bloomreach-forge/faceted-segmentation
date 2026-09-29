/**
 * Recursive builder tree. Renders one AST node and its children, mirroring the nesting the
 * grammar allows (D4) plus group negation (D5).
 *
 * Nodes are addressed by index path (e.g. [1, 0] = second operand's first child) so edits are
 * pure structural transforms on an immutable tree — no ids to keep in sync.
 */

import type { ExpressionNode } from '../expression/ast';
import { condition, isBinary, isCondition, isNot } from '../expression/ast';
import type { Facet } from '../catalog/types';
import { ValuePicker } from './ValuePicker';

export type Path = number[];

const EXHAUSTED_HINT =
  'Every available facet is already used in this group. Add values to an existing condition instead.';

export interface NodeEditorProps {
  node: ExpressionNode;
  path: Path;
  facets: Facet[];
  disabled?: boolean;
  onReplace: (path: Path, next: ExpressionNode) => void;
  onRemove: (path: Path) => void;
  onAddSibling: (path: Path, operator: 'AND' | 'OR') => void;
  onWrapInGroup: (path: Path) => void;
  onToggleNegate: (path: Path) => void;
  /** Root cannot be removed; it is cleared instead. */
  isRoot?: boolean;
  /** Set by GroupEditor when rendering a NOT-wrapped group. */
  negated?: boolean;
  /**
   * Facets already used by this node's *siblings* in the same group, which the facet dropdown
   * must therefore not offer — picking one twice in a group only produces noise like
   * `Size IN [Small] OR Size IN [Large]`. Set by GroupEditor; a root-level
   * lone condition has none.
   */
  siblingFacets?: ReadonlySet<string>;
}

export function NodeEditor(props: NodeEditorProps) {
  const { node } = props;

  if (isCondition(node)) return <ConditionEditor {...props} node={node} />;
  if (isNot(node)) return <GroupEditor {...props} negated />;
  if (isBinary(node)) return <GroupEditor {...props} negated={false} />;
  return null;
}

function ConditionEditor({
  node,
  path,
  facets,
  disabled,
  onReplace,
  onRemove,
  onAddSibling,
  onWrapInGroup,
  isRoot,
  siblingFacets,
}: NodeEditorProps & { node: ReturnType<typeof condition> }) {
  const facet = facets.find((f) => f.name === node.facet);

  const selectable = facets.filter(
    (f) => f.name === node.facet || !(siblingFacets?.has(f.name) ?? false),
  );

  const noFacetLeft = !facets.some(
    (f) => f.name !== node.facet && !(siblingFacets?.has(f.name) ?? false),
  );

  return (
    <div className="fs-node">
      <div className="fs-node__head">
        <select
          className="fs-select"
          value={node.facet}
          disabled={disabled}
          aria-label="Facet"
          onChange={(e) => {
            onReplace(path, condition(e.target.value, node.operator, []));
          }}
        >
          {!facet && <option value={node.facet}>{node.facet} (unknown)</option>}
          {selectable.map((f) => (
            <option key={f.id} value={f.name}>
              {f.name}
            </option>
          ))}
        </select>

        <div className="fs-toggle" role="group" aria-label="Operator">
          {(['IN', 'NOT IN'] as const).map((op) => (
            <button
              key={op}
              type="button"
              aria-pressed={node.operator === op}
              disabled={disabled}
              onClick={() => onReplace(path, condition(node.facet, op, node.values))}
            >
              {op}
            </button>
          ))}
        </div>

        <span className="fs-node__spacer" />

        {!disabled && (
          <>
            <button
              className="fs-btn fs-btn--ghost fs-btn--sm"
              type="button"
              disabled={noFacetLeft}
              title={noFacetLeft ? EXHAUSTED_HINT : undefined}
              onClick={() => onAddSibling(path, 'AND')}
            >
              + AND
            </button>
            <button
              className="fs-btn fs-btn--ghost fs-btn--sm"
              type="button"
              disabled={noFacetLeft}
              title={noFacetLeft ? EXHAUSTED_HINT : undefined}
              onClick={() => onAddSibling(path, 'OR')}
            >
              + OR
            </button>
            <button
              className="fs-btn fs-btn--ghost fs-btn--sm"
              type="button"
              title="Wrap this condition in a group"
              onClick={() => onWrapInGroup(path)}
            >
              Group
            </button>
            {!isRoot && (
              <button
                className="fs-btn fs-btn--ghost fs-btn--sm"
                type="button"
                onClick={() => onRemove(path)}
                aria-label="Remove condition"
              >
                Remove
              </button>
            )}
          </>
        )}
      </div>

      <ValuePicker
        facet={facet}
        selected={node.values}
        disabled={disabled}
        onChange={(values) => onReplace(path, condition(node.facet, node.operator, values))}
      />
    </div>
  );
}

function GroupEditor(props: NodeEditorProps) {
  const { node, path, negated, disabled, onRemove, onToggleNegate, onAddSibling, isRoot } = props;

  const inner = isNot(node) ? node.operand : node;
  const innerPath = isNot(node) ? [...path, 0] : path;

  if (!isBinary(inner)) {
    return (
      <div className={negated ? 'fs-node fs-node--group' : 'fs-node'}>
        <div className="fs-node__head">
          {negated && <strong className="fs-tok-logical">NOT</strong>}
          <span className="fs-node__spacer" />
          {!disabled && (
            <button className="fs-btn fs-btn--ghost fs-btn--sm" type="button" onClick={() => onToggleNegate(path)}>
              {negated ? 'Un-negate' : 'Negate'}
            </button>
          )}
        </div>
        <div className="fs-node__children">
          <NodeEditor {...props} node={inner} path={innerPath} negated={undefined} isRoot={false} />
        </div>
      </div>
    );
  }

  const groupFacets = new Set(inner.operands.filter(isCondition).map((o) => o.facet));
  const groupIsFull = groupFacets.size >= props.facets.length;

  return (
    <div className="fs-node fs-node--group">
      <div className="fs-node__head">
        {negated && <strong className="fs-tok-logical">NOT</strong>}
        <span className="fs-meta">
          {inner.operator === 'AND' ? 'All of these must match' : 'Any of these must match'}
        </span>
        <span className="fs-node__spacer" />
        {!disabled && (
          <>
            <button
              className="fs-btn fs-btn--ghost fs-btn--sm"
              type="button"
              title={negated ? 'Remove the NOT around this group' : 'Negate this whole group'}
              onClick={() => onToggleNegate(path)}
            >
              {negated ? 'Un-negate' : 'Negate'}
            </button>
            {!isRoot && (
              <button className="fs-btn fs-btn--ghost fs-btn--sm" type="button" onClick={() => onRemove(path)}>
                Remove group
              </button>
            )}
          </>
        )}
      </div>

      <div className="fs-node__children">
        {inner.operands.map((child, i) => (
          <div key={i}>
            {i > 0 && (
              <div className="fs-joiner">
                <strong className="fs-tok-logical">{inner.operator}</strong>
              </div>
            )}
            <NodeEditor
              {...props}
              node={child}
              path={[...innerPath, i]}
              negated={undefined}
              isRoot={false}
              siblingFacets={
                isCondition(child)
                  ? new Set([...groupFacets].filter((f) => f !== child.facet))
                  : groupFacets
              }
            />
          </div>
        ))}

        {!disabled && (
          <div style={{ marginTop: 8 }}>
            <button
              className="fs-btn fs-btn--ghost fs-btn--sm"
              type="button"
              disabled={groupIsFull}
              title={
                groupIsFull
                  ? 'Every available facet is already used in this group. Add values to an existing condition instead.'
                  : undefined
              }
              onClick={() => onAddSibling([...innerPath, inner.operands.length - 1], inner.operator)}
            >
              + Add {inner.operator === 'AND' ? 'condition' : 'alternative'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
