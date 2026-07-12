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
import { useState, type ReactNode } from 'react';
import Toggle from '../../settings/shared/Toggle';
import {
  BranchIcon, BroomIcon, CheckIcon, ChevronRightIcon, CloudIcon, PlanIcon, ShieldIcon,
} from '../TaskBrainDesignIcons';
import type { GuardChipData, LivePlanNode, LivePlanPhaseNode } from './livePlanModel';

/** One node button in the diagram. Disabled when it has nowhere to go (a
 *  future stage that hasn't been instantiated, or a milestone pseudo-node
 *  with no PR yet). `nt-<nodeType>` carries the R26/R30 node-type styling
 *  (gates render dashed, matching stage-phase-rail.html's `.nd.gate`).
 *  `toggle`, when present (a lifecycle node with phase rows), renders a
 *  separate small chevron button alongside the main one — the main button
 *  always navigates, the chevron expands/collapses the phase ladder, so
 *  neither affordance shadows the other. */
function PlanNode({ node, onClick, small, toggle }: {
  node: LivePlanNode;
  onClick: () => void;
  small?: boolean;
  toggle?: { expanded: boolean; onToggle: () => void };
}) {
  const cls = [
    'plan-node', node.status, `nt-${node.nodeType}`, node.activeView ? 'active-view' : '', small ? 'sm' : '',
  ].filter(Boolean).join(' ');
  const content = (
    <>
      <span className={markClass(node)} aria-hidden>{markIcon(node.status, node.key)}</span>
      <span className={`pn-kind pn-kind--${node.key}`} aria-hidden>{kindIcon(node)}</span>
      <span className="pn-name">{node.label}</span>
      {node.meta !== undefined && <span className="pn-meta">{node.meta}</span>}
    </>
  );
  if (toggle === undefined) {
    return (
      <button type="button" className={cls} onClick={onClick} disabled={node.nav.kind === 'none'} title={node.label}>
        {content}
      </button>
    );
  }
  // The chevron rides inside the same pill as the nav button (one shared
  // border/background by status) instead of a separate box beside it — the
  // two stay independent click targets, they just no longer look like it.
  return (
    <div className={`${cls} has-toggle`}>
      <button
        type="button"
        className="plan-node-nav"
        onClick={onClick}
        disabled={node.nav.kind === 'none'}
        title={node.label}
      >
        {content}
      </button>
      <button
        type="button"
        className="plan-node-toggle"
        onClick={toggle.onToggle}
        aria-expanded={toggle.expanded}
        aria-label={toggle.expanded ? `Collapse ${node.label}` : `Expand ${node.label}`}
      >
        <ChevronRightIcon />
      </button>
    </div>
  );
}

function markClass(node: LivePlanNode): string {
  return node.status === 'done' && node.key === 'local-development' ? 'pn-glyph pn-glyph--done-ring' : 'pn-glyph';
}

function markIcon(status: LivePlanNode['status'], nodeKey: string): ReactNode {
  if (status === 'done') {
    return <CheckIcon size={nodeKey === 'local-development' ? 12 : 13} strokeWidth={2.6} />;
  }
  if (status === 'running' || status === 'monitoring' || status === 'planning' || status === 'awaiting') {
    return <span className="pn-dot" />;
  }
  return null;
}

function kindIcon(node: LivePlanNode): ReactNode {
  switch (node.key) {
    case 'plan': return <PlanIcon />;
    case 'local-development': return <BranchIcon />;
    case 'remote-development': return <CloudIcon />;
    case 'cleanup': return <BroomIcon />;
    default: return null;
  }
}

/** One row of a lifecycle node's phase ladder — nested beneath its parent
 *  with a left accent border, matching stage-phase-rail.html's `.ph`
 *  treatment. */
