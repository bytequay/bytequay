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
import { useState } from 'react';
import type { ReactNode } from 'react';
import type { AgentReviewData } from '../../review/agentReviewTypes';
import {
  CheckCircleIcon, ChevronIcon, TrunkLineIcon, WorkspaceBottomNav,
  WorkspacePrimaryNav, WorkspaceSwitcherCard,
} from '../workspace';
import type { WsNavKey } from '../workspace';
import { TrafficLights } from './Sidebar';
import type { GuardChipData, LivePlanNav, LivePlanNode, LivePlanPhaseNode, LivePlanStatus } from './livePlanModel';
import { useThreadTasks } from '../../threads/useThreadTasks';
import { taskLabel } from '../../threads/taskLabel';
import type { WorkUnitTaskDto } from '../../types';

/** Active = still in flight: not completed/errored and the dev-lifecycle
 *  phase hasn't reached COMPLETED. Keeps IN_REVIEW (shipped, awaiting merge)
 *  visible for navigation, unlike the trunk's tighter latest-active test. */
export function isTaskActive(t: WorkUnitTaskDto): boolean {
  return t.phase !== 'COMPLETED' && t.status !== 'COMPLETED' && t.status !== 'ERRORED';
}

/** The active tasks to list under the trunk, capped at `cap` while collapsed.
 *  The current task is always kept in view even when it isn't active or would
 *  fall past the cap. Input is oldest-seq first (the hook's order). */
export function selectSidebarTasks(
  tasks: WorkUnitTaskDto[], currentTaskId: string | undefined, cap: number, expanded: boolean,
): { visible: WorkUnitTaskDto[]; hiddenCount: number } {
  const list = tasks.filter(t => isTaskActive(t) || t.id === currentTaskId);
  if (expanded || list.length <= cap) return { visible: list, hiddenCount: 0 };
  let visible = list.slice(0, cap);
  if (currentTaskId !== undefined && !visible.some(t => t.id === currentTaskId)) {
    const cur = list.find(t => t.id === currentTaskId);
    if (cur !== undefined) visible = [...list.slice(0, cap - 1), cur];
  }
  return { visible, hiddenCount: list.length - visible.length };
}

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
 * Task-page navigation shared by the brain and every stage page. It uses the
 * same flat row hierarchy as the workspace rail; only the row labels differ.
 */
