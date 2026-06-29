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
import { MergeIcon } from '../primitives';
import { LivePlan } from './LivePlan';
import type { LivePlanNode } from './livePlanModel';

/**
 * The task-scoped left sidebar for the brain + stage pages (frames 2/6/7):
 * a back-to-thread escape hatch, the task identity block, and the live-plan
 * lifecycle diagram (the canonical "where is this task right now?" surface).
 * Replaces the global workspace rail while inside a task. Optional panel
 * actions render at the bottom.
 */
export function TaskSidebar({
  task, threadLabel, nodes, onBack, onOpenStage, onOpenCode, onOpenPr, onOpenBrain, actions,
}: {
  task: {
    taskNumber: number; title: string; branch: string;
    statusPill?: ReactNode; metaLine?: ReactNode; finished?: boolean;
  };
  threadLabel?: string;
  nodes: LivePlanNode[];
  onBack?: () => void;
  onOpenStage?: (stageId: string) => void;
  onOpenCode?: () => void;
  onOpenPr?: () => void;
  /** Navigate to the task's brain page — the Root node in the live plan. */
  onOpenBrain?: () => void;
  actions?: ReactNode;
}) {
  return (
    <aside className="task-sidebar">
      {onBack !== undefined && (
        <div className="back-row">
          <button type="button" className="back-to-thread" onClick={onBack}>
            <span className="arrow" aria-hidden>←</span>
            <span className="label">{threadLabel ?? 'Back to thread'}</span>
          </button>
        </div>
      )}
      <div className="task-identity">
        <div className="pill-row">
          <span className="task-num">▣ TASK #{task.taskNumber}</span>
          {task.statusPill !== undefined && <span className="status">{task.statusPill}</span>}
        </div>
        <div className="ti-title">{task.finished === true && <MergeIcon />}{task.title}</div>
        <div className="ti-branch">{task.branch}</div>
        {task.metaLine !== undefined && <div className="ti-meta">{task.metaLine}</div>}
      </div>
      <div className="plan-section-h"><span>Live plan</span><span className="live-dot" aria-hidden /></div>
      <LivePlan
        nodes={nodes}
        onOpenStage={onOpenStage}
        onOpenCode={onOpenCode}
        onOpenPr={onOpenPr}
        onOpenBrain={onOpenBrain}
      />
      {actions !== undefined && <div className="panel-actions">{actions}</div>}
    </aside>
  );
}
