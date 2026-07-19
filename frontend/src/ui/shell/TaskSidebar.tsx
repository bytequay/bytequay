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
import type { AgentReviewData } from '../../review/agentReviewTypes';
import {
  CheckCircleIcon, ChevronIcon, TrunkLineIcon, WorkspaceChromeRow,
  WorkspaceGlobalRows, WorkspaceSidebarFooter, WorkspaceSwitcherCard,
} from '../workspace';
import type { GuardChipData, LivePlanNav, LivePlanNode, LivePlanPhaseNode } from './livePlanModel';

export type TaskAgentReviewTrack = {
  status: 'running' | 'questions' | 'complete' | 'stale' | 'errored';
  rounds: Array<{
    id: string;
    status: 'running' | 'questions' | 'complete' | 'cancelled' | 'errored';
    findings: number;
  }>;
  onOpenRound: (roundId: string) => void;
};

/** Kept as the route-level adapter for the existing review-round workflow. */
export function buildTaskAgentReviewTrack(
  data: AgentReviewData,
  onOpenRound: (roundId: string) => void,
): TaskAgentReviewTrack {
  const roundStatus = (status: string): TaskAgentReviewTrack['rounds'][number]['status'] =>
    status === 'RUNNING' || status === 'QUEUED' ? 'running'
      : status === 'COMPLETED_WITH_QUESTIONS' ? 'questions'
        : status === 'ERRORED' ? 'errored'
          : status === 'CANCELLED' ? 'cancelled'
            : 'complete';
  const latest = data.rounds.at(-1);
  const status: TaskAgentReviewTrack['status'] = data.review.status === 'STALE' ? 'stale'
    : data.rounds.some(round => round.status === 'RUNNING' || round.status === 'QUEUED') ? 'running'
      : latest?.status === 'ERRORED' ? 'errored'
        : latest?.status === 'COMPLETED_WITH_QUESTIONS'
          || data.findings.some(finding => finding.lifecycle_status === 'NEEDS_USER_JUDGEMENT')
          ? 'questions'
          : 'complete';
  return {
    status,
    rounds: data.rounds.map(round => ({
      id: round.id,
      status: roundStatus(round.status),
      findings: data.findings.filter(finding => finding.round_id === round.id
        && finding.lifecycle_status !== 'dropped').length,
    })),
    onOpenRound,
  };
}

type TaskSidebarTask = {
  title: string;
  branch: string;
  taskNumber?: number;
  repository?: string;
  workspaceName?: string;
  statusPill?: ReactNode;
  metaLine?: ReactNode;
  finished?: boolean;
};

/**
 * Locked task-page navigation shared by the brain and every stage page.
 * Lifecycle derivation stays in buildLivePlan; this component only translates
 * those nodes into the four design cards and dispatches their existing nav.
 */
