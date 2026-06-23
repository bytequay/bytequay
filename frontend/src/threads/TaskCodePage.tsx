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
import { useMemo } from 'react';
import { ThreadDiffPane } from './ThreadChangesTab';
import { useThreadTasks } from './useThreadTasks';

/**
 * Standalone "Code" page for a task — the diff/files viewer reached from
 * the brain view and the stage detail's "View code diff". A back-bar on
 * top, with the shared {@link ThreadDiffPane} filling the rest so the
 * task's working-tree changes and commits render with the same file-tree
 * pane, status badges, and {@code .diff-row*} body as the PR review's
 * {@code DiffViewerScreen} — one diff UI across the app, not a bespoke
 * copy. The task's diff is thread-scoped, which is exactly what
 * ThreadDiffPane reads.
 */
export default function TaskCodePage({
  threadId, taskId, onBack,
}: {
  threadId: string;
  taskId: string;
  onBack: () => void;
}) {
  const { tasks } = useThreadTasks(threadId);
  const task = useMemo(() => tasks?.find(t => t.id === taskId) ?? null, [tasks, taskId]);
  const title = task === null
    ? 'Loading…'
    : task.name ?? task.branchName ?? `Task ${task.seq}`;
  // Same shell as the PR review's DiffViewerScreen (.diff-viewer toolbar +
  // body) so the standalone task diff page reads as one UI with it. Only
  // the toolbar buttons differ — a task diff has no Approve / Run AI review
  // / publish controls, just Back.
  return (
    // .diff-viewer is position:absolute/inset:0 — give it a positioned,
    // full-height host since .app-content isn't a positioning context.
    <div style={{ position: 'relative', height: '100%', minHeight: 0 }}>
      <div className="diff-viewer">
        <div className="diff-viewer__toolbar">
          <button className="button button--secondary" onClick={onBack} type="button">
            ← Back
          </button>
          <div className="diff-viewer__title">
            {task?.branchName != null && (
              <span className="diff-viewer__repo">⎇ {task.branchName}</span>
            )}
            <span className="diff-viewer__pr-title">{title}</span>
          </div>
        </div>
        <ThreadDiffPane threadId={threadId} flush />
      </div>
    </div>
  );
}
