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
import type {
  ThreadCommitDto,
  ThreadCommitFileDto,
  ThreadWorkingFileDto,
  WorkUnitTaskDto,
} from '../types';
import { parseUnifiedDiff, type DiffHunk } from '../diffParse';
import { useThreadTasks } from './useThreadTasks';

type NavMode = 'commits' | 'files';

type DiffSelection =
  | { kind: 'working'; path: string }
  | { kind: 'commit-file'; sha: string; path: string }
  | { kind: 'commit'; sha: string }
  | null;

/**
 * Standalone "Code" page for a task — the diff/files viewer lifted out
 * of the old task-detail window. A simple back-bar on top, with the
 * commit/file navigator + diff column ({@link DiffPanels}) filling the
 * rest. Self-contained: it manages its own commit/working-file/diff
 * state and only needs the task's {@code threadId} and the task object.
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
      <div style={panelsGridStyle}>
        {task !== null && <DiffPanels threadId={threadId} task={task} onClose={onBack} />}
      </div>
    </div>
  );
}

/** The diff side of the task window: the commit/file navigator + the
 *  diff column. Returns the two grid items (nav, diff) directly — the
 *  parent owns the grid. */
function DiffPanels({
  threadId, task, onClose,
}: {
  threadId: string;
  task: WorkUnitTaskDto;
  onClose: () => void;
}) {
  const [navMode, setNavMode] = useState<NavMode>('commits');
  // Once the user has touched the toggle, stop auto-steering it — they
  // may want to inspect an empty tab on purpose.
  const navModePinned = useRef(false);
  const [commits, setCommits] = useState<ThreadCommitDto[] | null>(null);
  const [workingFiles, setWorkingFiles] = useState<ThreadWorkingFileDto[] | null>(null);
  const [commitFiles, setCommitFiles] = useState<ThreadCommitFileDto[] | null>(null);
  const [selection, setSelection] = useState<DiffSelection>(null);
  const [diffText, setDiffText] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [pushing, setPushing] = useState(false);
  const [pushNotice, setPushNotice] = useState<string | null>(null);

  // Pull the navigator's lists once when entering diff mode (and when
  // toggling — cheap, and keeps the list current as the agent commits).
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.listTaskCommits(threadId);
        if (!cancelled) setCommits(list);
      }
      catch {
        if (!cancelled) setCommits([]);
      }
    })();
    return () => { cancelled = true; };
  }, [threadId]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.listTaskWorkingChanges(threadId);
        if (!cancelled) setWorkingFiles(list);
      }
      catch {
        if (!cancelled) setWorkingFiles([]);
      }
    })();
    return () => { cancelled = true; };
  }, [threadId]);

  // Open on the tab that actually has something to show. A task that
  // hasn't committed yet (the common case mid-run) has only working-tree
  // changes, so defaulting to "Commits" left the diff column empty with
  // a "No commits yet" dead-end. Steer to "Changed files" in that case,
  // until the user picks a tab themselves.
  useEffect(() => {
    if (navModePinned.current) return;
    if (commits === null || workingFiles === null) return;
    if (commits.length === 0 && workingFiles.length > 0) {
      setNavMode('files');
    }
  }, [commits, workingFiles]);

  // Auto-select the first item when nav mode flips, so the diff column
  // is never empty.
  useEffect(() => {
    if (navMode === 'commits' && commits !== null && commits.length > 0 && selection?.kind !== 'commit-file' && selection?.kind !== 'commit') {
      setSelection({ kind: 'commit', sha: commits[0].sha });
    }
    if (navMode === 'files' && workingFiles !== null && workingFiles.length > 0 && selection?.kind !== 'working') {
      setSelection({ kind: 'working', path: workingFiles[0].path });
    }
  }, [navMode, commits, workingFiles, selection]);

  // When the user clicks a commit, fetch its per-file rollup so the
  // navigator can drill into individual files.
  useEffect(() => {
    if (selection?.kind !== 'commit' && selection?.kind !== 'commit-file') {
      setCommitFiles(null);
      return;
    }
    const sha = selection.sha;
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.listTaskCommitFiles(threadId, sha);
        if (!cancelled) setCommitFiles(list);
      }
      catch {
        if (!cancelled) setCommitFiles([]);
      }
    })();
    return () => { cancelled = true; };
  }, [threadId, selection]);

  // Load the diff text for the current selection. A {@code commit}
  // (no file) renders the commit's first file as a stand-in.
  useEffect(() => {
    let cancelled = false;
    setDiffText(null);
    if (selection === null) return;
    setLoading(true);
    void (async () => {
      try {
        let text = '';
        if (selection.kind === 'working') {
          text = await window.bridge.getTaskWorkingDiff(threadId, selection.path);
        }
        else if (selection.kind === 'commit-file') {
          text = await window.bridge.getTaskCommitDiff(threadId, selection.sha, selection.path);
        }
        else if (selection.kind === 'commit' && commitFiles !== null && commitFiles.length > 0) {
          text = await window.bridge.getTaskCommitDiff(threadId, selection.sha, commitFiles[0].path);
        }
        if (!cancelled) setDiffText(text);
      }
      catch (e) {
        if (!cancelled) setDiffText(`Could not load diff: ${e instanceof Error ? e.message : String(e)}`);
      }
      finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [threadId, selection, commitFiles]);

  const hunks = useMemo(() => parseUnifiedDiff(diffText), [diffText]);
  const totalAdditions = hunks.reduce(
    (sum, h) => sum + h.rows.filter(r => r.kind === 'add').length, 0);
  const totalDeletions = hunks.reduce(
    (sum, h) => sum + h.rows.filter(r => r.kind === 'del').length, 0);

  const onApproveAndPush = useCallback(() => {
    const ok = window.confirm(
      'Approve & push: this would push the task\'s branch and open / update '
      + 'its PR. Nothing publishes without this explicit confirmation. '
      + '(Phase 7 wires the actual push; for now this just records intent.)');
    if (!ok) return;
    setPushing(true);
    // Stub for the gated push — Phase 7 wires the actual PublishService
    // call. The confirm dialog satisfies the "nothing pushes without
    // approval" invariant.
    window.setTimeout(() => {
      setPushing(false);
      setPushNotice('Approved (no-op until Phase 7 wires PublishService).');
    }, 400);
  }, []);

  const onRequestFixes = useCallback(() => {
    setPushNotice(
      'Comments feed the agent in Phase 7. For now, post your review in the '
      + 'conversation column on the left and the agent will pick it up.');
  }, []);

  return (
    <>
      <div style={diffNavColStyle}>
        <div style={navToggleRowStyle}>
          <button
            type="button"
            onClick={() => { navModePinned.current = true; setNavMode('commits'); }}
            style={navToggleBtnStyle(navMode === 'commits')}
          >
            Commits{commits !== null && commits.length > 0 && ` · ${commits.length}`}
          </button>
          <button
            type="button"
            onClick={() => { navModePinned.current = true; setNavMode('files'); }}
            style={navToggleBtnStyle(navMode === 'files')}
          >
            Changed files{workingFiles !== null && workingFiles.length > 0 && ` · ${workingFiles.length}`}
          </button>
        </div>
        <div style={navListStyle}>
          {navMode === 'commits' && (
            <CommitsList
              commits={commits}
              commitFiles={commitFiles}
              selection={selection}
              onSelect={setSelection}
            />
          )}
          {navMode === 'files' && (
            <WorkingFilesList
              files={workingFiles}
              selection={selection}
              onSelect={setSelection}
            />
          )}
        </div>
      </div>

      <div style={diffColStyle}>
        <div style={diffHeaderStyle}>
          <span style={diffPathStyle}>{describeSelection(selection)}</span>
          {!loading && diffText !== null && hunks.length > 0 && (
            <span style={diffCountsStyle}>
              <span style={diffAddsStyle}>+{totalAdditions}</span>
              <span style={diffDelsStyle}>−{totalDeletions}</span>
            </span>
          )}
          <button
            type="button"
            onClick={onClose}
            style={closeDiffBtnStyle}
            title="Close the diff and return to the task conversation"
          >
            ✕ Close diff
          </button>
        </div>
        <div style={diffBodyStyle}>
          {loading && <div style={emptyStyle}>Loading diff…</div>}
          {!loading && diffText !== null && hunks.length === 0 && (
            <div style={emptyStyle}>
              {diffText.length > 0 ? diffText : 'No changes.'}
            </div>
          )}
          {!loading && hunks.length > 0 && <DiffHunks hunks={hunks} />}
        </div>
        <div style={diffActionsStyle}>
          <button
            type="button"
            style={diffActionBtnStyle('neutral')}
            onClick={onRequestFixes}
            title="Post a comment that the agent will pick up as guidance"
          >
            💬 Comment / Request fixes
          </button>
          <button
            type="button"
            style={diffActionBtnStyle('primary')}
            onClick={onApproveAndPush}
            disabled={pushing || task.prState === 'merged'}
            title="Push the branch and open / update the PR — gated by confirmation"
          >
            {pushing ? 'Pushing…' : '⤴ Approve & push'}
          </button>
        </div>
        {pushNotice !== null && (
          <div style={pushNoticeStyle}>{pushNotice}</div>
        )}
      </div>
    </>
  );
}

function describeSelection(selection: DiffSelection): string {
  if (selection === null) return 'Select a commit or file in the navigator';
  if (selection.kind === 'working') return `Working tree · ${selection.path}`;
  if (selection.kind === 'commit-file') return `${selection.sha.slice(0, 7)} · ${selection.path}`;
  return `Commit ${selection.sha.slice(0, 7)}`;
}

function CommitsList({
  commits, commitFiles, selection, onSelect,
}: {
  commits: ThreadCommitDto[] | null;
  commitFiles: ThreadCommitFileDto[] | null;
  selection: DiffSelection;
  onSelect: (s: DiffSelection) => void;
}) {
  if (commits === null) return <div style={emptyStyle}>Loading…</div>;
  if (commits.length === 0) return <div style={emptyStyle}>No commits on this branch yet.</div>;
  const activeSha = selection?.kind === 'commit' || selection?.kind === 'commit-file'
    ? selection.sha : null;
  return (
    <ul style={navItemsStyle}>
      {commits.map(c => (
        <li key={c.sha}>
          <button
            type="button"
            onClick={() => onSelect({ kind: 'commit', sha: c.sha })}
            style={navRowStyle(c.sha === activeSha && selection?.kind === 'commit')}
            title={c.subject}
          >
            <span style={navShaStyle}>{c.shortSha}</span>
            <span style={navTitleStyle}>{c.subject}</span>
          </button>
          {c.sha === activeSha && commitFiles !== null && (
            <ul style={navSubItemsStyle}>
              {commitFiles.map(f => (
                <li key={f.path}>
                  <button
                    type="button"
                    onClick={() => onSelect({ kind: 'commit-file', sha: c.sha, path: f.path })}
                    style={navSubRowStyle(
                      selection?.kind === 'commit-file' && selection.path === f.path)}
                    title={f.path}
                  >
                    <span style={navStatusStyle(f.status)}>{f.status}</span>
                    <span style={navSubPathStyle}>{f.path}</span>
                    <span style={navSubCountsStyle}>
                      <span style={diffAddsStyle}>+{f.additions}</span>
                      <span style={diffDelsStyle}>−{f.deletions}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </li>
      ))}
    </ul>
  );
}

function WorkingFilesList({
  files, selection, onSelect,
}: {
  files: ThreadWorkingFileDto[] | null;
  selection: DiffSelection;
  onSelect: (s: DiffSelection) => void;
}) {
  if (files === null) return <div style={emptyStyle}>Loading…</div>;
  if (files.length === 0) return <div style={emptyStyle}>Working tree is clean.</div>;
  const activePath = selection?.kind === 'working' ? selection.path : null;
  return (
    <ul style={navItemsStyle}>
      {files.map(f => (
        <li key={f.path}>
          <button
            type="button"
            onClick={() => onSelect({ kind: 'working', path: f.path })}
            style={navRowStyle(f.path === activePath)}
            title={f.path}
          >
            <span style={navStatusStyle(f.status)}>{f.status}</span>
            <span style={navTitleStyle}>{f.path}</span>
          </button>
        </li>
      ))}
    </ul>
  );
}

function DiffHunks({ hunks }: { hunks: DiffHunk[] }) {
  return (
    <div style={hunksContainerStyle}>
      {hunks.map((h, i) => (
        <div key={i} style={hunkBlockStyle}>
          <div style={hunkHeaderStyle}>{h.header}</div>
          {h.rows.filter(r => r.kind !== 'hunk-header').map((row, j) => (
            <div key={j} style={diffRowStyle(row.kind)}>
              <span style={lineNumStyle}>{row.oldLine ?? ''}</span>
              <span style={lineNumStyle}>{row.newLine ?? ''}</span>
              <span style={diffSigilStyle(row.kind)}>
                {row.kind === 'add' ? '+' : row.kind === 'del' ? '−' : ' '}
              </span>
              <span style={diffContentStyle}>{row.content}</span>
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

const TEAL = '#0d9488';
const TEAL_BG = 'rgba(13, 148, 136, 0.10)';
const TEAL_BORDER = 'rgba(13, 148, 136, 0.32)';

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

const panelsGridStyle: React.CSSProperties = {
  flex: 1,
  display: 'grid',
  gridTemplateColumns: '280px 1fr',
  gap: 12,
  padding: 12,
  minHeight: 0,
};

const diffNavColStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  background: 'rgba(255,255,255,0.78)',
  backdropFilter: 'blur(14px) saturate(125%)',
  WebkitBackdropFilter: 'blur(14px) saturate(125%)',
  border: '1px solid rgba(0,0,0,0.06)',
  borderRadius: 14,
  boxShadow: '0 4px 18px rgba(0,0,0,0.04)',
  overflow: 'hidden',
  minHeight: 0,
  height: '100%',
};

const diffColStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  background: 'rgba(255,255,255,0.78)',
  backdropFilter: 'blur(14px) saturate(125%)',
  WebkitBackdropFilter: 'blur(14px) saturate(125%)',
  border: '1px solid rgba(0,0,0,0.06)',
  borderRadius: 14,
  boxShadow: '0 4px 18px rgba(0,0,0,0.04)',
  overflow: 'hidden',
  minHeight: 0,
  height: '100%',
};

const closeDiffBtnStyle: React.CSSProperties = {
  flexShrink: 0,
  padding: '4px 10px',
  fontSize: 11,
  fontWeight: 600,
  color: 'var(--text-2)',
  background: 'rgba(0,0,0,0.04)',
  border: '1px solid rgba(0,0,0,0.08)',
  borderRadius: 7,
  cursor: 'pointer',
};

const navToggleRowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: 2,
  padding: 8,
  borderBottom: '1px solid rgba(0,0,0,0.06)',
};

function navToggleBtnStyle(active: boolean): React.CSSProperties {
  return {
    padding: '6px 8px',
    fontSize: 11,
    border: 'none',
    background: active ? TEAL : 'transparent',
    color: active ? '#fff' : 'var(--text-2)',
    borderRadius: 6,
    cursor: 'pointer',
    fontWeight: 600,
  };
}

const navListStyle: React.CSSProperties = {
  flex: 1,
  overflowY: 'auto',
  padding: 6,
};

const navItemsStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
};

function navRowStyle(active: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    width: '100%',
    padding: '6px 8px',
    border: 'none',
    background: active ? TEAL_BG : 'transparent',
    color: 'var(--text-1)',
    borderRadius: 6,
    fontSize: 11,
    cursor: 'pointer',
    textAlign: 'left',
    overflow: 'hidden',
  };
}

function navSubRowStyle(active: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    width: '100%',
    padding: '4px 8px 4px 22px',
    border: 'none',
    background: active ? TEAL_BG : 'transparent',
    color: active ? TEAL : 'var(--text-2)',
    borderRadius: 6,
    fontSize: 10,
    cursor: 'pointer',
    textAlign: 'left',
    overflow: 'hidden',
  };
}

const navSubItemsStyle: React.CSSProperties = {
  margin: '2px 0 6px',
  padding: 0,
  listStyle: 'none',
};

const navShaStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  color: 'var(--text-3)',
  fontSize: 10,
  flexShrink: 0,
  minWidth: 50,
};

const navTitleStyle: React.CSSProperties = {
  flex: 1,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const navSubPathStyle: React.CSSProperties = {
  flex: 1,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const navSubCountsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 4,
  fontSize: 9,
  flexShrink: 0,
};

function navStatusStyle(status: string): React.CSSProperties {
  const color = status === 'A' ? '#16a34a' : status === 'D' ? '#dc2626' : status === 'M' ? '#d97706' : '#6b7280';
  return {
    width: 14,
    textAlign: 'center',
    fontSize: 10,
    fontWeight: 700,
    color,
    flexShrink: 0,
  };
}

const diffHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '8px 14px',
  borderBottom: '1px solid rgba(0,0,0,0.06)',
  background: 'rgba(0,0,0,0.02)',
};

const diffPathStyle: React.CSSProperties = {
  flex: 1,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
  color: 'var(--text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const diffCountsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  fontSize: 11,
  fontWeight: 600,
};

const diffAddsStyle: React.CSSProperties = { color: '#16a34a' };
const diffDelsStyle: React.CSSProperties = { color: '#dc2626' };

const diffBodyStyle: React.CSSProperties = {
  flex: 1,
  overflow: 'auto',
  padding: 0,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
};

const hunksContainerStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
};

const hunkBlockStyle: React.CSSProperties = {
  borderTop: '1px solid rgba(0,0,0,0.04)',
};

const hunkHeaderStyle: React.CSSProperties = {
  padding: '4px 14px',
  background: 'rgba(59, 130, 246, 0.08)',
  color: '#1d4ed8',
  fontSize: 11,
  fontWeight: 600,
};

function diffRowStyle(kind: 'context' | 'add' | 'del' | 'hunk-header'): React.CSSProperties {
  let bg = 'transparent';
  if (kind === 'add') bg = 'rgba(22, 163, 74, 0.10)';
  else if (kind === 'del') bg = 'rgba(220, 38, 38, 0.10)';
  return {
    display: 'grid',
    gridTemplateColumns: '40px 40px 16px 1fr',
    background: bg,
    fontSize: 11,
    lineHeight: 1.5,
  };
}

const lineNumStyle: React.CSSProperties = {
  color: 'var(--text-4)',
  textAlign: 'right',
  paddingRight: 4,
  userSelect: 'none',
  fontSize: 10,
};

function diffSigilStyle(kind: 'context' | 'add' | 'del' | 'hunk-header'): React.CSSProperties {
  let color = 'var(--text-4)';
  if (kind === 'add') color = '#16a34a';
  else if (kind === 'del') color = '#dc2626';
  return {
    color,
    fontWeight: 700,
    textAlign: 'center',
    userSelect: 'none',
  };
}

const diffContentStyle: React.CSSProperties = {
  paddingLeft: 4,
  whiteSpace: 'pre',
  overflowX: 'auto',
};

const diffActionsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  padding: 12,
  borderTop: '1px solid rgba(0,0,0,0.06)',
  background: 'rgba(0,0,0,0.02)',
};

function diffActionBtnStyle(variant: 'primary' | 'neutral'): React.CSSProperties {
  return {
    padding: '8px 14px',
    fontSize: 12,
    border: variant === 'primary' ? 'none' : `1px solid ${TEAL_BORDER}`,
    background: variant === 'primary' ? 'linear-gradient(135deg, #0d9488, #0891b2)' : '#fff',
    color: variant === 'primary' ? '#fff' : TEAL,
    borderRadius: 8,
    fontWeight: 600,
    cursor: 'pointer',
    flex: variant === 'primary' ? 1 : 'unset',
  };
}

const pushNoticeStyle: React.CSSProperties = {
  padding: '8px 12px',
  background: TEAL_BG,
  color: TEAL,
  fontSize: 11,
  borderTop: `1px solid ${TEAL_BORDER}`,
};

const emptyStyle: React.CSSProperties = {
  padding: '6px 2px',
  fontSize: 11,
  color: 'var(--text-3)',
  lineHeight: 1.5,
};
