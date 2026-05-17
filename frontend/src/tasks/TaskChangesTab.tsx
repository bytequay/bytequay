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
import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import { DiffFileTreePane, type FilesPaneMode } from '../diff/DiffFileTreePane';
import { parseUnifiedDiff } from '../diffParse';
import { statusBadgeFromLetter } from '../diffStatusBadge';
import type {
  TaskCommitDto,
  TaskCommitFileDto,
  TaskWorkingFileDto,
} from '../types';

type Mode = 'files' | 'commits';

type Props = {
  taskId: string;
  /** Switches between the working-tree changes view and the
   *  commits-since-task-start view. */
  mode: Mode;
};

/**
 * Diff pane for the task detail page. Renders working-tree changes
 * (mode='files') or commit history (mode='commits') side-by-side
 * with the conversation.
 *
 * Visual language mirrors the PR review's diff viewer ({@code
 * DiffViewerScreen}): the file list uses the shared {@link
 * DiffFileTreePane} with a tree/flat toggle, and the diff body
 * reuses {@code parseUnifiedDiff} + the {@code .diff-row*} classes
 * so additions/deletions, line-number gutters, and hunk headers all
 * read the same as in the PR flow.
 */
export default function TaskChangesTab({ taskId, mode }: Props) {
  return mode === 'files'
    ? <FilesPanel taskId={taskId} />
    : <CommitsPanel taskId={taskId} />;
}

// ─── Tree/flat mode persistence ─────────────────────────────────────
// Kept on a separate localStorage key from the PR review viewer so a
// user can prefer Flat for tasks (which usually have fewer files)
// without affecting their PR-review preference.

const FILES_MODE_STORAGE_KEY = 'bytequay.tasks.detailDiffFilesMode';
function loadFilesMode(): FilesPaneMode {
  try {
    return window.localStorage.getItem(FILES_MODE_STORAGE_KEY) === 'flat'
      ? 'flat'
      : 'tree';
  }
  catch { return 'tree'; }
}
function useFilesMode(): [FilesPaneMode, (next: FilesPaneMode) => void] {
  const [mode, setMode] = useState<FilesPaneMode>(loadFilesMode);
  useEffect(() => {
    try { window.localStorage.setItem(FILES_MODE_STORAGE_KEY, mode); }
    catch { /* private browsing — fine to skip */ }
  }, [mode]);
  return [mode, setMode];
}

function FilesPaneHeader({
  label, count, mode, onChangeMode, onRefresh,
}: {
  label: string;
  count: number | null;
  mode: FilesPaneMode;
  onChangeMode: (next: FilesPaneMode) => void;
  onRefresh: () => void;
}) {
  return (
    <div className="diff-viewer__files-header">
      <span>{label}</span>
      {count !== null && (
        <span className="diff-viewer__files-count">{count}</span>
      )}
      <div className="diff-viewer__mode-toggle">
        {(['tree', 'flat'] as const).map(m => (
          <button
            key={m}
            type="button"
            className={`diff-viewer__mode-btn${m === mode ? ' diff-viewer__mode-btn--active' : ''}`}
            onClick={() => onChangeMode(m)}
          >
            {m === 'tree' ? 'Tree' : 'Flat'}
          </button>
        ))}
      </div>
      <button
        type="button"
        className="diff-viewer__files-collapse-btn"
        onClick={onRefresh}
        title="Refresh"
        aria-label="Refresh"
      >
        ↻
      </button>
    </div>
  );
}

// ─── Files (uncommitted) ────────────────────────────────────────────

