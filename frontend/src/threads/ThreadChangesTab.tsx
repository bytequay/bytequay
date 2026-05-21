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
import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { DiffFileTreePane, type FilesPaneMode } from '../diff/DiffFileTreePane';
import { parseUnifiedDiff } from '../diffParse';
import { statusBadgeFromLetter } from '../diffStatusBadge';
import type {
  ThreadCommitDto,
  ThreadCommitFileDto,
  ThreadWorkingFileDto,
} from '../types';

export type DiffMode = 'files' | 'commits';

type Props = {
  threadId: string;
  /** Switches between the working-tree changes view and the
   *  commits-since-thread-start view. */
  mode: DiffMode;
};

/**
 * Diff pane for the thread detail page. Renders working-tree changes
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
export default function ThreadChangesTab({ threadId, mode }: Props) {
  return mode === 'files'
    ? <FilesPanel threadId={threadId} mode={mode} onChangeMode={() => {}} workingCount={null} commitsCount={null} />
    : <CommitsPanel threadId={threadId} mode={mode} onChangeMode={() => {}} workingCount={null} commitsCount={null} />;
}

/** Counts shown next to the Working tree / Commits toggle buttons.
 *  Either count is {@code null} until its fetch resolves; the chip
 *  hides the number while loading rather than flashing a "0". */
export type DiffCounts = {
  workingCount: number | null;
  commitsCount: number | null;
};

/** Two-pill segmented toggle for the diff mode. Used in both the
 *  inline panel header and the {@code ReviewStrip}; same component
 *  in both places keeps them visually aligned. */
export function DiffModeToggle({
  mode, onChangeMode, workingCount, commitsCount, dense,
}: {
  mode: DiffMode;
  onChangeMode: (next: DiffMode) => void;
  workingCount: number | null;
  commitsCount: number | null;
  /** Compact variant for the {@code ReviewStrip} where vertical room
   *  is tight. The default size is meant for the panel header. */
  dense?: boolean;
}) {
  const groupClass = `diff-mode-toggle${dense ? ' diff-mode-toggle--dense' : ''}`;
  return (
    <div className={groupClass} role="tablist" aria-label="Diff view">
      <button
        type="button"
        role="tab"
        aria-selected={mode === 'files'}
        onClick={() => onChangeMode('files')}
        onMouseDown={(e) => e.stopPropagation()}
        className={`diff-mode-toggle__btn${mode === 'files' ? ' diff-mode-toggle__btn--active' : ''}`}
      >
        Working tree
        {workingCount !== null && (
          <span className="diff-mode-toggle__count">{workingCount}</span>
        )}
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={mode === 'commits'}
        onClick={() => onChangeMode('commits')}
        onMouseDown={(e) => e.stopPropagation()}
        className={`diff-mode-toggle__btn${mode === 'commits' ? ' diff-mode-toggle__btn--active' : ''}`}
      >
        Commits
        {commitsCount !== null && (
          <span className="diff-mode-toggle__count">{commitsCount}</span>
        )}
      </button>
    </div>
  );
}

/** Owner of the diff mode + per-tab counts. Lives in the parent so
 *  the inline {@link DiffModeToggle} in the panel header and the
 *  one rendered next to the Diff button stay in sync. Exposed as a
 *  hook so both {@code ThreadDetailPage} and {@code ThreadZoomModal}
 *  can wire the same state without duplicating fetch logic. */
