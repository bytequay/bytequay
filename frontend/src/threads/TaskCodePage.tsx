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
import { useCallback, useEffect, useMemo, useState } from 'react';
import { ContinuousDiff, FileDiffBody } from '../diff/DiffFileList';
import { DiffFileTreePane, type FilesPaneMode } from '../diff/DiffFileTreePane';
import { statusBadge } from '../diffStatusBadge';
import { relativeTime } from '../notificationDisplay';
import type { DiffFileDto, ThreadCommitDto } from '../types';
import { useThreadTasks } from './useThreadTasks';

/**
 * Standalone "Code" page for a task — the diff/files viewer reached from
 * the brain view and the stage detail's "View code diff". It renders the
 * task's diff with the **same component** as the PR review's
 * {@code DiffViewerScreen}: the shared {@link ContinuousDiff} /
 * {@link FileDiffBody} continuous multi-file body, {@link DiffFileTreePane}
 * file tree, and {@code .diff-viewer} shell. So PR-diff rendering changes
 * propagate here automatically. Read-only (no review draft to comment to);
 * the shared renderer keeps its interactive hooks, simply unused here.
 *
 * Default view is the task's cumulative diff (every commit, base..HEAD);
 * the commits column on the left scopes to a single commit.
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

  const [commits, setCommits] = useState<ThreadCommitDto[] | null>(null);
  // null selectedSha ⇒ cumulative (all commits); otherwise a single commit.
  const [selectedSha, setSelectedSha] = useState<string | null>(null);
  const [files, setFiles] = useState<DiffFileDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [mode, setMode] = useState<FilesPaneMode>('tree');
  const [collapsedDirs, setCollapsedDirs] = useState<Set<string>>(() => new Set());

  // Commit list for the left column.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.listTaskCommits(threadId);
        if (!cancelled) setCommits(list);
      }
      catch { if (!cancelled) setCommits([]); }
    })();
    return () => { cancelled = true; };
  }, [threadId]);

  // Diff for the active scope (cumulative or one commit).
  useEffect(() => {
    let cancelled = false;
    setFiles(null);
    setError(null);
    void (async () => {
      try {
        const list = selectedSha === null
          ? await window.bridge.getTaskCumulativeDiff(threadId)
          : await window.bridge.getTaskCommitDiffFiles(threadId, selectedSha);
        if (cancelled) return;
        setFiles(list);
        setSelectedPath(prev => (prev && list.some(f => f.filename === prev) ? prev : list[0]?.filename ?? null));
      }
      catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
  }, [threadId, selectedSha]);

  const toggleDir = useCallback((path: string) => {
    setCollapsedDirs(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }, []);

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

        <div
          className="diff-viewer__body"
          style={{ gridTemplateColumns: '210px 280px minmax(0, 1fr)' }}
        >
          {/* Commits column — scope from cumulative (all) to one commit.
              Reuses the PR viewer's commit-column classes. */}
          <aside className="diff-viewer__commits">
            <div className="diff-viewer__col-head">
              <span className="diff-viewer__col-title">Commits</span>
              {commits !== null && <span className="diff-viewer__col-status">{commits.length}</span>}
            </div>
            <div className="diff-viewer__commits-list">
              <button
                type="button"
                className={'diff-viewer__commit-row diff-viewer__commit-all'
                  + (selectedSha === null ? ' diff-viewer__commit-row--sel' : '')}
                onClick={() => setSelectedSha(null)}
              >
                <span className="diff-viewer__commit-text">
                  <span className="diff-viewer__commit-subject diff-viewer__commit-subject--all">All commits</span>
                  <span className="diff-viewer__commit-meta">cumulative diff</span>
                </span>
              </button>
              {(commits ?? []).map(c => (
                <button
                  key={c.sha}
                  type="button"
                  className={'diff-viewer__commit-row'
                    + (selectedSha === c.sha ? ' diff-viewer__commit-row--sel' : '')}
                  onClick={() => setSelectedSha(c.sha)}
                  title={`${c.sha}\n${c.authorName}\n${c.authoredAt}`}
                >
                  <span className="diff-viewer__commit-text">
                    <span className="diff-viewer__commit-subject">{c.subject}</span>
                    <span className="diff-viewer__commit-meta">
                      <span className="diff-viewer__commit-sha">{c.shortSha}</span>
                      {' · '}{relativeTime(c.authoredAt)}
                    </span>
                  </span>
                </button>
              ))}
            </div>
          </aside>

          {/* Changed-files tree — shared with the PR viewer. */}
          <aside className="diff-viewer__files">
            <div className="diff-viewer__files-header">
              <span>Changed files</span>
              {files !== null && <span className="diff-viewer__files-count">{files.length}</span>}
              <div className="diff-viewer__mode-toggle" role="tablist" aria-label="File list layout">
                <button
                  type="button"
                  role="tab"
                  className={`diff-viewer__mode-btn${mode === 'tree' ? ' diff-viewer__mode-btn--active' : ''}`}
                  onClick={() => setMode('tree')}
                  aria-selected={mode === 'tree'}
                >
                  Tree
                </button>
                <button
                  type="button"
                  role="tab"
                  className={`diff-viewer__mode-btn${mode === 'flat' ? ' diff-viewer__mode-btn--active' : ''}`}
                  onClick={() => setMode('flat')}
                  aria-selected={mode === 'flat'}
                >
                  Flat
                </button>
              </div>
            </div>
            <DiffFileTreePane<DiffFileDto>
              files={files}
              error={error}
              mode={mode}
              pathOf={(f) => f.filename}
              statusBadgeOf={(f) => statusBadge(f.status)}
              selectedPath={selectedPath}
              onSelectPath={setSelectedPath}
              collapsedDirs={collapsedDirs}
              onToggleDir={toggleDir}
            />
          </aside>

          {/* Continuous multi-file diff — the same renderer as the PR page. */}
          <main className="diff-viewer__pane">
            {files !== null && files.length > 0 ? (
              <ContinuousDiff
                files={files}
                selectedPath={selectedPath}
                onActiveFileChange={setSelectedPath}
                renderFileBody={(file) => <FileDiffBody file={file} />}
              />
            ) : error !== null ? (
              <div className="diff-viewer__empty">{error}</div>
            ) : files === null ? (
              <div className="diff-viewer__loading">Loading diff…</div>
            ) : (
              <div className="diff-viewer__empty">No changes in this task yet.</div>
            )}
          </main>
        </div>
      </div>
    </div>
  );
}