export function TaskSidebar({
  task, threadLabel = 'Trunk', nodes, highlightActiveStage = true,
  threadId, currentTaskId, onOpenTask,
  onBack, onForward, backEnabled, forwardEnabled, onToggleCollapse, onOpenTrunk,
  onOpenStage, onOpenPr, onOpenTab, onOpenBrain, onOpenRun,
  onNavigateGlobal, onSwitchWorkspace,
}: {
  task: TaskSidebarTask;
  threadLabel?: string;
  nodes: LivePlanNode[];
  /** Owning trunk — drives the sibling-task list. Omit to keep the single
   *  current-task row (visual fixtures do). */
  threadId?: string;
  currentTaskId?: string;
  onOpenTask?: (taskId: string) => void;
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
  onOpenPr?: () => void;
  onOpenTab?: (tab: 'pr', subTab?: 'overview' | 'checks' | 'changes') => void;
  onOpenBrain?: () => void;
  onOpenRun?: (runId: string) => void;
  onToggleGuard?: (enabled: boolean) => void;
  agentReview?: TaskAgentReviewTrack;
  actions?: ReactNode;
  onNavigateGlobal?: (destination: WsNavKey) => void;
  onSwitchWorkspace?: () => void;
}) {
  const leaves = nodes.flatMap(node => node.phases?.length ? node.phases : [node]);
  const doneCount = leaves.filter(node => node.status === 'done').length;
  const totalCount = leaves.length;
  const { tasks: siblingTasks } = useThreadTasks(threadId ?? '');
  const repository = task.repository ?? '';
  const workspaceName = task.workspaceName
    ?? repository.split('/').filter(Boolean).at(-1)
    ?? 'Workspace';

  const navigate = (nav: LivePlanNav) => {
    switch (nav.kind) {
      case 'stage': onOpenStage?.(nav.stageId); break;
      case 'run': onOpenRun?.(nav.runId); break;
      case 'pr': onOpenPr?.(); break;
      case 'tab': onOpenTab?.(nav.tab, nav.subTab); break;
      case 'brain': onOpenBrain?.(); break;
      case 'none': break;
    }
  };

  return (
    <aside className="sidebar task-sidebar workspace-task-sidebar-v2">
      <TrafficLights
        onBack={onBack}
        onForward={onForward}
        backEnabled={backEnabled}
        forwardEnabled={forwardEnabled}
        onToggleCollapse={onToggleCollapse}
      />
      <WorkspacePrimaryNav activeNav="trunks" onNavigate={onNavigateGlobal} />
      <WorkspaceSwitcherCard name={workspaceName} repository={repository || workspaceName}
        onSwitch={onSwitchWorkspace} />

      <div className="workspace-task-sidebar-v2__trunk-wrap">
        <button type="button" className="workspace-task-sidebar-v2__trunk" onClick={onOpenTrunk ?? onBack}>
          <span className="workspace-task-sidebar-v2__trunk-icon"><TrunkLineIcon size={14} /></span>
          <span>{threadLabel}</span>
          <small>trunk</small>
        </button>
      </div>

      {onOpenTask !== undefined && siblingTasks !== null && siblingTasks.length > 0
        ? <TaskList tasks={siblingTasks} currentTaskId={currentTaskId} onOpenTask={onOpenTask} />
        : <TaskIdentityRow task={task} />}

      <div className="workspace-task-sidebar-v2__stages-heading">
        <span>STAGES</span>
        <small>{doneCount} of {totalCount} done</small>
      </div>
      <StagesList
        nodes={nodes}
        highlightActive={highlightActiveStage}
        onNavigate={navigate}
      />

      <WorkspaceBottomNav activeNav="trunks" onNavigate={onNavigateGlobal} workspaceMode />
    </aside>
  );
}

function TaskIdentityRow({ task }: { task: TaskSidebarTask }) {
  return (
    <div className="workspace-task-row-wrap">
      <div className="workspace-task-row" title={task.title}
        aria-label={`Task ${task.taskNumber ?? 1}: ${task.title}`}>
        <span aria-hidden><BrainIcon /></span>
        <span>{task.title}</span>
      </div>
    </div>
  );
}

/** The active tasks under the trunk. Caps at three; "Load more" reveals the
 *  rest so a busy trunk's rail doesn't push the stages out of view. */