export function useTaskDiffState(threadId: string): {
  mode: DiffMode;
  setMode: (next: DiffMode) => void;
  workingCount: number | null;
  commitsCount: number | null;
} {
  const [mode, setModeState] = useState<DiffMode>(loadDiffTab);
  const [userOverride, setUserOverride] = useState(false);
  const [workingCount, setWorkingCount] = useState<number | null>(null);
  const [commitsCount, setCommitsCount] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [w, c] = await Promise.all([
          window.bridge.listTaskWorkingChanges(threadId),
          window.bridge.listTaskCommits(threadId).catch(() => [] as ThreadCommitDto[]),
        ]);
        if (cancelled) return;
        setWorkingCount(w.length);
        setCommitsCount(c.length);
        if (!userOverride && w.length === 0 && c.length > 0) {
          setModeState('commits');
        }
      }
      catch { /* non-fatal — counts just stay null */ }
    })();
    return () => { cancelled = true; };
  }, [threadId, userOverride]);

  const setMode = useCallback((next: DiffMode) => {
    setUserOverride(true);
    setModeState(next);
    try { window.localStorage.setItem(DIFF_TAB_STORAGE_KEY, next); }
    catch { /* private browsing — fine to skip */ }
  }, []);

  return { mode, setMode, workingCount, commitsCount };
}

// ─── Tabbed wrapper — used by ThreadDetailPage / ThreadZoomModal ────────
// When the diff pane is opened from the conversation chrome, the user
// might want either "what's uncommitted" or "what got committed since
// the thread started." Earlier the pane only surfaced the working tree,
// which read as "no files changed" the moment the agent committed
// anything — confusing because the conversation strip and the sidebar
// metric still showed lifetime numbers. The toggle below lets the user
// flip between the two views; the smart default falls back to Commits
// when the working tree is clean but commits exist.

const DIFF_TAB_STORAGE_KEY = 'bytequay.threads.diffPaneTab';
function loadDiffTab(): DiffMode {
  try {
    return window.localStorage.getItem(DIFF_TAB_STORAGE_KEY) === 'commits'
      ? 'commits'
      : 'files';
  }
  catch { return 'files'; }
}

/** Diff pane with no internal mode tab bar — the toggle now lives
 *  inside the panel header (via {@link DiffModeToggle}). When the
 *  parent controls the mode (via {@link useTaskDiffState}), pass it
 *  through; otherwise this falls back to managing its own state so
 *  surfaces like {@code ThreadZoomModal} don't need to thread it. */
export function ThreadDiffPane({
  threadId, mode, onChangeMode, workingCount, commitsCount,
}: {
  threadId: string;
  mode?: DiffMode;
  onChangeMode?: (next: DiffMode) => void;
  workingCount?: number | null;
  commitsCount?: number | null;
}) {
  const internal = useTaskDiffState(threadId);
  const effectiveMode = mode ?? internal.mode;
  const effectiveSetMode = onChangeMode ?? internal.setMode;
  const effectiveWorking = workingCount ?? internal.workingCount;
  const effectiveCommits = commitsCount ?? internal.commitsCount;
  const sharedProps = {
    mode: effectiveMode,
    onChangeMode: effectiveSetMode,
    workingCount: effectiveWorking,
    commitsCount: effectiveCommits,
  };
  return effectiveMode === 'files'
    ? <FilesPanel threadId={threadId} {...sharedProps} />
    : <CommitsPanel threadId={threadId} {...sharedProps} />;
}

// ─── Tree/flat mode persistence ─────────────────────────────────────
// Kept on a separate localStorage key from the PR review viewer so a
// user can prefer Flat for threads (which usually have fewer files)
// without affecting their PR-review preference.

