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
import { initials } from '../../diff/DiffInlineComments';
import { Avatar, MergeIcon } from '../primitives';
import { BackChevronIcon, CheckIcon, TaskBranchIcon, ThreadBubbleIcon, UpArrowIcon } from '../TaskBrainDesignIcons';
import { LivePlan } from './LivePlan';
import type { GuardChipData, LivePlanNode } from './livePlanModel';
import { TrafficLights } from './Sidebar';

/**
 * The task-scoped left sidebar for the brain + stage pages (frames 2/6/7):
 * the window-chrome row (same {@link TrafficLights} every other surface
 * uses), the task identity block, and the live-plan lifecycle diagram (the
 * canonical "where is this task right now?" surface). Replaces the global
 * workspace rail while inside a task. Optional panel actions render at the
 * bottom.
 */
export function TaskSidebar({
  task, threadLabel, nodes, guard, defaultExpandPhases = false,
  onBack, onForward, backEnabled, forwardEnabled, onToggleCollapse,
  onOpenStage, onOpenCode, onOpenPr, onOpenTab, onOpenBrain, onOpenRun, onToggleGuard, actions, user,
}: {
  task: {
    title: string; branch: string;
    statusPill?: ReactNode; metaLine?: ReactNode; finished?: boolean;
  };
  threadLabel?: string;
  nodes: LivePlanNode[];
  guard?: GuardChipData;
  defaultExpandPhases?: boolean;
  /** Global nav-history back/forward (App.tsx's goBack/goForward) — the
   *  same semantics the main Sidebar's TrafficLights use, so back/forward
   *  behave identically whether or not a task sidebar is showing. */
  onBack?: () => void;
  onForward?: () => void;
  backEnabled?: boolean;
  forwardEnabled?: boolean;
  // ponytail: collapse toggle renders (matches the mockup chrome) but isn't
  // wired — .task-sidebar has no compact/icon-only rendering yet, unlike
  // the main Sidebar. Wire this once that variant exists.
  onToggleCollapse?: () => void;
  onOpenStage?: (stageId: string) => void;
  onOpenCode?: () => void;
  onOpenPr?: () => void;
  /** Force-switch the host page's own right-pane tab — the gate nodes
   *  (Local review / Remote pull request / Merge-Close) use this. `subTab`
   *  additionally forces the PR tab's own Checks sub-tab (CI validation). */
  onOpenTab?: (tab: 'pr', subTab?: 'checks') => void;
  /** Navigate to the task's brain page — the Plan node in the live plan. */
  onOpenBrain?: () => void;
  /** Navigate to a live run's own log — the Checks/Addressing sub-rows use this. */
  onOpenRun?: (runId: string) => void;
  /** Enable/disable the branch guard from its chip's toggle. */
  onToggleGuard?: (enabled: boolean) => void;
  actions?: ReactNode;
  /** GitHub login shown in the sidebar footer; omitted → no footer. */
  user?: string;
}) {
  const leaves = nodes.flatMap(n => (n.phases !== undefined && n.phases.length > 0 ? n.phases : [n]));
  const doneCount = leaves.filter(l => l.status === 'done').length;
  return (
    <aside className="task-sidebar">
      <TrafficLights
        onBack={onBack}
        onForward={onForward}
        backEnabled={backEnabled}
        forwardEnabled={forwardEnabled}
        onToggleCollapse={onToggleCollapse}
        hideNavArrows
      />
      {onBack !== undefined && (
        <div className="task-thread-row">
          <button type="button" className="task-thread-back" onClick={onBack} aria-label="Back">
            <BackChevronIcon />
          </button>
          <button type="button" className="task-thread-link" onClick={onBack}>
            <span className="task-thread-link__ic" aria-hidden><ThreadBubbleIcon /></span>
            <span className="task-thread-link__label">{threadLabel ?? 'Back to thread'}</span>
            <span className="task-thread-link__arr" aria-hidden><UpArrowIcon /></span>
          </button>
        </div>
      )}
      <div className="task-identity">
        {task.statusPill !== undefined && (
          <div className="pill-row">
            <span className="status">{task.statusPill}</span>
          </div>
        )}
        <span className="ti-icon" aria-hidden>
          <TaskBranchIcon />
        </span>
        <div className="ti-title">{task.finished === true && <MergeIcon />}{task.title}</div>
        <div className="ti-sub">
          <div className="ti-branch">{task.branch}</div>
          {task.metaLine !== undefined && (
            <div className={task.finished === true ? 'ti-meta ti-meta--done' : 'ti-meta'}>
              {task.finished === true && <CheckIcon size={11} strokeWidth={2.4} />}
              {task.metaLine}
            </div>
          )}
        </div>
      </div>
      <div className="plan-section-h">
        <span>Live plan</span>
        <span className="live-dot" aria-hidden />
        <span className="plan-count">{doneCount} of {leaves.length} done</span>
      </div>
      <LivePlan
        nodes={nodes}
        guard={guard}
        defaultExpandPhases={defaultExpandPhases}
        onOpenStage={onOpenStage}
        onOpenCode={onOpenCode}
        onOpenPr={onOpenPr}
        onOpenTab={onOpenTab}
        onOpenBrain={onOpenBrain}
        onOpenRun={onOpenRun}
        onToggleGuard={onToggleGuard}
      />
      {actions !== undefined && <div className="panel-actions">{actions}</div>}
      {user !== undefined && (
        <div className="ts-footer">
          <Avatar initials={initials(user)} hue="amber" />
          <span className="ts-footer__name">{user}</span>
        </div>
      )}
    </aside>
  );
}
