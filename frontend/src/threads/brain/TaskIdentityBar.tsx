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

/** Compact GitHub mark for the branch chip. */
function GithubMark() {
  return (
    <svg viewBox="0 0 16 16" width={12} height={12} role="img" aria-label="GitHub repo" fill="currentColor">
      <path fillRule="evenodd" clipRule="evenodd" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.66 7.66 0 014 0c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" />
    </svg>
  );
}

/**
 * The brain view's single-row identity bar: back button, TASK chip,
 * title, branch / PR / draft meta, the agent runtime+model pill, the
 * server-computed status pill, and a more menu. The brain view renders
 * no nav bar above this — the unibar is the top of the surface.
 */
export function TaskIdentityBar({ task, onBack, onOpenPr }: Props) {
  return (
    <div className={`unibar${task.terminal ? ' unibar--terminal' : ''}`}>
      <button className="back" onClick={onBack} title="Back" aria-label="Back">
        <span className="wa" aria-hidden>B</span>
      </button>
      <span className="task-id">● TASK {task.taskNumber}</span>
      <span className="title">{task.title}</span>
      <span className="meta">
        <span className="branch-chip" title={`Branch ${task.branch}`}>
          <GithubMark />
          <span className="branch">{task.branch}</span>
        </span>
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
      <span className={`stat-pill${task.terminal ? ' stat-pill--terminal' : ''}`}><span className="d" />{task.statusLabel}</span>
      <button className="icon-btn" title="More" aria-label="More actions">⋯</button>
    </div>
  );
}