const FILES_MODE_STORAGE_KEY = 'bytequay.threads.detailDiffFilesMode';
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
  diffMode, onChangeDiffMode, workingCount, commitsCount,
  staticLabel,
  mode, onChangeMode, onRefresh, onCollapse,
}: {
  /** Working tree vs Commits. Omitted from the {@code CommitDiffView}
   *  branch (which shows files inside a single commit, not a top-level
   *  mode); pass {@code staticLabel} instead. */
  diffMode?: DiffMode;
  onChangeDiffMode?: (next: DiffMode) => void;
  workingCount?: number | null;
  commitsCount?: number | null;
  /** Used by {@code CommitDiffView} where the diff-mode toggle isn't
   *  meaningful — falls back to a plain text label. */
  staticLabel?: string;
  mode: FilesPaneMode;
  onChangeMode: (next: FilesPaneMode) => void;
  onRefresh: () => void;
  /** Optional — when omitted, the collapse chevron isn't rendered.
   *  Only the column-split layouts (FilesPanel, CommitsPanel) support
   *  collapse; the row-split CommitDiffView passes nothing. */
  onCollapse?: () => void;
}) {
  return (
    <div className="diff-viewer__files-header">
      {diffMode && onChangeDiffMode ? (
        <DiffModeToggle
          mode={diffMode}
          onChangeMode={onChangeDiffMode}
          workingCount={workingCount ?? null}
          commitsCount={commitsCount ?? null}
        />
      ) : (
        <span>{staticLabel}</span>
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
      {onCollapse && (
        <button
          type="button"
          className="diff-viewer__files-collapse-btn"
          onClick={onCollapse}
          title="Collapse panel"
          aria-label="Collapse panel"
        >
          ‹
        </button>
      )}
    </div>
  );
}

// ─── Resizable / collapsible list column ────────────────────────────
// One shared hook so FilesPanel and CommitsPanel agree on width and
// collapsed state — switching tabs (working tree ↔ commits) shouldn't
// jump the layout.

const LIST_WIDTH_STORAGE_KEY = 'bytequay.threads.detailDiffListWidth';
const LIST_COLLAPSED_STORAGE_KEY = 'bytequay.threads.detailDiffListCollapsed';
const DEFAULT_LIST_WIDTH = 280;
const MIN_LIST_WIDTH = 160;
const MAX_LIST_WIDTH = 720;

function useResizableList(): {
  width: number;
  setWidth: (next: number) => void;
  collapsed: boolean;
  setCollapsed: (next: boolean) => void;
} {
  const [width, setWidth] = useState<number>(() => {
    try {
      const raw = window.localStorage.getItem(LIST_WIDTH_STORAGE_KEY);
      const n = raw == null ? NaN : parseInt(raw, 10);
      return Number.isFinite(n) && n >= MIN_LIST_WIDTH && n <= MAX_LIST_WIDTH
        ? n
        : DEFAULT_LIST_WIDTH;
    }
    catch { return DEFAULT_LIST_WIDTH; }
  });
  const [collapsed, setCollapsed] = useState<boolean>(() => {
    try { return window.localStorage.getItem(LIST_COLLAPSED_STORAGE_KEY) === '1'; }
    catch { return false; }
  });
  useEffect(() => {
    try { window.localStorage.setItem(LIST_WIDTH_STORAGE_KEY, String(width)); }
    catch { /* private browsing — fine to skip */ }
  }, [width]);
  useEffect(() => {
    try { window.localStorage.setItem(LIST_COLLAPSED_STORAGE_KEY, collapsed ? '1' : '0'); }
    catch { /* private browsing — fine to skip */ }
  }, [collapsed]);
  return { width, setWidth, collapsed, setCollapsed };
}

// ─── Row splitter — vertical-orientation drag handle ───────────────
// Used inside CommitDiffView, where the file list sits ABOVE the diff
// body. Mirrors ColumnSplitter but tracks the Y axis. The height is a
// pixel value (not a fraction) because the parent's height varies
// with the surrounding split fraction; persisting an absolute file-
// list height keeps the diff body filling whatever remains.

const COMMIT_FILES_HEIGHT_KEY = 'bytequay.threads.detailCommitFilesHeight';
const DEFAULT_COMMIT_FILES_HEIGHT = 220;
const MIN_COMMIT_FILES_HEIGHT = 80;
const MAX_COMMIT_FILES_HEIGHT = 600;

function useCommitFilesHeight(): [number, (next: number) => void] {
  const [height, setHeightState] = useState<number>(() => {
    try {
      const raw = window.localStorage.getItem(COMMIT_FILES_HEIGHT_KEY);
      const n = raw == null ? NaN : parseInt(raw, 10);
      return Number.isFinite(n) && n >= MIN_COMMIT_FILES_HEIGHT && n <= MAX_COMMIT_FILES_HEIGHT
        ? n
        : DEFAULT_COMMIT_FILES_HEIGHT;
    }
    catch { return DEFAULT_COMMIT_FILES_HEIGHT; }
  });
  const setHeight = useCallback((next: number) => {
    setHeightState(Math.max(MIN_COMMIT_FILES_HEIGHT, Math.min(MAX_COMMIT_FILES_HEIGHT, next)));
  }, []);
  useEffect(() => {
    try { window.localStorage.setItem(COMMIT_FILES_HEIGHT_KEY, String(height)); }
    catch { /* private browsing — fine to skip */ }
  }, [height]);
  return [height, setHeight];
}

function RowSplitter({
  height, onChange,
}: {
  height: number;
  onChange: (next: number) => void;
}) {
  const [dragging, setDragging] = useState(false);
  const startRef = useRef<{ y: number; h: number } | null>(null);
  useEffect(() => {
    if (!dragging) return;
    const onMove = (e: MouseEvent) => {
      const s = startRef.current;
      if (s === null) return;
      onChange(s.h + (e.clientY - s.y));
    };
    const onUp = () => setDragging(false);
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
    const prevCursor = document.body.style.cursor;
    const prevSelect = document.body.style.userSelect;
    document.body.style.cursor = 'row-resize';
    document.body.style.userSelect = 'none';
    return () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      document.body.style.cursor = prevCursor;
      document.body.style.userSelect = prevSelect;
    };
  }, [dragging, onChange]);
  return (
    <div
      role="separator"
      aria-orientation="horizontal"
      title="Drag to resize"
      onMouseDown={(e) => {
        e.preventDefault();
        startRef.current = { y: e.clientY, h: height };
        setDragging(true);
      }}
      onDoubleClick={() => onChange(DEFAULT_COMMIT_FILES_HEIGHT)}
      style={{
        flex: '0 0 5px',
        cursor: 'row-resize',
        background: dragging ? 'var(--accent)' : 'var(--border)',
        opacity: dragging ? 1 : 0.5,
        transition: 'opacity 100ms ease, background 100ms ease',
      }}
      onMouseEnter={(e) => { (e.currentTarget as HTMLDivElement).style.opacity = '1'; }}
      onMouseLeave={(e) => {
        if (!dragging) (e.currentTarget as HTMLDivElement).style.opacity = '0.5';
      }}
    />
  );
}

function ColumnSplitter({
  width, onChange,
}: {
  width: number;
  onChange: (next: number) => void;
}) {
  const [dragging, setDragging] = useState(false);
  const startRef = useRef<{ x: number; w: number } | null>(null);
  useEffect(() => {
    if (!dragging) return;
    const onMove = (e: MouseEvent) => {
      const s = startRef.current;
      if (s === null) return;
      const next = Math.max(
        MIN_LIST_WIDTH,
        Math.min(MAX_LIST_WIDTH, s.w + (e.clientX - s.x)),
      );
      onChange(next);
    };
    const onUp = () => setDragging(false);
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
    // Lock cursor + selection across the whole document while dragging
    // so the cursor doesn't flicker when the mouse strays off the strip.
    const prevCursor = document.body.style.cursor;
    const prevSelect = document.body.style.userSelect;
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    return () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      document.body.style.cursor = prevCursor;
      document.body.style.userSelect = prevSelect;
    };
  }, [dragging, onChange]);
  return (
    <div
      role="separator"
      aria-orientation="vertical"
      title="Drag to resize"
      onMouseDown={(e) => {
        e.preventDefault();
        startRef.current = { x: e.clientX, w: width };
        setDragging(true);
      }}
      style={{
        flex: '0 0 5px',
        cursor: 'col-resize',
        background: dragging ? 'var(--accent)' : 'var(--border)',
        opacity: dragging ? 1 : 0.5,
        transition: 'opacity 100ms ease, background 100ms ease',
      }}
      onMouseEnter={(e) => { (e.currentTarget as HTMLDivElement).style.opacity = '1'; }}
      onMouseLeave={(e) => {
        if (!dragging) (e.currentTarget as HTMLDivElement).style.opacity = '0.5';
      }}
    />
  );
}

