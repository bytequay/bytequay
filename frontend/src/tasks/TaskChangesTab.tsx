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
import type {
  TaskCommitDto,
  TaskCommitFileDto,
  TaskWorkingFileDto,
} from '../types';

type Mode = 'files' | 'commits';

type Props = {
  taskId: string;
  /** Switches between the working-tree changes view and the
   *  commits-since-task-start view. The tab strip in TaskDetailPage
   *  owns the active mode. */
  mode: Mode;
};

/**
 * Shared panel for the "Files" and "Commits" tabs on the task detail
 * page. Both views have the same shape — a list on the left, a diff
 * pane on the right that fills with the selected entry's diff — so
 * one component handles both with a mode switch.
 *
 * <p>Both views poll the workingDir on demand (no caching): the AI
 * session is actively mutating the tree, and refreshing on tab
 * switch is the cheapest way to keep the panel honest. There's a
 * manual ↻ button for when the user wants to re-check without
 * navigating away.
 */
export default function TaskChangesTab({ taskId, mode }: Props) {
  return mode === 'files'
    ? <FilesPanel taskId={taskId} />
    : <CommitsPanel taskId={taskId} />;
}

// ─── Files (uncommitted) ────────────────────────────────────────────

function FilesPanel({ taskId }: { taskId: string }) {
  const [files, setFiles] = useState<TaskWorkingFileDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const list = await window.bridge.listTaskWorkingChanges(taskId);
      setFiles(list);
      setError(null);
      // Auto-select first entry when nothing's selected so the diff
      // pane isn't empty after a refresh that produced rows.
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

  return (
    <div style={panelStyle}>
      <div style={listColumnStyle}>
        <div style={listHeaderStyle}>
          <span style={listTitleStyle}>
            Uncommitted{files ? ` (${files.length})` : ''}
          </span>
          <button type="button" style={refreshBtnStyle} onClick={() => void refresh()} title="Refresh">
            ↻
          </button>
        </div>
        {error && <div style={errorRowStyle}>{error}</div>}
        {!error && files !== null && files.length === 0 && (
          <div style={emptyRowStyle}>No uncommitted changes.</div>
        )}
        {files === null && !error && (
          <div style={emptyRowStyle}>Loading…</div>
        )}
        {files?.map(f => (
          <button
            key={f.path}
            type="button"
            onClick={() => setSelectedPath(f.path)}
            style={f.path === selectedPath ? selectedRowStyle : rowStyle}
            title={f.path}
          >
            <span style={statusBadgeStyle(f.status)}>{f.status}</span>
            <span style={pathStyle}>{f.path}</span>
          </button>
        ))}
      </div>
      <div style={diffColumnStyle}>
        {selectedPath ? (
          <WorkingDiff taskId={taskId} path={selectedPath} />
        ) : (
          <div style={diffEmptyStyle}>Pick a file on the left to view its diff.</div>
        )}
      </div>
    </div>
  );
}

function WorkingDiff({ taskId, path }: { taskId: string; path: string }) {
  const [diff, setDiff] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    setDiff(null);
    setError(null);
    void (async () => {
      try {
        const text = await window.bridge.getTaskWorkingDiff(taskId, path);
        if (!cancelled) setDiff(text);
      }
      catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
  }, [taskId, path]);

  if (error) return <div style={errorRowStyle}>{error}</div>;
  if (diff === null) return <div style={diffEmptyStyle}>Loading diff…</div>;
  if (diff === '') return <div style={diffEmptyStyle}>No diff content (file may be binary or unchanged).</div>;
  return <DiffPre text={diff} />;
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
      <div style={listColumnStyle}>
        <div style={listHeaderStyle}>
          <span style={listTitleStyle}>
            Commits{commits ? ` (${commits.length})` : ''}
          </span>
          <button type="button" style={refreshBtnStyle} onClick={() => void refresh()} title="Refresh">
            ↻
          </button>
        </div>
        {error && <div style={errorRowStyle}>{error}</div>}
        {!error && commits !== null && commits.length === 0 && (
          <div style={emptyRowStyle}>No commits since this task started.</div>
        )}
        {commits === null && !error && (
          <div style={emptyRowStyle}>Loading…</div>
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
          <div style={diffEmptyStyle}>Pick a commit on the left to see its files.</div>
        )}
      </div>
    </div>
  );
}

