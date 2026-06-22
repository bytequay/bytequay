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
  return (
    <div style={pageStyle}>
      <div style={topBarStyle}>
        <button type="button" onClick={onBack} style={backBtnStyle}>
          ← Back
        </button>
        <span style={pageTitleStyle}>{title}</span>
      </div>
      <div style={paneWrapStyle}>
        <ThreadDiffPane threadId={threadId} />
      </div>
    </div>
  );
}

const pageStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  height: '100%',
  minHeight: 0,
};
const topBarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  padding: '10px 14px',
  borderBottom: '1px solid rgba(0,0,0,0.06)',
  flexShrink: 0,
};
const backBtnStyle: React.CSSProperties = {
  flexShrink: 0,
  padding: '5px 12px',
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--text-2)',
  background: 'rgba(0,0,0,0.04)',
  border: '1px solid rgba(0,0,0,0.08)',
  borderRadius: 7,
  cursor: 'pointer',
};
const pageTitleStyle: React.CSSProperties = {
  fontSize: 14,
  fontWeight: 600,
  color: 'var(--text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};
const paneWrapStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
};