function CollapsedRail({
  label, onExpand,
}: {
  label: string;
  onExpand: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onExpand}
      title={`Expand ${label}`}
      aria-label={`Expand ${label}`}
      style={collapsedRailStyle}
    >
      <span aria-hidden style={{ fontSize: 14 }}>›</span>
      <span style={collapsedRailLabelStyle}>{label}</span>
    </button>
  );
}

// ─── Files (uncommitted) ────────────────────────────────────────────

function FilesPanel({
  threadId, mode, onChangeMode, workingCount, commitsCount,
}: {
  threadId: string;
  mode: DiffMode;
  onChangeMode: (next: DiffMode) => void;
  workingCount: number | null;
  commitsCount: number | null;
}) {
  const [files, setFiles] = useState<ThreadWorkingFileDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [collapsedDirs, setCollapsedDirs] = useState<Set<string>>(() => new Set());
  const [filesMode, setFilesMode] = useFilesMode();
  const { width, setWidth, collapsed, setCollapsed } = useResizableList();

  const refresh = useCallback(async () => {
    try {
      const list = await window.bridge.listTaskWorkingChanges(threadId);
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
  }, [threadId]);

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
      {collapsed ? (
        <CollapsedRail label="Files" onExpand={() => setCollapsed(false)} />
      ) : (
        <>
          <div
            style={{ ...listColumnStyle, width, flex: `0 0 ${width}px` }}
            className="diff-viewer__files"
          >
            <FilesPaneHeader
              diffMode={mode}
              onChangeDiffMode={onChangeMode}
              workingCount={workingCount ?? files?.length ?? null}
              commitsCount={commitsCount}
              mode={filesMode}
              onChangeMode={setFilesMode}
              onRefresh={() => void refresh()}
              onCollapse={() => setCollapsed(true)}
            />
            <DiffFileTreePane<ThreadWorkingFileDto>
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
          <ColumnSplitter width={width} onChange={setWidth} />
        </>
      )}
      <div style={diffColumnStyle}>
        {selectedPath ? (
          <DiffBody
            fetcher={() => window.bridge.getTaskWorkingDiff(threadId, selectedPath)}
            cacheKey={`${threadId}::${selectedPath}`}
            path={selectedPath}
          />
        ) : (
          <div className="diff-viewer__empty">Pick a file on the left to view its diff.</div>
        )}
      </div>
    </div>
  );
}