function TaskList({ tasks, currentTaskId, onOpenTask }: {
  tasks: WorkUnitTaskDto[];
  currentTaskId?: string;
  onOpenTask: (taskId: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const { visible, hiddenCount } = selectSidebarTasks(tasks, currentTaskId, 3, expanded);
  return (
    <div className="workspace-task-row-wrap">
      {visible.map(t => {
        const label = taskLabel(t);
        const current = t.id === currentTaskId;
        return (
          <button
            key={t.id}
            type="button"
            className={`workspace-task-row${current ? '' : ' is-sibling'}`}
            title={label}
            aria-current={current ? 'true' : undefined}
            aria-label={`Task ${t.seq}: ${label}`}
            onClick={() => onOpenTask(t.id)}
          >
            <span aria-hidden><BrainIcon /></span>
            <span>{label}</span>
          </button>
        );
      })}
      {hiddenCount > 0 && (
        <button type="button" className="workspace-task-row-more" onClick={() => setExpanded(true)}>
          Load more ({hiddenCount})
        </button>
      )}
    </div>
  );
}

export function StagesList({ nodes, highlightActive = true, onNavigate }: {
  nodes: LivePlanNode[];
  highlightActive?: boolean;
  onNavigate: (nav: LivePlanNav) => void;
}) {
  // Explicit per-node fold state for the phase ladder, keyed by node key.
  // Grouped stages start collapsed so the rail stays compact.
  const [openPhases, setOpenPhases] = useState<Record<string, boolean>>({});
  return (
    <div className="workspace-task-stages">
      {nodes.map(node => {
        const cleanup = node.key === 'cleanup' || node.nodeType === 'auto';
        const active = highlightActive && node.activeView && !cleanup;
        const hasPhases = !cleanup && node.phases !== undefined && node.phases.length > 0;
        const disabled = cleanup || (!hasPhases && node.nav.kind === 'none');
        const open = openPhases[node.key] ?? false;
        const title = cleanup
          ? 'Runs automatically — no agent page'
          : hasPhases
            ? open
              ? 'Collapse sub-stages'
              : node.nav.kind === 'none' ? 'Expand sub-stages' : 'Open agent page and expand sub-stages'
            : 'Open agent page';
        return (
          <div className="workspace-task-stage-group" key={node.key}>
            <div className={`workspace-task-stage-row${active ? ' is-active' : ''}`}>
              <button
                type="button"
                className={`workspace-task-stage tone-${stageTone(node)}${active ? ' is-active' : ''}${cleanup ? ' is-automatic' : ''}${hasPhases ? ' has-phases' : ''}${open ? ' is-open' : ''}`}
                title={title}
                disabled={disabled}
                aria-expanded={hasPhases ? open : undefined}
                onClick={() => {
                  if (hasPhases) {
                    setOpenPhases(prev => ({ ...prev, [node.key]: !open }));
                    if (!open) onNavigate(node.nav);
                    return;
                  }
                  onNavigate(node.nav);
                }}
              >
                <StageStatusIcon node={node} />
                <span className="workspace-task-stage__copy">
                  <strong>{node.label}</strong>
                  <span>{stageMetric(node)}</span>
                </span>
                <small className={`is-${node.status}`}>{stageStatusLabel(node)}</small>
                {!cleanup && <ChevronIcon size={11} />}
              </button>
            </div>
            {open && hasPhases && node.phases !== undefined && (
              <div className="workspace-task-stage__plan">
                {node.phases.map(phase => (
                  <StagePlanRow key={phase.key} phase={phase} onNavigate={onNavigate} />
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function StagePlanRow({ phase, onNavigate }: {
  phase: LivePlanPhaseNode;
  onNavigate: (nav: LivePlanNav) => void;
}) {
  const disabled = phase.nav.kind === 'none';
  return (
    <button
      type="button"
      className="workspace-task-stage__plan-row"
      disabled={disabled}
      onClick={() => { if (!disabled) onNavigate(phase.nav); }}
    >
      <PhaseStatusIcon status={phase.status} />
      <span>{phase.label}</span>
      {phase.meta !== undefined && <small>{phase.meta}</small>}
    </button>
  );
}

/** Sub-step status marker, mirroring the top-level {@link StageStatusIcon}:
 *  green ✓ when done, a pulsing dot while active, amber when it needs the user,
 *  a gray dot otherwise. Reuses the rail's status-icon CSS so a phase's real
 *  status shows through instead of every row reading as a finished check. */
function PhaseStatusIcon({ status }: { status: LivePlanStatus }) {
  const cls = status === 'done' ? 'is-done'
    : status === 'running' || status === 'planning' || status === 'monitoring' || status === 'awaiting'
      ? `is-${status}`
      : 'is-idle';
  return (
    <span className={`workspace-task-stage__status-icon ${cls}`} aria-hidden>
      {status === 'done' ? <SmallCheckIcon /> : <i />}
    </span>
  );
}

function stageStatusLabel(node: LivePlanNode): string {
  if (node.key === 'plan' && node.status === 'done') return 'approved';
  if (node.key === 'remote-development' && node.status === 'done') {
    return node.meta === 'closed' ? 'closed' : 'merged';
  }
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