export function TaskSidebar({
  task, threadLabel = 'Trunk', nodes, highlightActiveStage = true,
  onBack, onForward, backEnabled, forwardEnabled, onToggleCollapse, onOpenTrunk,
  onOpenStage, onOpenCode, onOpenPr, onOpenTab, onOpenBrain, onOpenRun,
  onNavigateGlobal, onSwitchWorkspace, onNotifications, notificationCount, user,
}: {
  task: TaskSidebarTask;
  threadLabel?: string;
  nodes: LivePlanNode[];
  guard?: GuardChipData;
  defaultExpandPhases?: boolean;
  highlightActiveStage?: boolean;
  onBack?: () => void;
  onForward?: () => void;
  backEnabled?: boolean;
  forwardEnabled?: boolean;
  onToggleCollapse?: () => void;
  /** Opens the owning trunk. Kept separate from the traffic-light history back action. */
  onOpenTrunk?: () => void;
  onOpenStage?: (stageId: string) => void;
  onOpenCode?: () => void;
  onOpenPr?: () => void;
  onOpenTab?: (tab: 'pr', subTab?: 'checks') => void;
  onOpenBrain?: () => void;
  onOpenRun?: (runId: string) => void;
  onToggleGuard?: (enabled: boolean) => void;
  agentReview?: TaskAgentReviewTrack;
  actions?: ReactNode;
  user?: string;
  onNavigateGlobal?: (destination: 'home' | 'workspaces') => void;
  onSwitchWorkspace?: () => void;
  onNotifications?: () => void;
  notificationCount?: number;
}) {
  const leaves = nodes.flatMap(node => node.phases?.length ? node.phases : [node]);
  const doneCount = leaves.filter(node => node.status === 'done').length;
  const totalCount = leaves.length;
  const repository = task.repository ?? '';
  const workspaceName = task.workspaceName
    ?? repository.split('/').filter(Boolean).at(-1)
    ?? 'Workspace';

  const navigate = (nav: LivePlanNav) => {
    switch (nav.kind) {
      case 'stage': onOpenStage?.(nav.stageId); break;
      case 'run': onOpenRun?.(nav.runId); break;
      case 'code': onOpenCode?.(); break;
      case 'pr': onOpenPr?.(); break;
      case 'tab': onOpenTab?.(nav.tab, nav.subTab); break;
      case 'brain': onOpenBrain?.(); break;
      case 'none': break;
    }
  };

  return (
    <aside className="task-sidebar workspace-task-sidebar-v2">
      <WorkspaceChromeRow
        onBack={onBack}
        onForward={onForward}
        backEnabled={backEnabled}
        forwardEnabled={forwardEnabled}
        onToggleSidebar={onToggleCollapse}
      />
      <WorkspaceGlobalRows onNavigate={onNavigateGlobal} />
      <WorkspaceSwitcherCard name={workspaceName} repository={repository || workspaceName}
        onSwitch={onSwitchWorkspace} />

      <div className="workspace-task-sidebar-v2__trunk-wrap">
        <button type="button" className="workspace-task-sidebar-v2__trunk" onClick={onOpenTrunk ?? onBack}>
          <ChevronIcon direction="left" size={13} />
          <span className="workspace-task-sidebar-v2__trunk-icon"><TrunkLineIcon size={14} /></span>
          <span>{threadLabel}</span>
          <small>trunk</small>
        </button>
      </div>

      <TaskIdentityCard task={task} done={doneCount} total={totalCount} />

      <div className="workspace-task-sidebar-v2__stages-heading">
        <span>STAGES</span>
        <small>{doneCount} of {totalCount} done</small>
      </div>
      <StagesList
        nodes={nodes}
        highlightActive={highlightActiveStage}
        onNavigate={navigate}
      />

      <WorkspaceSidebarFooter
        user={user}
        notificationCount={notificationCount}
        onNotifications={onNotifications}
      />
    </aside>
  );
}

function TaskIdentityCard({ task, done, total }: {
  task: TaskSidebarTask;
  done: number;
  total: number;
}) {
  const status = task.finished === true ? 'COMPLETED'
    : typeof task.metaLine === 'string' ? task.metaLine.toUpperCase()
      : task.statusPill ?? 'IN PROGRESS';
  return (
    <div className="workspace-task-card-wrap">
      <div className="workspace-task-card">
        <div className="workspace-task-card__heading">
          <span className="workspace-task-card__brain" aria-hidden><BrainIcon /></span>
          <strong>TASK #{task.taskNumber ?? 1}</strong>
          <span className={task.finished === true ? 'is-complete' : ''}>
            {task.finished === true && <SmallCheckIcon />}{status}
          </span>
        </div>
        <div className="workspace-task-card__title">{task.title}</div>
        <div className="workspace-task-card__meta">
          <code>{task.branch}</code>
          <small>{done} / {total}</small>
        </div>
      </div>
    </div>
  );
}