// ─── Commits (since thread start) ─────────────────────────────────────

function CommitsPanel({
  threadId, mode, onChangeMode, workingCount, commitsCount,
}: {
  threadId: string;
  mode: DiffMode;
  onChangeMode: (next: DiffMode) => void;
  workingCount: number | null;
  commitsCount: number | null;
}) {
  const [commits, setCommits] = useState<ThreadCommitDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedSha, setSelectedSha] = useState<string | null>(null);
  const { width, setWidth, collapsed, setCollapsed } = useResizableList();

  const refresh = useCallback(async () => {
    try {
      const list = await window.bridge.listTaskCommits(threadId);
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
  }, [threadId]);

  useEffect(() => { void refresh(); }, [refresh]);

  return (
    <div style={panelStyle}>
      {collapsed ? (
        <CollapsedRail label="Commits" onExpand={() => setCollapsed(false)} />
      ) : (
        <>
          <div
            style={{ ...commitListColumnStyle, width, flex: `0 0 ${width}px` }}
            className="diff-viewer__files"
          >
            <div className="diff-viewer__files-header">
              <DiffModeToggle
                mode={mode}
                onChangeMode={onChangeMode}
                workingCount={workingCount}
                commitsCount={commitsCount ?? commits?.length ?? null}
              />
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
              <button
                type="button"
                className="diff-viewer__files-collapse-btn"
                onClick={() => setCollapsed(true)}
                title="Collapse panel"
                aria-label="Collapse panel"
              >
                ‹
              </button>
            </div>
            {error && <div className="diff-viewer__error">{error}</div>}
            {commits === null && !error && (
              <div className="diff-viewer__loading">Loading commits…</div>
            )}
            {commits !== null && commits.length === 0 && (
              <div className="diff-viewer__empty">No commits since this thread started.</div>
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
          <ColumnSplitter width={width} onChange={setWidth} />
        </>
      )}
      <div style={diffColumnStyle}>
        {selectedSha ? (
          <CommitDiffView threadId={threadId} sha={selectedSha} />
        ) : (
          <div className="diff-viewer__empty">Pick a commit on the left to see its files.</div>
        )}
      </div>
    </div>
  );
}

function CommitDiffView({ threadId, sha }: { threadId: string; sha: string }) {
  const [files, setFiles] = useState<ThreadCommitFileDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [collapsedDirs, setCollapsedDirs] = useState<Set<string>>(() => new Set());
  const [filesMode, setFilesMode] = useFilesMode();
  const [filesHeight, setFilesHeight] = useCommitFilesHeight();

  useEffect(() => {
    let cancelled = false;
    setFiles(null);
    setError(null);
    setSelectedPath(null);
    setCollapsedDirs(new Set());
    void (async () => {
      try {
        const list = await window.bridge.listTaskCommitFiles(threadId, sha);
        if (cancelled) return;
        setFiles(list);
        setSelectedPath(list[0]?.path ?? null);
      }
      catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
  }, [threadId, sha]);

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
      <div
        style={{ ...commitFileListStyle, height: filesHeight, flex: `0 0 ${filesHeight}px` }}
        className="diff-viewer__files"
      >
        <FilesPaneHeader
          staticLabel={files === null ? 'Files in commit' : `Files in commit · ${files.length}`}
          mode={filesMode}
          onChangeMode={setFilesMode}
          onRefresh={() => { /* commit files don't refresh independently */ }}
        />
        <DiffFileTreePane<ThreadCommitFileDto>
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
      <RowSplitter height={filesHeight} onChange={setFilesHeight} />
      <div style={commitDiffPaneStyle}>
        {selectedPath ? (
          <DiffBody
            fetcher={() => window.bridge.getTaskCommitDiff(threadId, sha, selectedPath)}
            cacheKey={`${threadId}::${sha}::${selectedPath}`}
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
  display: 'flex',
  flexDirection: 'row',
  border: '1px solid var(--border)',
  borderRadius: 8,
  overflow: 'hidden',
  background: 'var(--bg-card)',
  flex: 1, minHeight: 0,
};

const listColumnStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  background: 'var(--bg-elevated)',
  minHeight: 0,
  overflow: 'hidden',
};

const collapsedRailStyle: React.CSSProperties = {
  flex: '0 0 24px',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'flex-start',
  gap: 8,
  padding: '8px 0',
  background: 'var(--bg-elevated)',
  border: 'none',
  borderRight: '1px solid var(--border)',
  cursor: 'pointer',
  color: 'var(--text-2)',
  font: 'inherit',
};
const collapsedRailLabelStyle: React.CSSProperties = {
  writingMode: 'vertical-rl',
  transform: 'rotate(180deg)',
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
  fontSize: 10,
  color: 'var(--text-3)',
};

const commitListColumnStyle: React.CSSProperties = {
  ...listColumnStyle,
  overflow: 'auto',
};

const diffColumnStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
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
  display: 'flex',
  flexDirection: 'column',
  minHeight: 0,
  height: '100%',
};
const commitFileListStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column',
  minHeight: 0,
  overflow: 'hidden',
};
const commitDiffPaneStyle: React.CSSProperties = {
  flex: 1,
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

