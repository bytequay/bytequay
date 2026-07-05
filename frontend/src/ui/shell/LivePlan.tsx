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
import Toggle from '../../settings/shared/Toggle';
import type { GuardChipData, LivePlanNode } from './livePlanModel';

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

/** The guard chip rendered above the rail (R4, plan-spine-options.html) —
 *  hidden entirely until the task has pushed ({@link buildGuardChip} returns
 *  null until then); shown dimmed with a toggle while disabled so the user
 *  can arm it. */
function GuardChip({ guard, onToggle }: { guard: GuardChipData; onToggle?: (enabled: boolean) => void }) {
  if (guard === null) return null;
  return (
    <div className={`guard-chip ${guard.enabled ? guard.state : 'off'}`}>
      <span className="guard-icon" aria-hidden>🛡</span>
      <span className="guard-lb">Guard</span>
      <span className="guard-label">{guard.label}</span>
      <span className="guard-right">
        {guard.meta !== null && <span className="guard-meta">{guard.meta}</span>}
        <Toggle
          on={guard.enabled}
          onChange={next => onToggle?.(next)}
          ariaLabel={guard.enabled ? 'Disable branch guard' : 'Enable branch guard'}
        />
      </span>
    </div>
  );
}

/**
 * The live-plan lifecycle diagram for the task-scoped sidebar (frames 2/6/7).
 * Renders the {@link buildLivePlan} node list as a flat spine of full nodes
 * with lazy `sub` rows (Review callable, live Checks/Addressing runs)
 * indented beneath their parent, plus the branch-guard chip above the rail.
 * Clicking a node navigates to its stage (or the changes / PR surface for
 * the Push / Merge milestones).
 */
export function LivePlan({
  nodes, guard, onOpenStage, onOpenCode, onOpenPr, onOpenBrain, onOpenRun, onToggleGuard,
}: {
  nodes: LivePlanNode[];
  guard?: GuardChipData;
  onOpenStage?: (stageId: string) => void;
  onOpenCode?: () => void;
  onOpenPr?: () => void;
  /** Navigate to the task's brain page — the Root node uses this. */
  onOpenBrain?: () => void;
  /** Navigate to a live run's own log — the Checks/Addressing sub-rows use this. */
  onOpenRun?: (runId: string) => void;
  /** Enable/disable the branch guard from its chip's toggle. */
  onToggleGuard?: (enabled: boolean) => void;
}) {
  const click = (node: LivePlanNode) => () => {
    switch (node.nav.kind) {
      case 'stage': onOpenStage?.(node.nav.stageId); break;
      case 'code': onOpenCode?.(); break;
      case 'pr': onOpenPr?.(); break;
      case 'brain': onOpenBrain?.(); break;
      case 'run': onOpenRun?.(node.nav.runId); break;
      default: break;
    }
  };

  const rows: ReactNode[] = [];
  let prev: LivePlanNode['placement'] | null = null;
  for (const node of nodes) {
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
    // Full node: connect it to the spine with a line unless it's the first.
    if (prev === 'full' || prev === 'sub') {
      rows.push(<div className="plan-line" key={`${node.key}-line`} />);
    }
    rows.push(<PlanNode node={node} key={node.key} onClick={click(node)} />);
    prev = 'full';
  }

  return (
    <div className="live-plan">
      <GuardChip guard={guard ?? null} onToggle={onToggleGuard} />
      {rows}
    </div>
  );
}
