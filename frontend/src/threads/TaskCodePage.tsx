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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import ResizeHandle from '../ResizeHandle';
import { CommitsColumn } from '../diff/CommitsColumn';
import { ContinuousDiff, FileDiffBody } from '../diff/DiffFileList';
import { DiffFileTreePane, type FilesPaneMode } from '../diff/DiffFileTreePane';
import { contiguousRange } from '../diff/commitRange';
import { unionCommitFiles } from '../diff/unionCommitFiles';
import { statusBadge } from '../diffStatusBadge';
import type { DiffFileDto, ThreadCommitDto } from '../types';
import { useThreadTasks } from './useThreadTasks';

const COMMITS_WIDTH_KEY = 'bytequay.taskCode.commitsWidth';
const FILES_WIDTH_KEY = 'bytequay.taskCode.filesWidth';
const COMMITS_DEFAULT = 230;
const FILES_DEFAULT = 280;
const WIDTH_MIN = 160;
const WIDTH_MAX = 560;

function loadWidth(key: string, fallback: number): number {
  try {
    const n = parseInt(window.localStorage.getItem(key) ?? '', 10);
    return Number.isFinite(n) && n >= WIDTH_MIN && n <= WIDTH_MAX ? n : fallback;
  }
  catch { return fallback; }
}

/**
 * Standalone "Code" page for a task — the diff/files viewer reached from
 * the brain view and the stage detail's "View code diff". It renders the
 * task's diff with the **same components** as the PR review's
 * {@code DiffViewerScreen}: {@link CommitsColumn}, {@link DiffFileTreePane},
 * {@link ResizeHandle}, and the continuous {@link ContinuousDiff} /
 * {@link FileDiffBody} body — so PR-diff rendering changes propagate here.
 * Read-only (no review draft to comment to).
 *
 * Default view is the task's cumulative diff (every commit, base..HEAD);
 * the commits column scopes to one commit or a contiguous range (union),
 * exactly like the PR page.
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
  // Empty set ⇒ cumulative (all commits); otherwise a contiguous range.
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const [rangeAnchor, setRangeAnchor] = useState<string | null>(null);
  const [files, setFiles] = useState<DiffFileDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [mode, setMode] = useState<FilesPaneMode>('tree');
  const [collapsedDirs, setCollapsedDirs] = useState<Set<string>>(() => new Set());
  const [commitsWidth, setCommitsWidth] = useState(() => loadWidth(COMMITS_WIDTH_KEY, COMMITS_DEFAULT));
  const [filesWidth, setFilesWidth] = useState(() => loadWidth(FILES_WIDTH_KEY, FILES_DEFAULT));
  const bodyRef = useRef<HTMLDivElement>(null);

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

  // Diff for the active scope. Empty selection ⇒ cumulative; one commit ⇒
  // that commit; a range ⇒ the union of the selected commits' diffs.
  const selKey = useMemo(() => [...selected].sort().join(','), [selected]);
  useEffect(() => {
    let cancelled = false;
    setFiles(null);
    setError(null);
    void (async () => {
      try {
        let list: DiffFileDto[];
        if (selected.size === 0) {
          list = await window.bridge.getTaskCumulativeDiff(threadId);
        }
        else {
          // Fetch each selected commit (in commit order) and union by path.
          const orderedSel = (commits ?? []).map(c => c.sha).filter(sha => selected.has(sha));
          const perCommit = await Promise.all(
            orderedSel.map(sha => window.bridge.getTaskCommitDiffFiles(threadId, sha)));
          list = unionCommitFiles(perCommit, f => f.filename);
        }
        if (cancelled) return;
        setFiles(list);
        setSelectedPath(prev => (prev && list.some(f => f.filename === prev) ? prev : list[0]?.filename ?? null));
      }
      catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
    // selKey captures the selection contents; commits feed the union order.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [threadId, selKey, commits]);

  const orderedShas = useMemo(() => (commits ?? []).map(c => c.sha), [commits]);

  const onSelectCommit = useCallback((sha: string, extend: boolean) => {
    if (extend && rangeAnchor !== null && selected.size > 0) {
      setSelected(contiguousRange(orderedShas, rangeAnchor, sha));
    }
    else {
      setSelected(new Set([sha]));
      setRangeAnchor(sha);
    }
  }, [rangeAnchor, selected, orderedShas]);

  const onSelectAll = useCallback(() => {
    setSelected(new Set());
    setRangeAnchor(null);
  }, []);

  const toggleDir = useCallback((path: string) => {
    setCollapsedDirs(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }, []);

  const summary = useMemo(() => (files ?? []).reduce(
    (acc, f) => ({ additions: acc.additions + f.additions, deletions: acc.deletions + f.deletions }),
    { additions: 0, deletions: 0 }), [files]);

  const handleCommitsResize = useCallback((clientX: number) => {
    const rect = bodyRef.current?.getBoundingClientRect();
    if (!rect) return;
    const next = Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, clientX - rect.left));
    setCommitsWidth(next);
    try { window.localStorage.setItem(COMMITS_WIDTH_KEY, String(next)); } catch { /* private mode */ }
  }, []);

  const handleFilesResize = useCallback((clientX: number) => {
    const rect = bodyRef.current?.getBoundingClientRect();
    if (!rect) return;
    const next = Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, clientX - rect.left - commitsWidth - 5));
    setFilesWidth(next);
    try { window.localStorage.setItem(FILES_WIDTH_KEY, String(next)); } catch { /* private mode */ }
  }, [commitsWidth]);

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
          ref={bodyRef}
          style={{ gridTemplateColumns: `${commitsWidth}px 5px ${filesWidth}px 5px minmax(0, 1fr)` }}
        >
          {/* Commits column — shared with the PR viewer (checkboxes + range). */}
          <CommitsColumn
            commits={(commits ?? []).map(c => ({
              sha: c.sha, subject: c.subject, author: c.authorName, authoredAt: c.authoredAt,
            }))}
            selected={selected}
            onSelectCommit={onSelectCommit}
            onSelectAll={onSelectAll}
            summary={summary}
            loading={files === null}
            collapsed={false}
            onToggleCollapsed={() => { /* task page keeps the column open */ }}
          />
          <ResizeHandle onResize={handleCommitsResize} ariaLabel="Resize commits panel" />

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
          <ResizeHandle onResize={handleFilesResize} ariaLabel="Resize changed-files panel" />

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