function PhaseRow({ phase, onClick }: { phase: LivePlanPhaseNode; onClick: () => void }) {
  const cls = ['plan-phase-row', phase.status].filter(Boolean).join(' ');
  return (
    <button type="button" className={cls} onClick={onClick} disabled={phase.nav.kind === 'none'} title={phase.label}>
      <span className="ph-glyph" aria-hidden>{markIcon(phase.status, phase.key)}</span>
      <span className="ph-name">{phase.label}</span>
      {phase.badge !== undefined && <span className="ph-badge">{phase.badge}</span>}
      {phase.meta !== undefined && (
        <span className={phase.meta.startsWith('PR #') ? 'ph-meta ph-meta--pr' : 'ph-meta'}>{phase.meta}</span>
      )}
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
      <span className="guard-icon" aria-hidden><ShieldIcon /></span>
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
 * Clicking a node navigates to its stage (or the PR surface for milestone
 * rows). A node with a phase ladder additionally renders a small chevron
 * button that expands/collapses the ladder in place, independent of
 * navigation.
 */
export function LivePlan({
  nodes, guard, defaultExpandPhases = false,
  onOpenStage, onOpenCode, onOpenPr, onOpenTab, onOpenBrain, onOpenRun, onToggleGuard,
}: {
  nodes: LivePlanNode[];
  guard?: GuardChipData;
  defaultExpandPhases?: boolean;
  onOpenStage?: (stageId: string) => void;
  onOpenCode?: () => void;
  onOpenPr?: () => void;
  /** Force-switch the host page's own right-pane tab (e.g. Local review /
   *  Remote pull request / Merge-Close all open the PR tab in place, R27).
   *  `subTab` additionally forces the PR tab's own Checks sub-tab (CI
   *  validation). */
  onOpenTab?: (tab: 'pr', subTab?: 'checks') => void;
  /** Navigate to the task's brain page — the Plan node uses this. */
  onOpenBrain?: () => void;
  /** Navigate to a live run's own log — the Checks/Addressing sub-rows use this. */
  onOpenRun?: (runId: string) => void;
  /** Enable/disable the branch guard from its chip's toggle. */
  onToggleGuard?: (enabled: boolean) => void;
}) {
  const navigate = (nav: LivePlanNode['nav']) => {
    switch (nav.kind) {
      case 'stage': onOpenStage?.(nav.stageId); break;
      case 'code': onOpenCode?.(); break;
      case 'pr': onOpenPr?.(); break;
      case 'tab': onOpenTab?.(nav.tab, nav.subTab); break;
      case 'brain': onOpenBrain?.(); break;
      case 'run': onOpenRun?.(nav.runId); break;
      default: break;
    }
  };
  const click = (node: LivePlanNode) => () => navigate(node.nav);

  // Explicit user toggles for a node's phase ladder, keyed by node key.
  // Absent = collapsed — the ladder only opens once the user clicks its
  // chevron, live or not; it used to auto-expand while the node was
  // running, which buried the plan under every in-progress node's steps.
  const [phaseToggles, setPhaseToggles] = useState<Record<string, boolean>>({});
  const phasesExpanded = (node: LivePlanNode): boolean =>
    phaseToggles[node.key] ?? defaultExpandPhases;

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
    const hasPhases = node.phases !== undefined && node.phases.length > 0;
    const expanded = hasPhases && phasesExpanded(node);
    const toggle = hasPhases
      ? { expanded, onToggle: () => setPhaseToggles(prevToggles => ({ ...prevToggles, [node.key]: !expanded })) }
      : undefined;
    rows.push(
      <PlanNode node={node} key={node.key} onClick={click(node)} toggle={toggle} />,
    );
    prev = 'full';
    if (expanded && node.phases !== undefined) {
      for (const phase of node.phases) {
        rows.push(
          <PhaseRow phase={phase} key={`${node.key}-${phase.key}`} onClick={() => navigate(phase.nav)} />,
        );
      }
      prev = 'sub';
    }
  }

  return (
    <div className="live-plan">
      <GuardChip guard={guard ?? null} onToggle={onToggleGuard} />
      <div className="plan-tree">{rows}</div>
    </div>
  );
}