function CommitDiffView({ taskId, sha }: { taskId: string; sha: string }) {
  const [files, setFiles] = useState<TaskCommitFileDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setFiles(null);
    setError(null);
    setSelectedPath(null);
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

  if (error) return <div style={errorRowStyle}>{error}</div>;
  if (files === null) return <div style={diffEmptyStyle}>Loading commit…</div>;
  if (files.length === 0) return <div style={diffEmptyStyle}>No file changes in this commit.</div>;

  return (
    <div style={commitDiffLayoutStyle}>
      <div style={commitFileListStyle}>
        {files.map(f => (
          <button
            key={f.path}
            type="button"
            onClick={() => setSelectedPath(f.path)}
            style={f.path === selectedPath ? selectedRowStyle : rowStyle}
            title={f.path}
          >
            <span style={statusBadgeStyle(f.status)}>{f.status}</span>
            <span style={pathStyle}>{f.path}</span>
            <span style={lineCountStyle}>
              <span style={additionsStyle}>+{f.additions}</span>
              {' '}
              <span style={deletionsStyle}>-{f.deletions}</span>
            </span>
          </button>
        ))}
      </div>
      <div style={commitDiffPaneStyle}>
        {selectedPath && <CommitDiff taskId={taskId} sha={sha} path={selectedPath} />}
      </div>
    </div>
  );
}

function CommitDiff({ taskId, sha, path }: { taskId: string; sha: string; path: string }) {
  const [diff, setDiff] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    setDiff(null);
    setError(null);
    void (async () => {
      try {
        const text = await window.bridge.getTaskCommitDiff(taskId, sha, path);
        if (!cancelled) setDiff(text);
      }
      catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
  }, [taskId, sha, path]);

  if (error) return <div style={errorRowStyle}>{error}</div>;
  if (diff === null) return <div style={diffEmptyStyle}>Loading diff…</div>;
  if (diff === '') return <div style={diffEmptyStyle}>No diff content.</div>;
  return <DiffPre text={diff} />;
}

// ─── Shared diff renderer + helpers ─────────────────────────────────

function DiffPre({ text }: { text: string }) {
  // Cheap line-by-line colouring — green for additions, red for
  // deletions, slate for hunk headers, default for context. Keeps the
  // diff scannable without pulling in a full syntax-highlighter.
  const lines = useMemo(() => text.split('\n'), [text]);
  return (
    <pre style={diffPreStyle}>
      {lines.map((ln, i) => {
        let bg: string | undefined;
        let color: string | undefined;
        if (ln.startsWith('@@')) {
          bg = 'rgba(124, 58, 237, 0.08)';
          color = '#7c3aed';
        }
        else if (ln.startsWith('+++') || ln.startsWith('---')) {
          color = '#475569';
        }
        else if (ln.startsWith('+')) {
          bg = 'rgba(16, 185, 129, 0.10)';
          color = '#047857';
        }
        else if (ln.startsWith('-')) {
          bg = 'rgba(239, 68, 68, 0.10)';
          color = '#b91c1c';
        }
        return (
          <span key={i} style={{ display: 'block', background: bg, color, paddingInline: 8 }}>
            {ln || ' '}
          </span>
        );
      })}
    </pre>
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

// ─── Styles ─────────────────────────────────────────────────────────

const panelStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'minmax(240px, 320px) 1fr',
  gap: 0,
  border: '1px solid var(--border, #e2e8f0)',
  borderRadius: 8,
  overflow: 'hidden',
  background: 'var(--bg-card, #fff)',
  minHeight: 320,
};

const listColumnStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  borderRight: '1px solid var(--border, #e2e8f0)',
  background: 'var(--bg-2, #f8fafc)',
  overflow: 'auto',
  maxHeight: '70vh',
};

const listHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  padding: '10px 12px',
  borderBottom: '1px solid var(--border, #e2e8f0)',
  fontSize: 12,
  textTransform: 'uppercase',
  letterSpacing: 0.5,
  color: 'var(--text-3, #64748b)',
  background: 'var(--bg-card, #fff)',
};

const listTitleStyle: React.CSSProperties = { fontWeight: 600 };

const refreshBtnStyle: React.CSSProperties = {
  background: 'transparent',
  border: '1px solid var(--border, #e2e8f0)',
  borderRadius: 6,
  padding: '2px 8px',
  cursor: 'pointer',
  color: 'var(--text-2, #475569)',
};

const rowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '24px 1fr',
  alignItems: 'center',
  gap: 8,
  padding: '8px 12px',
  background: 'transparent',
  border: 'none',
  borderBottom: '1px solid var(--border-light, #eef2f7)',
  textAlign: 'left',
  cursor: 'pointer',
  font: 'inherit',
  color: 'inherit',
};

const selectedRowStyle: React.CSSProperties = {
  ...rowStyle,
  background: 'rgba(124, 58, 237, 0.08)',
};

const commitRowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '64px 1fr auto',
  alignItems: 'center',
  gap: 8,
  padding: '10px 12px',
  background: 'transparent',
  border: 'none',
  borderBottom: '1px solid var(--border-light, #eef2f7)',
  textAlign: 'left',
  cursor: 'pointer',
  font: 'inherit',
  color: 'inherit',
};

const selectedCommitRowStyle: React.CSSProperties = {
  ...commitRowStyle,
  background: 'rgba(124, 58, 237, 0.08)',
};

const commitShaStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, monospace',
  fontSize: 12,
  color: 'var(--text-3, #64748b)',
};

const commitSubjectStyle: React.CSSProperties = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  fontSize: 13,
};

const commitMetaStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3, #64748b)',
};

const pathStyle: React.CSSProperties = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  fontSize: 13,
  fontFamily: 'ui-monospace, monospace',
};

const lineCountStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, monospace',
  fontSize: 11,
  marginLeft: 'auto',
};

const additionsStyle: React.CSSProperties = { color: '#047857' };
const deletionsStyle: React.CSSProperties = { color: '#b91c1c' };

const diffColumnStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  maxHeight: '70vh',
};

const diffEmptyStyle: React.CSSProperties = {
  padding: 20,
  color: 'var(--text-3, #64748b)',
  fontSize: 13,
};

const diffPreStyle: React.CSSProperties = {
  margin: 0,
  padding: '8px 0',
  fontFamily: 'ui-monospace, monospace',
  fontSize: 12,
  lineHeight: 1.5,
  overflow: 'auto',
  maxHeight: '70vh',
  background: 'var(--bg-card, #fff)',
};

const emptyRowStyle: React.CSSProperties = {
  padding: 14,
  color: 'var(--text-3, #64748b)',
  fontSize: 13,
};

const errorRowStyle: React.CSSProperties = {
  padding: 14,
  color: '#b91c1c',
  fontSize: 13,
};

const commitDiffLayoutStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateRows: 'auto 1fr',
  minHeight: 0,
  height: '100%',
};

const commitFileListStyle: React.CSSProperties = {
  borderBottom: '1px solid var(--border, #e2e8f0)',
  maxHeight: '30vh',
  overflow: 'auto',
};

const commitDiffPaneStyle: React.CSSProperties = {
  overflow: 'hidden',
  minHeight: 0,
};

function statusBadgeStyle(status: string): React.CSSProperties {
  let bg = '#e2e8f0';
  let color = '#475569';
  if (status === 'M') { bg = '#fef3c7'; color = '#92400e'; }
  else if (status === 'A') { bg = '#d1fae5'; color = '#047857'; }
  else if (status === 'D') { bg = '#fee2e2'; color = '#b91c1c'; }
  else if (status === 'R' || status === 'C') { bg = '#dbeafe'; color = '#1e40af'; }
  return {
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: 20,
    height: 20,
    borderRadius: 4,
    background: bg,
    color,
    fontSize: 11,
    fontWeight: 700,
    fontFamily: 'ui-monospace, monospace',
  };
}