function FilesPanel({ taskId }: { taskId: string }) {
  const [files, setFiles] = useState<TaskWorkingFileDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [collapsedDirs, setCollapsedDirs] = useState<Set<string>>(() => new Set());
  const [filesMode, setFilesMode] = useFilesMode();

  const refresh = useCallback(async () => {
    try {
      const list = await window.bridge.listTaskWorkingChanges(taskId);
      setFiles(list);
      setError(null);
      setSelectedPath(prev => {
        if (prev && list.some(f => f.path === prev)) return prev;
        return list[0]?.path ?? null;
      });
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [taskId]);

  useEffect(() => { void refresh(); }, [refresh]);

  const onToggleDir = useCallback((path: string) => {
    setCollapsedDirs(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }, []);

  return (
    <div style={panelStyle}>
      <div style={listColumnStyle} className="diff-viewer__files">
        <FilesPaneHeader
          label="Uncommitted"
          count={files?.length ?? null}
          mode={filesMode}
          onChangeMode={setFilesMode}
          onRefresh={() => void refresh()}
        />
        <DiffFileTreePane<TaskWorkingFileDto>
          files={files}
          error={error}
          mode={filesMode}
          pathOf={f => f.path}
          statusBadgeOf={f => statusBadgeFromLetter(f.status)}
          selectedPath={selectedPath}
          onSelectPath={setSelectedPath}
          collapsedDirs={collapsedDirs}
          onToggleDir={onToggleDir}
        />
      </div>
      <div style={diffColumnStyle}>
        {selectedPath ? (
          <DiffBody
            fetcher={() => window.bridge.getTaskWorkingDiff(taskId, selectedPath)}
            cacheKey={`${taskId}::${selectedPath}`}
            path={selectedPath}
          />
        ) : (
          <div className="diff-viewer__empty">Pick a file on the left to view its diff.</div>
        )}
      </div>
    </div>
  );
}

// ─── Commits (since task start) ─────────────────────────────────────

function CommitsPanel({ taskId }: { taskId: string }) {
  const [commits, setCommits] = useState<TaskCommitDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedSha, setSelectedSha] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const list = await window.bridge.listTaskCommits(taskId);
      setCommits(list);
      setError(null);
      setSelectedSha(prev => {
        if (prev && list.some(c => c.sha === prev)) return prev;
        return list[0]?.sha ?? null;
      });
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [taskId]);

  useEffect(() => { void refresh(); }, [refresh]);

  return (
    <div style={panelStyle}>
      <div style={commitListColumnStyle} className="diff-viewer__files">
        <div className="diff-viewer__files-header">
          <span>Commits</span>
          {commits !== null && (
            <span className="diff-viewer__files-count">{commits.length}</span>
          )}
          <button
            type="button"
            className="diff-viewer__files-collapse-btn"
            style={{ marginLeft: 'auto' }}
            onClick={() => void refresh()}
            title="Refresh"
            aria-label="Refresh"
          >
            ↻
          </button>
        </div>
        {error && <div className="diff-viewer__error">{error}</div>}
        {commits === null && !error && (
          <div className="diff-viewer__loading">Loading commits…</div>
        )}
        {commits !== null && commits.length === 0 && (
          <div className="diff-viewer__empty">No commits since this task started.</div>
        )}
        {commits?.map(c => (
          <button
            key={c.sha}
            type="button"
            onClick={() => setSelectedSha(c.sha)}
            style={c.sha === selectedSha ? selectedCommitRowStyle : commitRowStyle}
            title={`${c.sha}\n${c.authorName} <${c.authorEmail}>\n${c.authoredAt}`}
          >
            <span style={commitShaStyle}>{c.shortSha}</span>
            <span style={commitSubjectStyle}>{c.subject}</span>
            <span style={commitMetaStyle}>{relativeTime(c.authoredAt)}</span>
          </button>
        ))}
      </div>
      <div style={diffColumnStyle}>
        {selectedSha ? (
          <CommitDiffView taskId={taskId} sha={selectedSha} />
        ) : (
          <div className="diff-viewer__empty">Pick a commit on the left to see its files.</div>
        )}
      </div>
    </div>
  );
}

function CommitDiffView({ taskId, sha }: { taskId: string; sha: string }) {
  const [files, setFiles] = useState<TaskCommitFileDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [collapsedDirs, setCollapsedDirs] = useState<Set<string>>(() => new Set());
  const [filesMode, setFilesMode] = useFilesMode();

  useEffect(() => {
    let cancelled = false;
    setFiles(null);
    setError(null);
    setSelectedPath(null);
    setCollapsedDirs(new Set());
    void (async () => {
      try {
        const list = await window.bridge.listTaskCommitFiles(taskId, sha);
        if (cancelled) return;
        setFiles(list);
        setSelectedPath(list[0]?.path ?? null);
      }
      catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
  }, [taskId, sha]);

  const onToggleDir = useCallback((path: string) => {
    setCollapsedDirs(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }, []);

  return (
    <div style={commitDiffLayoutStyle}>
      <div style={commitFileListStyle} className="diff-viewer__files">
        <FilesPaneHeader
          label="Files in commit"
          count={files?.length ?? null}
          mode={filesMode}
          onChangeMode={setFilesMode}
          onRefresh={() => { /* commit files don't refresh independently */ }}
        />
        <DiffFileTreePane<TaskCommitFileDto>
          files={files}
          error={error}
          mode={filesMode}
          pathOf={f => f.path}
          statusBadgeOf={f => statusBadgeFromLetter(f.status)}
          selectedPath={selectedPath}
          onSelectPath={setSelectedPath}
          collapsedDirs={collapsedDirs}
          onToggleDir={onToggleDir}
        />
      </div>
      <div style={commitDiffPaneStyle}>
        {selectedPath ? (
          <DiffBody
            fetcher={() => window.bridge.getTaskCommitDiff(taskId, sha, selectedPath)}
            cacheKey={`${taskId}::${sha}::${selectedPath}`}
            path={selectedPath}
          />
        ) : (
          <div className="diff-viewer__empty">Pick a file to view its diff.</div>
        )}
      </div>
    </div>
  );
}

// ─── Diff body — mirrors DiffViewerScreen's .diff-row* layout ───────

/** Renders a unified diff via {@link parseUnifiedDiff} with the same
 *  3-cell row layout (old-line gutter, new-line gutter, sigil +
 *  content) that the PR diff viewer uses. The cacheKey gates the
 *  fetch so switching files / commits triggers a refresh without a
 *  stale render in between. */
function DiffBody({
  fetcher, cacheKey, path,
}: {
  fetcher: () => Promise<string>;
  cacheKey: string;
  path: string;
}) {
  const [diff, setDiff] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    setDiff(null);
    setError(null);
    void (async () => {
      try {
        const text = await fetcher();
        if (!cancelled) setDiff(text);
      }
      catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
    // fetcher is recreated by the caller each render; use cacheKey as
    // the actual dependency so we don't refetch on unrelated renders.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cacheKey]);

  const hunks = useMemo(() => parseUnifiedDiff(diff), [diff]);

  if (error) return <div className="diff-viewer__error">{error}</div>;
  if (diff === null) return <div className="diff-viewer__loading">Loading diff…</div>;
  if (diff === '' || hunks.length === 0) {
    return <div className="diff-viewer__empty">No diff content (file may be binary or unchanged).</div>;
  }

  return (
    <div style={diffBodyStyle}>
      <div style={diffPathStyle} title={path}>{path}</div>
      {hunks.map((hunk, hIdx) => (
        <Fragment key={hIdx}>
          {hunk.rows.map((row, rIdx) => {
            if (row.kind === 'hunk-header') {
              return (
                <div key={rIdx} className="diff-row diff-row--hunk-header">
                  <span className="diff-row__gutter" />
                  <span className="diff-row__gutter" />
                  <span className="diff-row__content">{hunk.header}</span>
                </div>
              );
            }
            return (
              <div key={rIdx} className={`diff-row diff-row--${row.kind}`}>
                <span className="diff-row__gutter">{row.oldLine ?? ''}</span>
                <span className="diff-row__gutter">{row.newLine ?? ''}</span>
                <span className="diff-row__content">
                  <span className="diff-row__sigil">
                    {row.kind === 'add' ? '+' : row.kind === 'del' ? '−' : ' '}
                  </span>
                  {row.content}
                </span>
              </div>
            );
          })}
        </Fragment>
      ))}
    </div>
  );
}

function relativeTime(iso: string): string {
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return iso;
  const deltaSec = Math.max(1, Math.round((Date.now() - t) / 1000));
  if (deltaSec < 60) return `${deltaSec}s ago`;
  if (deltaSec < 3600) return `${Math.round(deltaSec / 60)}m ago`;
  if (deltaSec < 86400) return `${Math.round(deltaSec / 3600)}h ago`;
  return `${Math.round(deltaSec / 86400)}d ago`;
}

// ─── Styles — only the bits that aren't covered by the shared CSS ──

const panelStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'minmax(220px, 320px) 1fr',
  gap: 0,
  border: '1px solid var(--border)',
  borderRadius: 8,
  overflow: 'hidden',
  background: 'var(--bg-card)',
  flex: 1, minHeight: 0,
};

const listColumnStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  borderRight: '1px solid var(--border)',
  background: 'var(--bg-elevated)',
  minHeight: 0,
};

const commitListColumnStyle: React.CSSProperties = {
  ...listColumnStyle,
  overflow: 'auto',
};

const diffColumnStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  overflow: 'auto',
  minHeight: 0,
};

// Commit rows — not a file path so the shared file-row CSS doesn't
// apply. Kept as plain inline styles to match the existing visual
// weight (sha · subject · age).
const commitRowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '64px 1fr auto',
  alignItems: 'center',
  gap: 8,
  padding: '10px 12px',
  background: 'transparent',
  border: 'none',
  borderBottom: '1px solid var(--border-hairline)',
  textAlign: 'left',
  cursor: 'pointer',
  font: 'inherit',
  color: 'inherit',
};
const selectedCommitRowStyle: React.CSSProperties = {
  ...commitRowStyle,
  background: 'var(--accent-a10)',
};
const commitShaStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, monospace',
  fontSize: 12,
  color: 'var(--text-3)',
};
const commitSubjectStyle: React.CSSProperties = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  fontSize: 13,
};
const commitMetaStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
};

const commitDiffLayoutStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateRows: 'minmax(80px, 30vh) 1fr',
  minHeight: 0,
  height: '100%',
};
const commitFileListStyle: React.CSSProperties = {
  borderBottom: '1px solid var(--border)',
  display: 'flex', flexDirection: 'column',
  minHeight: 0,
  overflow: 'hidden',
};
const commitDiffPaneStyle: React.CSSProperties = {
  overflow: 'auto',
  minHeight: 0,
};

const diffBodyStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  background: 'var(--bg-card)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
};
const diffPathStyle: React.CSSProperties = {
  position: 'sticky', top: 0, zIndex: 1,
  padding: '6px 14px',
  borderBottom: '1px solid var(--border)',
  background: 'var(--bg-elevated)',
  color: 'var(--text-2)',
  fontSize: 12, fontWeight: 600,
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};