export function StagesList({ nodes, highlightActive = true, onNavigate }: {
  nodes: LivePlanNode[];
  highlightActive?: boolean;
  onNavigate: (nav: LivePlanNav) => void;
}) {
  return (
    <div className="workspace-task-stages">
      {nodes.map(node => {
        const cleanup = node.key === 'cleanup' || node.nodeType === 'auto';
        const active = highlightActive && node.activeView && !cleanup;
        const disabled = cleanup || node.nav.kind === 'none';
        const title = cleanup ? 'Runs automatically — no agent page' : 'Open agent page';
        return (
          <button
            type="button"
            key={node.key}
            className={`workspace-task-stage tone-${stageTone(node)}${active ? ' is-active' : ''}${cleanup ? ' is-automatic' : ''}`}
            title={title}
            disabled={disabled}
            onClick={() => onNavigate(node.nav)}
          >
            <span className="workspace-task-stage__top">
              <StageStatusIcon node={node} />
              <strong>{node.label}</strong>
              <small className={`is-${node.status}`}>{stageStatusLabel(node)}</small>
              {!cleanup && <ChevronIcon size={11} />}
            </span>
            <span className="workspace-task-stage__metric">{stageMetric(node)}</span>
            {active && node.phases !== undefined && node.phases.length > 0 && (
              <span className="workspace-task-stage__plan">
                {node.phases.map(phase => (
                  <StagePlanRow key={phase.key} phase={phase} onNavigate={onNavigate} />
                ))}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

function StagePlanRow({ phase, onNavigate }: {
  phase: LivePlanPhaseNode;
  onNavigate: (nav: LivePlanNav) => void;
}) {
  return (
    <span
      className="workspace-task-stage__plan-row"
      role={phase.nav.kind === 'none' ? undefined : 'button'}
      tabIndex={phase.nav.kind === 'none' ? undefined : 0}
      onClick={event => {
        if (phase.nav.kind === 'none') return;
        event.stopPropagation();
        onNavigate(phase.nav);
      }}
      onKeyDown={event => {
        if (phase.nav.kind === 'none' || (event.key !== 'Enter' && event.key !== ' ')) return;
        event.preventDefault();
        event.stopPropagation();
        onNavigate(phase.nav);
      }}
    >
      <span aria-hidden><SmallCheckIcon /></span>
      <span>{phase.label}</span>
      {phase.meta !== undefined && <small>{phase.meta}</small>}
    </span>
  );
}

function stageStatusLabel(node: LivePlanNode): string {
  if (node.key === 'plan' && node.status === 'done') return 'approved';
  if (node.key === 'remote-development' && node.status === 'done') return 'merged';
  switch (node.status) {
    case 'done': return 'done';
    case 'planning': return 'planning';
    case 'running': return 'running';
    case 'monitoring': return 'monitoring';
    case 'awaiting': return 'needs you';
    case 'errored': return 'failed';
    case 'sleep': return 'idle';
    case 'future': return 'queued';
  }
}

function stageMetric(node: LivePlanNode): string {
  if (node.meta !== undefined && node.meta.trim().length > 0) return node.meta;
  if (node.key === 'cleanup' && node.status === 'done') return 'branch deleted · refs clean';
  const complete = node.phases?.filter(phase => phase.status === 'done').length ?? 0;
  if (node.phases !== undefined && node.phases.length > 0) {
    return `${complete} of ${node.phases.length} steps`;
  }
  return node.status === 'future' ? 'Not started' : stageStatusLabel(node);
}

function stageTone(node: LivePlanNode): 'plan' | 'local' | 'remote' | 'cleanup' {
  if (node.key === 'plan') return 'plan';
  if (node.key === 'remote-development') return 'remote';
  if (node.key === 'cleanup' || node.nodeType === 'auto') return 'cleanup';
  return 'local';
}

function StageStatusIcon({ node }: { node: LivePlanNode }) {
  const { status } = node;
  if (node.key === 'cleanup' || node.nodeType === 'auto') {
    return status === 'done'
      ? <span className="workspace-task-stage__status-icon is-cleanup"><CheckCircleIcon /></span>
      : <span className="workspace-task-stage__status-icon is-idle"><i /></span>;
  }
  if (status === 'done') return <span className="workspace-task-stage__status-icon is-done"><CheckCircleIcon /></span>;
  if (status === 'running' || status === 'planning' || status === 'monitoring' || status === 'awaiting') {
    return <span className={`workspace-task-stage__status-icon is-${status}`}><i /></span>;
  }
  return <span className="workspace-task-stage__status-icon is-idle"><i /></span>;
}

function BrainIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 3a4 4 0 0 0-4 4 3.5 3.5 0 0 0-2 6.5A3.5 3.5 0 0 0 9 20a3 3 0 0 0 6 0 3.5 3.5 0 0 0 3-6.5A3.5 3.5 0 0 0 16 7a4 4 0 0 0-4-4Z" />
      <path d="M12 3v18" />
    </svg>
  );
}

function SmallCheckIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="m4.5 12.5 5 5 10-11" />
    </svg>
  );
}
