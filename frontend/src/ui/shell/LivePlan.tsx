/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import type { ReactNode } from 'react';
import type { LivePlanNode } from './livePlanModel';

/** One node button in the diagram. Disabled when it has nowhere to go (a
 *  future stage that hasn't been instantiated, or a milestone pseudo-node
 *  with no PR yet). */
function PlanNode({ node, onClick, small }: {
  node: LivePlanNode;
  onClick: () => void;
  small?: boolean;
}) {
  const cls = ['plan-node', node.status, node.activeView ? 'active-view' : '', small ? 'sm' : '']
    .filter(Boolean).join(' ');
  return (
    <button
      type="button"
      className={cls}
      onClick={onClick}
      disabled={node.nav.kind === 'none'}
      title={node.label}
    >
      <span className="pn-glyph" aria-hidden>{node.glyph}</span>
      <span className="pn-name">{node.label}</span>
      {node.meta !== undefined && <span className="pn-meta">{node.meta}</span>}
    </button>
  );
}

/**
 * The live-plan lifecycle diagram for the task-scoped sidebar (frames 2/6/7).
 * Renders the {@link buildLivePlan} node list as connected lifecycle nodes:
 * a vertical spine of full nodes, the indented Review (callable) sub-node,
 * and the parallel CI-Fix ‖ Comments split. Clicking a node navigates to its
 * stage (or the changes / PR surface for the Push / Merge milestones).
 */
export function LivePlan({ nodes, onOpenStage, onOpenCode, onOpenPr }: {
  nodes: LivePlanNode[];
  onOpenStage?: (stageId: string) => void;
  onOpenCode?: () => void;
  onOpenPr?: () => void;
}) {
  const click = (node: LivePlanNode) => () => {
    switch (node.nav.kind) {
      case 'stage': onOpenStage?.(node.nav.stageId); break;
      case 'code': onOpenCode?.(); break;
      case 'pr': onOpenPr?.(); break;
      default: break;
    }
  };

  const rows: ReactNode[] = [];
  let prev: LivePlanNode['placement'] | null = null;
  for (let i = 0; i < nodes.length; i += 1) {
    const node = nodes[i];
    if (node.placement === 'split-left') {
      const right = nodes[i + 1]?.placement === 'split-right' ? nodes[i + 1] : undefined;
      rows.push(
        <div className="plan-split" key={node.key}>
          <div className="split-glyph"><span className="split-icon">┌──┴──┐</span></div>
          <div className="split-row">
            <PlanNode node={node} onClick={click(node)} small />
            {right !== undefined && <PlanNode node={right} onClick={click(right)} small />}
          </div>
          <div className="converge-glyph"><span className="converge-icon">└──┬──┘</span></div>
        </div>,
      );
      if (right !== undefined) i += 1;
      prev = 'split-right';
      continue;
    }
    if (node.placement === 'sub') {
      rows.push(
        <div className="plan-sub-row" key={node.key}>
          <span className="branch-glyph" aria-hidden>└─</span>
          <PlanNode node={node} onClick={click(node)} small />
        </div>,
      );
      prev = 'sub';
      continue;
    }
    // Full node: connect it to the spine with a line unless it's the first
    // element or sits right under the split's converge glyph.
    if (prev === 'full' || prev === 'sub') {
      rows.push(<div className="plan-line" key={`${node.key}-line`} />);
    }
    rows.push(<PlanNode node={node} key={node.key} onClick={click(node)} />);
    prev = 'full';
  }

  return <div className="live-plan">{rows}</div>;
}
