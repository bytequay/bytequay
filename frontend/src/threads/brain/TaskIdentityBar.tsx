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
import type { TaskBrainViewData } from '../../types/brainView';

type Props = {
  task: TaskBrainViewData['task'];
  onBack: () => void;
  /** Open the linked PR in the in-app PR detail page. */
  onOpenPr?: () => void;
};

/**
 * The brain view's single-row identity bar: back button, TASK chip,
 * title, branch / PR / draft meta, the agent runtime+model pill, the
 * server-computed status pill, and a more menu. The brain view renders
 * no nav bar above this — the unibar is the top of the surface.
 */
export function TaskIdentityBar({ task, onBack, onOpenPr }: Props) {
  return (
    <div className="unibar">
      <button className="back" onClick={onBack} title="Back" aria-label="Back">
        <span className="wa" aria-hidden>B</span>
      </button>
      <span className="task-id">● TASK {task.taskNumber}</span>
      <span className="title">{task.title}</span>
      <span className="meta">
        <span className="sep" aria-hidden>·</span>
        <span className="branch">{task.branch}</span>
        {task.prNumber !== null && (
          <>
            <span className="sep" aria-hidden>·</span>
            <button
              className="pr"
              onClick={onOpenPr}
              disabled={onOpenPr === undefined}
              title={`Open PR #${task.prNumber}`}
            >
              PR #{task.prNumber} →
            </button>
          </>
        )}
        {task.prDraft && (
          <>
            <span className="sep" aria-hidden>·</span>
            <span>draft</span>
          </>
        )}
      </span>
      <span className="grow" />
      <span className="agent-pill" title="Agent runtime · model">
        <span className={`rt ${task.agentRuntime.toLowerCase()}`}>
          <span className="ic" aria-hidden>{task.agentRuntime === 'CLI' ? '⌘' : '⚡'}</span>
          {task.agentRuntime === 'CLI' ? 'CLAUDE CLI' : 'CLAUDE API'}
        </span>
        <span className="md">{task.agentModel}</span>
      </span>
      <span className="stat-pill"><span className="d" />{task.statusLabel}</span>
      <button className="icon-btn" title="More" aria-label="More actions">⋯</button>
    </div>
  );
}
