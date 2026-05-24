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
  ThreadCheckpointDto,
  ThreadCommitDto,
  ThreadCommitFileDto,
  ThreadDto,
  ThreadMessageDto,
  ThreadWorkingFileDto,
  UserProfileDto,
  WorkUnitTaskDto,
} from '../types';
import { parseUnifiedDiff, type DiffHunk } from '../diffParse';
import TaskChat from './TaskChat';
import { useThreadTasks } from './useThreadTasks';

type Props = {
  threadId: string;
  taskId: string;
  /** Navigate back to the parent thread's trunk window. */
  onBackToTrunk: () => void;
  /** Navigate to a different task within the same thread (only the
   *  trunk should switch tasks per the design, but the breadcrumb's
   *  parent task drop-down may want to jump siblings — for Phase 3
   *  this is a no-op; Phase 5 wires it through the zoom). */
  onOpenSiblingTask?: (taskId: string) => void;
};

type Mode = 'conversation' | 'terminal' | 'diff';

/** Navigator panel mode inside the diff view. {@code commits} lists
 *  branch commits, {@code files} lists uncommitted working-tree
 *  changes. Picking a row in either rescopes the right-hand diff. */
type NavMode = 'commits' | 'files';

type DiffSelection =
  | { kind: 'working'; path: string }
  | { kind: 'commit-file'; sha: string; path: string }
  | { kind: 'commit'; sha: string }
  | null;

/**
 * Task-detail window — the per-task altitude. Teal identity (full-
 * height spine, TASK altitude band, "Replying in Task n" composer
 * anchor) makes it unmistakable from the slate trunk window. Right
 * rail is task-scoped — Commits, metrics, checkpoints, Ship at the
 * bottom — and there is no task-switcher or Next (advancing to the
 * next task happens at the trunk; the task only Ships).
 */
export default function TaskDetailPage({
  threadId, taskId, onBackToTrunk,
}: Props) {
  const [thread, setThread] = useState<ThreadDto | null>(null);
  const [messages, setMessages] = useState<ThreadMessageDto[] | null>(null);
  const [mode, setMode] = useState<Mode>('conversation');
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [shipping, setShipping] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { tasks, refresh: refreshTasks } = useThreadTasks(threadId);
  const composerRef = useRef<HTMLTextAreaElement | null>(null);
  const [commits, setCommits] = useState<ThreadCommitDto[] | null>(null);
  const [checkpoints, setCheckpoints] = useState<ThreadCheckpointDto[] | null>(null);
  const [profile, setProfile] = useState<UserProfileDto | null>(null);

  useEffect(() => {
    void window.bridge.getUserProfile()
      .then(p => setProfile(p))
      .catch(() => { /* fall back to JC */ });
  }, []);

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
      try {
        const list = await window.bridge.getTaskCheckpoints(threadId);
        if (!cancelled) setCheckpoints(list);
      }
      catch {
        if (!cancelled) setCheckpoints([]);
      }
    })();
    return () => { cancelled = true; };
  }, [threadId]);

  const userInitials = useMemo(() => initialsFor(profile), [profile]);

  const task = useMemo(
    () => tasks?.find(t => t.id === taskId) ?? null,
    [tasks, taskId]);

  const loadThread = useCallback(async () => {
    try {
      const t = await window.bridge.getTask(threadId);
      setThread(t);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [threadId]);

  const loadMessages = useCallback(async () => {
    try {
      const all = await window.bridge.getTaskMessages(threadId);
      setMessages(all.filter(m => m.taskId === taskId));
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [threadId, taskId]);

  useEffect(() => { void loadThread(); }, [loadThread]);
  useEffect(() => { void loadMessages(); }, [loadMessages]);

  // Light poll while the thread is RUNNING — Phase 8+ will wire SSE
  // through to the task-scoped stream. Until then, a 5s safety net
  // catches the agent's responses without pegging the backend.
  useEffect(() => {
    if (thread?.status !== 'RUNNING') return;
    const handle = window.setInterval(() => {
      void loadMessages();
      void loadThread();
    }, 5_000);
    return () => window.clearInterval(handle);
  }, [thread?.status, loadMessages, loadThread]);

  const onSend = useCallback(async () => {
    if (sending || input.trim().length === 0) return;
    setSending(true);
    setError(null);
    try {
      await window.bridge.sendTaskMessage(threadId, input.trim());
      setInput('');
      await loadMessages();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSending(false);
    }
  }, [sending, input, threadId, loadMessages]);

  const onShip = useCallback(async () => {
    if (task === null || shipping) return;
    const ok = window.confirm(
      `Ship Task ${task.seq}`
      + (task.branchName !== null ? ` (${task.branchName})` : '')
      + ' — closes this task and returns to the thread trunk.');
    if (!ok) return;
    setShipping(true);
    setError(null);
    try {
      await window.bridge.shipAndContinue(threadId, task.id);
      onBackToTrunk();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setShipping(false);
    }
  }, [task, shipping, threadId, onBackToTrunk]);

  const taskTitle = task !== null ? taskLabel(task) : 'Loading…';
  const taskBranch = task?.branchName ?? null;
  const taskPr = task?.prNumber ?? null;
  const taskSeq = task?.seq ?? null;

  // Inline rename in the altitude band — pencil opens an input,
  // Enter PATCHes /tasks/{id}/name and refreshes the rail.
  const [renaming, setRenaming] = useState<boolean>(false);
  const [renameDraft, setRenameDraft] = useState<string>('');
  const [renameSaving, setRenameSaving] = useState<boolean>(false);
  const [renameError, setRenameError] = useState<string | null>(null);
  const renameInputRef = useRef<HTMLInputElement | null>(null);
  useEffect(() => {
    if (renaming && renameInputRef.current !== null) {
      renameInputRef.current.focus();
      renameInputRef.current.select();
    }
  }, [renaming]);
  const startRename = (): void => {
    if (task === null) return;
    setRenameDraft(task.name ?? taskTitle);
    setRenameError(null);
    setRenaming(true);
  };
  const cancelRename = (): void => {
    setRenaming(false);
    setRenameDraft('');
    setRenameError(null);
  };
  const saveRename = async (): Promise<void> => {
    if (task === null) return;
    const trimmed = renameDraft.trim();
    if (trimmed === (task.name ?? '')) {
      cancelRename();
      return;
    }
    setRenameSaving(true);
    try {
      await window.bridge.renameTaskUnit(task.threadId, task.id, trimmed);
      await refreshTasks();
      setRenaming(false);
      setRenameDraft('');
      setRenameError(null);
    }
    catch (err) {
      setRenameError(err instanceof Error ? err.message : String(err));
    }
    finally {
      setRenameSaving(false);
    }
  };

  // Aggregate diff counts for the top-bar "⇄ +N -M" button. Sum from
  // the loaded commits so the badge mirrors what View-diff will show.
  const totalAdds = useMemo(
    () => (commits ?? []).reduce(
      (s, c) => s + estimateCommitInsertions(c), 0),
    [commits]);
  const totalDels = useMemo(
    () => (commits ?? []).reduce(
      (s, c) => s + estimateCommitDeletions(c), 0),
    [commits]);

  const toolCallCount = useMemo(
    () => (messages ?? []).filter(m => m.role === 'tool' && m.type === 'tool_call').length,
    [messages]);

  // Terminal mode takes over the entire shell with a dark theme per
  // docs/mockups/design/tasks/thread-detail-terminal.png. The page
  // background, top bar, altitude band, and composer all switch to
  // the Warp/tmux palette while the diff/conversation views keep the
  // existing workspace-language treatment.
  const isTerminal: boolean = mode === 'terminal';
  const isDiff: boolean = mode === 'diff';

  return (
    <div style={isTerminal ? pageDarkStyle : pageStyle}>
      {!isTerminal && <div style={meshBgStyle} aria-hidden />}
      {!isTerminal && <div style={noiseBgStyle} aria-hidden />}
      <div style={isTerminal ? spineDarkStyle : spineStyle} aria-hidden />

      <div style={contentColStyle}>
        <header style={isTerminal ? headerDarkStyle : headerStyle}>
          <button
            type="button"
            onClick={onBackToTrunk}
            style={isTerminal ? backArrowDarkStyle : backArrowBtnStyle}
            title="Back to the thread trunk"
            aria-label="Back to thread"
          >
            ←
          </button>
          {!isTerminal && <div style={brandStyle} aria-hidden>B</div>}
          {isTerminal && (
            <span style={termBreadcrumbBrandStyle}>Threads</span>
          )}
          {isTerminal && <span style={termBreadcrumbSepStyle}>/</span>}
          <button
            type="button"
            onClick={onBackToTrunk}
            style={isTerminal ? crumbThreadBtnDarkStyle : crumbThreadBtnStyle}
            title="Back to the thread trunk"
          >
            {thread?.title ?? 'Thread'}
          </button>
          {isTerminal && taskBranch !== null && (
            <span style={termHeaderBranchChipStyle}>↗ {taskBranch}</span>
          )}
          {isTerminal && taskPr !== null && (
            <span style={termHeaderPrStyle}>● #{taskPr}</span>
          )}
          {isTerminal && task !== null && (
            <span style={termHeaderStatusStyle}>{task.status.toLowerCase()}</span>
          )}
          <div style={headerSpacerStyle} />
          {isTerminal && (
            <span style={termCtxBadgeStyle}>
              {thread?.model ?? 'claude'}
              <span style={{ marginLeft: 8, opacity: 0.7 }}>
                ctx {task !== null
                  ? Math.min(100, Math.round((task.tokensIn / 200_000) * 100))
                  : 0}%
              </span>
            </span>
          )}
          {!isTerminal && <ModeToggle mode={mode} onChange={setMode} />}
          {isTerminal && (
            <button
              type="button"
              onClick={() => setMode('conversation')}
              style={termExitBtnStyle}
              title="Back to conversation mode"
            >
              ✕ Exit terminal
            </button>
          )}
          <button
            type="button"
            style={isTerminal ? topDiffDarkStyle(isDiff) : topDiffBtnStyle(isDiff)}
            onClick={() => setMode(mode === 'diff' ? 'conversation' : 'diff')}
            title={mode === 'diff'
              ? 'Close diff and return to the conversation'
              : 'Open the three-column diff'}
          >
            ⇄ +{totalAdds} -{totalDels}
          </button>
          {thread !== null && !isTerminal && (
            <span style={statusPillStyle(thread.status)}>
              <span style={statusDotStyle(thread.status)} aria-hidden />
              {thread.status}
            </span>
          )}
          {!isTerminal && (
            <button type="button" style={menuDotsStyle} title="More" aria-label="More">⋯</button>
          )}
        </header>

        {!isTerminal && (
          <div style={altitudeBandStyle}>
            <span style={bandGlyphStyle}>● TASK</span>
            {renaming ? (
              <span style={bandTitleStyle}>
                <input
                  ref={renameInputRef}
                  value={renameDraft}
                  onChange={e => setRenameDraft(e.target.value)}
                  onKeyDown={e => {
                    if (e.key === 'Enter') { e.preventDefault(); void saveRename(); }
                    else if (e.key === 'Escape') { e.preventDefault(); cancelRename(); }
                  }}
                  disabled={renameSaving}
                  placeholder={taskBranch !== null ? humanizeBranch(taskBranch) : `Task ${taskSeq ?? ''}`}
                  style={bandRenameInputStyle}
                />
                <button
                  type="button"
                  onClick={() => { void saveRename(); }}
                  disabled={renameSaving}
                  style={bandRenameSaveStyle}
                  title="Save"
                >
                  {renameSaving ? '…' : '✓'}
                </button>
                <button
                  type="button"
                  onClick={cancelRename}
                  disabled={renameSaving}
                  style={bandRenameCancelStyle}
                  title="Cancel"
                >
                  ✕
                </button>
                {renameError !== null && (
                  <span style={bandRenameErrorStyle}>{renameError}</span>
                )}
              </span>
            ) : (
              <>
                <span style={bandTitleStyle}>{taskTitle}</span>
                {task !== null && (
                  <button
                    type="button"
                    onClick={startRename}
                    style={bandRenameBtnStyle}
                    title="Rename this task"
                    aria-label="Rename task"
                  >
                    ✎
                  </button>
                )}
              </>
            )}
            {taskBranch !== null && (
              <span style={bandBranchStyle}>↗ {taskBranch}</span>
            )}
            {taskPr !== null && (
              <span style={bandPrStyle}>⊕ PR #{taskPr}</span>
            )}
            {task !== null && (
              <span style={bandStatusStyle}>· {task.status.toLowerCase()}</span>
            )}
            <div style={bandSpacerStyle} />
          </div>
        )}

        {mode !== 'diff' && (
          <div style={bodyGridStyle}>
            <main style={mainStyle}>
              <div style={isTerminal ? chatCardDarkStyle : chatCardStyle}>
                {mode === 'conversation' && (
                  messages === null ? (
                    <div style={loadingCenterStyle}>Loading conversation…</div>
                  ) : (
                    <TaskChat
                      messages={messages}
                      taskSeq={taskSeq}
                      baseBranch={task?.baseBranch ?? null}
                      userInitials={userInitials}
                      isInFlight={thread?.status === 'RUNNING' || sending}
                    />
                  )
                )}
                {mode === 'terminal' && (
                  <TerminalPlaceholder
                    messages={messages}
                    cwd={task?.workingDir ?? null}
                    branch={taskBranch}
                    taskSeq={taskSeq}
                    threadTitle={thread?.title ?? null}
                    model={thread?.model ?? null}
                    costUsdMilli={task?.costUsdMilli ?? 0}
                    tokensIn={task?.tokensIn ?? 0}
                    runtimeSec={task !== null
                      ? Math.max(0, Math.floor((Date.now() - Date.parse(task.createdAt)) / 1000))
                      : 0}
                    ctxPct={task !== null
                      ? Math.min(100, Math.round((task.tokensIn / 200_000) * 100))
                      : 0}
                  />
                )}
              </div>

              <div style={isTerminal ? composerCardDarkStyle : composerCardStyle}>
                <div style={composerTopStyle}>
                  <div style={composerAnchorStyle}>
                    ↻ Replying in Task {taskSeq ?? ''} {taskBranch !== null && (
                      <span style={composerBranchStyle}>· {taskBranch}</span>
                    )}
                  </div>
                  <textarea
                    ref={composerRef}
                    value={input}
                    onChange={e => setInput(e.target.value)}
                    onKeyDown={e => {
                      if (e.key === 'Enter' && (e.metaKey || e.ctrlKey) && !sending) {
                        e.preventDefault();
                        void onSend();
                      }
                    }}
                    placeholder={`Continue Task ${taskSeq ?? ''} — describe a change, ask the agent, or paste an error.`}
                    style={composerInputStyle}
                    rows={3}
                    disabled={sending}
                  />
                </div>
                <div style={composerFooterStyle}>
                  <span style={composerScopeStyle}>▸ Task {taskSeq ?? ''}</span>
                  <span style={composerGlyphStyle} title="Previous prompt">↑</span>
                  <span style={composerGlyphStyle} title="Next prompt">↓</span>
                  <span style={composerFooterHintStyle}>
                    send · commands · files
                  </span>
                  <span style={composerAutoTagStyle} title="Agent auto-accepts safe tool calls">Auto</span>
                  <button
                    type="button"
                    onClick={() => { void onSend(); }}
                    disabled={sending || input.trim().length === 0}
                    style={sendBtnStyle}
                  >
                    {sending ? 'Sending…' : 'Send'}
                  </button>
                </div>
                {thread?.status === 'RUNNING' && (
                  <div style={queuedHintStyle}>queued — sends after current turn</div>
                )}
              </div>
            </main>

            <aside style={railStyle}>
              <div style={railThreadAnchorStyle}>
                Thread · {thread?.title ?? '—'}
              </div>

              <section style={railSectionStyle}>
                <div style={railHeadStyle}>
                  <span>COMMITS</span>
                  <span style={railHeadMutedStyle}>
                    this task{commits !== null && ` · ${commits.length}`}
                  </span>
                </div>
                <CommitsListSection commits={commits} />
                <button
                  type="button"
                  onClick={() => setMode('diff')}
                  style={viewDiffBtnStyle}
                  title="Open the three-column diff"
                >
                  ⇄ View code diff
                </button>
              </section>

              <section style={railSectionStyle}>
                <div style={railHeadStyle}>
                  <span>TASK METRICS</span>
                  <span style={railHeadMutedStyle}>this task</span>
                </div>
                <TaskMetricsTable task={task} toolCallCount={toolCallCount} />
              </section>

              <section style={railSectionStyle}>
                <div style={railHeadStyle}>
                  <span>CONTEXT WINDOW</span>
                </div>
                <ContextWindowMeter
                  tokensIn={task?.tokensIn ?? 0}
                  tokensOut={task?.tokensOut ?? 0}
                />
              </section>

              <section style={railSectionStyle}>
                <div style={railHeadStyle}>
                  <span>CHECKPOINTS</span>
                  <span style={railHeadMutedStyle}>
                    rewind{checkpoints !== null
                      && ` · ${checkpoints.filter(c => !c.isOverall).length}`}
                  </span>
                </div>
                <CheckpointsListSection checkpoints={checkpoints} />
              </section>

              <section style={railSectionStyle}>
                {(() => {
                  const isShipped = task?.status === 'COMPLETED';
                  const isTerminal = isShipped || task?.status === 'ERRORED';
                  return (
                    <>
                      <button
                        type="button"
                        onClick={() => { void onShip(); }}
                        disabled={task === null || shipping || isTerminal}
                        style={
                          isShipped
                            ? shipShippedDoneStyle
                            : isTerminal
                              ? shipShippedStyle
                              : shipPrimaryStyle}
                        title={task === null
                          ? 'No task loaded yet'
                          : isShipped
                            ? 'This task has already shipped'
                            : task.status === 'ERRORED'
                              ? 'This task ended in an error; recover from the thread trunk'
                              : `Ship Task ${task.seq} and return to the thread trunk`}
                      >
                        <span aria-hidden style={{ marginRight: 8 }}>
                          {isShipped ? '✓' : isTerminal ? '⨯' : '☁︎↑'}
                        </span>
                        {isShipped
                          ? 'Shipped'
                          : task?.status === 'ERRORED'
                            ? 'Errored — no ship'
                            : shipping ? 'Shipping…' : 'Ship — finalize & merge'}
                      </button>
                      <div style={shipHintStyle}>
                        {isShipped
                          ? 'Already merged. Open the trunk to start the next task.'
                          : task?.status === 'ERRORED'
                            ? 'Recover or abandon this task from the trunk; Ship is disabled while a task is in an errored state.'
                            : 'Finalises & merges this task, then takes you back to the thread — where the next task starts.'}
                      </div>
                    </>
                  );
                })()}
              </section>
            </aside>
          </div>
        )}
        {mode === 'diff' && task !== null && (
          <DiffThreeColumn
            threadId={threadId}
            task={task}
            messages={messages}
            threadTitle={thread?.title ?? null}
          />
        )}
      </div>

      {error !== null && (
        <div style={floatErrStyle}>{error}</div>
      )}
    </div>
  );
}

function initialsFor(profile: UserProfileDto | null): string {
  const source = profile?.name ?? profile?.login ?? '';
  if (source.length === 0) return 'JC';
  const parts = source.trim().split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function estimateCommitInsertions(_c: ThreadCommitDto): number {
  // Backend ThreadCommitDto today doesn't carry +/- counts at the
  // commit level; the totals are summed inside the diff view. Return
  // 0 so the top-bar pill stays honest until /commits surfaces stats.
  return 0;
}

function estimateCommitDeletions(_c: ThreadCommitDto): number {
  return 0;
}

function CommitsListSection({ commits }: { commits: ThreadCommitDto[] | null }) {
  if (commits === null) return <div style={emptyStyle}>Loading…</div>;
  if (commits.length === 0) return <div style={emptyStyle}>No commits on this branch yet.</div>;
  return (
    <ul style={commitsListStyle}>
      {commits.slice(0, 5).map(c => (
        <li key={c.sha} style={commitRowStyle}>
          <div style={commitTitleStyle} title={c.subject}>{c.subject}</div>
          <div style={commitMetaStyle}>
            <span style={commitShaStyle}>{c.shortSha}</span>
            <span style={commitTimeStyle}>{relativeShort(c.authoredAt)}</span>
          </div>
        </li>
      ))}
    </ul>
  );
}

function CheckpointsListSection({
  checkpoints,
}: {
  checkpoints: ThreadCheckpointDto[] | null;
}) {
  if (checkpoints === null) return <div style={emptyStyle}>Loading…</div>;
  const segments = checkpoints
    .filter(c => !c.isOverall && c.supersededAt === null)
    .sort((a, b) => b.seq - a.seq)
    .slice(0, 4);
  if (segments.length === 0) {
    return (
      <div style={emptyStyle}>
        No rewind points yet. The summariser writes one once enough
        tokens have accumulated.
      </div>
    );
  }
  return (
    <ul style={checkpointsListStyle}>
      {segments.map(cp => (
        <li key={cp.id} style={checkpointRowStyle}>
          <span style={checkpointDotStyle} aria-hidden />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={checkpointTitleStyle}>cp-{cp.seq} · rewind point</div>
            <div style={checkpointBlurbStyle} title={cp.summaryMd}>
              {previewBlurb(cp.summaryMd)}
            </div>
          </div>
        </li>
      ))}
    </ul>
  );
}

function previewBlurb(md: string): string {
  const line = md.split('\n').map(l => l.trim()).find(l => l.length > 0) ?? '';
  const stripped = line.replace(/^[-*•]\s*/, '').replace(/^#+\s*/, '');
  return stripped.length > 90 ? stripped.slice(0, 87) + '…' : stripped;
}

function ContextWindowMeter({
  tokensIn, tokensOut,
}: {
  tokensIn: number;
  tokensOut: number;
}) {
  // Estimate against a 200k context window (Sonnet 4.x default). The
  // bar surfaces *consumed input* — tokensIn is the dominant signal
  // for "how full is the window"; tokensOut don't count against it.
  const cap = 200_000;
  const used = tokensIn;
  const pct = Math.min(100, Math.round((used / cap) * 100));
  const tone = pct < 60 ? '#16a34a' : pct < 85 ? '#d97706' : '#dc2626';
  const safety = pct < 60 ? 'safe' : pct < 85 ? 'tight' : 'critical';
  return (
    <div>
      <div style={ctxLabelRowStyle}>
        <span style={{ color: tone, fontWeight: 700 }}>{pct}% {safety}</span>
        <span style={ctxNumStyle}>
          {formatTokensCompact(used)} / {formatTokensCompact(cap)}
        </span>
      </div>
      <div style={ctxTrackStyle}>
        <div style={{ ...ctxFillStyle, width: `${pct}%`, background: tone }} />
      </div>
    </div>
  );
}

function TaskMetricsTable({
  task, toolCallCount,
}: {
  task: WorkUnitTaskDto | null;
  toolCallCount: number;
}) {
  if (task === null) {
    return <div style={emptyStyle}>—</div>;
  }
  return (
    <dl style={vitalsListStyle}>
      <VitalRow label="Cost" value={`$${(task.costUsdMilli / 1000).toFixed(2)}`} />
      <VitalRow
        label="Tokens"
        value={`${formatTokensCompact(task.tokensIn)} → ${formatTokensCompact(task.tokensOut)}`}
      />
      <VitalRow label="Runtime" value={formatRuntime(task.createdAt)} />
      <VitalRow label="Tool calls" value={String(toolCallCount)} />
    </dl>
  );
}

function formatTokensCompact(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

function formatRuntime(createdAt: string): string {
  const start = Date.parse(createdAt);
  if (!Number.isFinite(start)) return '—';
  const secs = Math.floor((Date.now() - start) / 1000);
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  const s = secs % 60;
  if (h > 0) return `${h}h ${m}m ${s}s`;
  if (m > 0) return `${m}m ${s.toString().padStart(2, '0')}s`;
  return `${s}s`;
}

function relativeShort(iso: string): string {
  const start = Date.parse(iso);
  if (!Number.isFinite(start)) return '';
  const diff = Date.now() - start;
  if (diff < 60_000) return 'just now';
  const mins = Math.floor(diff / 60_000);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
}

function ModeToggle({
  mode, onChange,
}: {
  mode: Mode;
  onChange: (m: Mode) => void;
}) {
  return (
    <div role="tablist" style={modeToggleStyle}>
      <button
        type="button"
        role="tab"
        aria-selected={mode === 'conversation'}
        onClick={() => onChange('conversation')}
        style={modeBtnStyle(mode === 'conversation')}
      >
        Conversation
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={mode === 'terminal'}
        onClick={() => onChange('terminal')}
        style={modeBtnStyle(mode === 'terminal')}
      >
        Terminal
      </button>
    </div>
  );
}

function ConversationView({
  messages, threadTitle,
}: {
  messages: ThreadMessageDto[] | null;
  threadTitle: string | null;
}) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = scrollRef.current;
    if (el !== null) el.scrollTop = el.scrollHeight;
  }, [messages]);

  if (messages === null) {
    return <div style={loadingStyle}>Loading conversation…</div>;
  }

  return (
    <div ref={scrollRef} style={conversationScrollStyle}>
      <div style={forkedMarkerStyle}>
        ⑂ forked from the thread{threadTitle !== null && ` · ${threadTitle}`}
      </div>
      {messages.length === 0 ? (
        <div style={emptyStyle}>
          No conversation yet on this task. Send a message below to get the agent moving.
        </div>
      ) : (
        <ul style={chatListStyle}>
          {messages.map(m => (
            <MessageBubble key={m.id} message={m} />
          ))}
        </ul>
      )}
    </div>
  );
}

function DiffThreeColumn({
  threadId, task, messages, threadTitle,
}: {
  threadId: string;
  task: WorkUnitTaskDto;
  messages: ThreadMessageDto[] | null;
  threadTitle: string | null;
}) {
  const [navMode, setNavMode] = useState<NavMode>('commits');
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
    <div style={diffGridStyle}>
      <div style={diffConvColStyle}>
        <ConversationView messages={messages} threadTitle={threadTitle} />
      </div>

      <div style={diffNavColStyle}>
        <div style={navToggleRowStyle}>
          <button
            type="button"
            onClick={() => setNavMode('commits')}
            style={navToggleBtnStyle(navMode === 'commits')}
          >
            Commits{commits !== null && commits.length > 0 && ` · ${commits.length}`}
          </button>
          <button
            type="button"
            onClick={() => setNavMode('files')}
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
    </div>
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

function MessageBubble({ message }: { message: ThreadMessageDto }) {
  const role = bucketRole(message);
  if (role === 'system-fold') {
    return null;
  }
  const text = previewBody(message);
  const isUser = role === 'user';
  return (
    <li style={bubbleRowStyle(isUser)}>
      <div style={bubbleHeadStyle(isUser)}>{roleLabel(role)}</div>
      <div style={bubbleStyle(role)}>{text}</div>
    </li>
  );
}

function bucketRole(m: ThreadMessageDto): 'user' | 'assistant' | 'tool' | 'system' | 'system-fold' {
  if (m.role === 'user' && m.type === 'text') return 'user';
  if (m.role === 'assistant' && m.type === 'text') return 'assistant';
  if (m.role === 'assistant' && m.type === 'thinking') return 'assistant';
  if (m.role === 'tool') return 'tool';
  // Lifecycle / live deltas — keep terminal mode rich, but conversation
  // mode folds these so the chat reads as a chat.
  return 'system-fold';
}

function roleLabel(role: 'user' | 'assistant' | 'tool' | 'system'): string {
  if (role === 'user') return 'You';
  if (role === 'assistant') return 'Claude';
  if (role === 'tool') return 'tool';
  return 'system';
}

function previewBody(m: ThreadMessageDto): string {
  try {
    const parsed = JSON.parse(m.contentJson) as Record<string, unknown>;
    if (typeof parsed.text === 'string') return parsed.text;
    if (typeof parsed.summary === 'string') return parsed.summary;
    if (m.type === 'tool_call' && typeof parsed.toolName === 'string') {
      return `↪ ${parsed.toolName}`;
    }
    if (m.type === 'tool_result') {
      const out = parsed.output;
      return typeof out === 'string' ? out.slice(0, 240) : '[tool result]';
    }
  }
  catch {
    return m.contentJson.slice(0, 240);
  }
  return m.contentJson.slice(0, 240);
}

/**
 * Terminal-mode rendering of the task scrollback per
 * docs/mockups/design/tasks/thread-detail-terminal.png — dark
 * background, monospace, colourised per-event lines that mirror the
 * raw stream-json the CLI emits. tmux-style window bar on top with
 * the workspace + task labels; status bar on the bottom with the
 * task counters; everything else is the conversation rendered as a
 * straight log of bullets + bold key tokens + tool deltas.
 */
function TerminalPlaceholder({
  messages, cwd, branch, taskSeq, threadTitle, model, costUsdMilli, tokensIn, runtimeSec, ctxPct,
}: {
  messages: ThreadMessageDto[] | null;
  cwd: string | null;
  branch: string | null;
  taskSeq: number | null;
  threadTitle: string | null;
  model: string | null;
  costUsdMilli: number;
  tokensIn: number;
  runtimeSec: number;
  ctxPct: number;
}) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = scrollRef.current;
    if (el !== null) el.scrollTop = el.scrollHeight;
  }, [messages]);
  return (
    <div style={terminalShellStyle}>
      <div style={tmuxTopBarStyle}>
        <span style={tmuxBrandStyle}>ByteQuay</span>
        <span style={tmuxPaneStyle('inactive')}>1:cost-parser</span>
        <span style={tmuxPaneStyle('active')}>{taskSeq !== null ? `${taskSeq}:` : ''}{shortPaneLabel(threadTitle)}</span>
        <span style={tmuxSpacerStyle} />
        <span style={tmuxRightHintStyle}>{model ?? 'claude'}  ctx {ctxPct}%</span>
      </div>

      <div style={terminalTitleStyle}>
        <span style={terminalThreadTitleStyle}>{threadTitle ?? 'Thread'}</span>
        {branch !== null && (
          <span style={terminalBranchStyle}>↗ {branch}</span>
        )}
      </div>

      <div ref={scrollRef} style={terminalScrollStyle}>
        {messages === null && <div style={termInfoStyle}>loading…</div>}
        {messages !== null && messages.length === 0 && (
          <div style={termInfoStyle}>$ no messages yet — type below to start the task.</div>
        )}
        {messages !== null && messages.length > 0 && (
          <>
            {/* Per-task header strip echoing the mockup's right-aligned
                "task N · ⏱ runtime · $ cost" label that introduces the
                scrollback below. */}
            <div style={termTaskHeaderStyle}>
              <span style={termTaskHeaderTagStyle}>
                ▶ task {taskSeq ?? ''}
              </span>
              <span style={termTaskHeaderHintStyle}>
                {messages.length} events
              </span>
              <span style={tmuxSpacerStyle} />
              <span style={termTaskHeaderStatStyle}>
                ⏱ {formatRuntimeShort(runtimeSec)}
              </span>
              <span style={termTaskHeaderStatStyle}>
                $ ${(costUsdMilli / 1000).toFixed(2)}
              </span>
            </div>
            {messages.map(m => <TermLine key={m.id} message={m} />)}
            {/* If the task is parked at AWAITING_REVIEW or NEEDS_ATTENTION,
                drop a separator line that mirrors the mockup's "Next —
                parked Task N · awaiting review" status strip. */}
          </>
        )}
      </div>

      <div style={tmuxStatusBarStyle}>
        <span style={tmuxBrandStyle}>[ByteQuay]</span>
        <span style={tmuxPaneStyle('inactive')}>1:cost-parser</span>
        <span style={tmuxPaneStyle('activeAlt')}>{taskSeq !== null ? `${taskSeq}:` : ''}{shortPaneLabel(threadTitle)}</span>
        <span style={tmuxSpacerStyle} />
        <span style={tmuxStatNumStyle}>↑↓ {(messages ?? []).filter(m => m.type === 'turn_done').length}</span>
        <span style={tmuxStatNumStyle}>$ ${(costUsdMilli / 1000).toFixed(2)}</span>
        <span style={tmuxStatNumStyle}>RUN · {formatRuntimeShort(runtimeSec)} · ctx {ctxPct}%</span>
      </div>

      {/* cwd is rendered in a tooltip on the title so it doesn't crowd the bar */}
      <div style={termCwdHintStyle} title={cwd ?? '—'}>{cwd ?? '—'}</div>
    </div>
  );
}

// Per-task header strip — matches the mockup's right-aligned task
// label that introduces each task's chunk of scrollback.
const termTaskHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  padding: '8px 0 10px',
  borderBottom: '1px dashed rgba(255,255,255,0.10)',
  marginBottom: 4,
  color: '#94a3b8',
  fontSize: 11,
};

const termTaskHeaderTagStyle: React.CSSProperties = {
  color: '#22c55e',
  background: 'rgba(34, 197, 94, 0.10)',
  padding: '2px 10px',
  borderRadius: 4,
  fontWeight: 700,
  letterSpacing: '0.04em',
};

const termTaskHeaderHintStyle: React.CSSProperties = {
  color: '#64748b',
  fontStyle: 'italic',
  fontSize: 10,
};

const termTaskHeaderStatStyle: React.CSSProperties = {
  color: '#cdd6f4',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 10,
  fontVariantNumeric: 'tabular-nums',
};

function TermLine({ message }: { message: ThreadMessageDto }) {
  const text = (() => {
    try {
      const p = JSON.parse(message.contentJson) as Record<string, unknown>;
      if (typeof p.text === 'string') return p.text;
      if (typeof p.summary === 'string') return p.summary;
      if (message.type === 'tool_call') {
        const tool = p.toolName ?? '';
        const input = p.input ?? {};
        const inObj = typeof input === 'object' && input !== null ? input as Record<string, unknown> : {};
        const path = inObj.file_path ?? inObj.path ?? inObj.command ?? '';
        return `${tool} ${path}`.trim();
      }
      if (message.type === 'tool_result') {
        const out = p.output;
        if (typeof out === 'string') return out.split('\n')[0].slice(0, 200);
        return '(tool result)';
      }
      if (message.type === 'turn_done') {
        return `turn done · ${p.tokensIn ?? 0}→${p.tokensOut ?? 0}t · ${(((p.costUsdMilli as number) ?? 0) / 1000).toFixed(2)}$`;
      }
      if (message.type === 'session_started') return `session_started · ${p.sessionId ?? ''}`;
      if (message.type === 'session_ended') return `session_ended · exit ${p.exitCode ?? '?'}`;
    }
    catch { /* fall through */ }
    return message.contentJson;
  })();
  return (
    <div style={termRowStyle(message)}>
      <span style={termGlyphStyle(message)}>{glyphFor(message)}</span>
      <span style={termContentStyle(message)}>{text}</span>
    </div>
  );
}

function glyphFor(m: ThreadMessageDto): string {
  if (m.role === 'user') return '›';
  if (m.role === 'assistant' && m.type === 'thinking') return '*';
  if (m.role === 'assistant') return '◆';
  if (m.type === 'tool_call') return '◐';
  if (m.type === 'tool_result') return '◑';
  if (m.type === 'turn_done') return '✓';
  if (m.type === 'session_started') return '$';
  if (m.type === 'session_ended') return '·';
  return '·';
}

function shortPaneLabel(title: string | null): string {
  if (title === null) return 'task';
  return title.toLowerCase().split(/\s+/).slice(0, 2).join('-').slice(0, 16);
}

function formatRuntimeShort(secs: number): string {
  if (secs < 60) return `${secs}s`;
  const m = Math.floor(secs / 60);
  const s = secs % 60;
  if (m < 60) return `${m}m ${s.toString().padStart(2, '0')}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${(m % 60).toString().padStart(2, '0')}m`;
}

function MetricsTable({ task }: { task: WorkUnitTaskDto | null }) {
  if (task === null) {
    return <div style={emptyStyle}>—</div>;
  }
  return (
    <dl style={vitalsListStyle}>
      <VitalRow label="Status" value={task.status.toLowerCase()} />
      <VitalRow label="seq" value={String(task.seq)} />
      {task.prNumber !== null && (
        <VitalRow label="PR" value={`#${task.prNumber}`} />
      )}
      <VitalRow label="task type" value={task.taskType} />
    </dl>
  );
}

function VitalRow({ label, value }: { label: string; value: string }) {
  return (
    <div style={vitalsRowStyle}>
      <dt style={vitalsLabelStyle}>{label}</dt>
      <dd style={vitalsValueStyle}>{value}</dd>
    </div>
  );
}

function ContextRow({
  label, value, mono, truncate,
}: {
  label: string;
  value: string;
  mono?: boolean;
  truncate?: boolean;
}) {
  return (
    <div style={vitalsRowStyle}>
      <dt style={vitalsLabelStyle}>{label}</dt>
      <dd
        style={{
          ...vitalsValueStyle,
          fontFamily: mono ? 'ui-monospace, SFMono-Regular, Menlo, monospace' : undefined,
          maxWidth: truncate ? '160px' : undefined,
          overflow: truncate ? 'hidden' : undefined,
          textOverflow: truncate ? 'ellipsis' : undefined,
          whiteSpace: truncate ? 'nowrap' : undefined,
        }}
      >
        {value}
      </dd>
    </div>
  );
}

function taskLabel(task: WorkUnitTaskDto): string {
  if (task.name !== null && task.name.length > 0) {
    return task.name;
  }
  if (task.branchName !== null && task.branchName.length > 0) {
    return humanizeBranch(task.branchName);
  }
  return `Task ${task.seq}`;
}

function humanizeBranch(branch: string): string {
  let rest = branch;
  const slash = rest.lastIndexOf('/');
  if (slash >= 0 && slash < rest.length - 1) rest = rest.slice(slash + 1);
  const hex = rest.match(/^[a-f0-9]{8,}-(.+)$/i);
  if (hex !== null) rest = hex[1];
  const spaced = rest.replace(/[-_]+/g, ' ').trim();
  if (spaced.length === 0) return branch;
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

/* ── Styles ────────────────────────────────────────────────────────── */

const TEAL = '#0d9488';
const TEAL_BG = 'rgba(13, 148, 136, 0.10)';
const TEAL_BORDER = 'rgba(13, 148, 136, 0.32)';

const pageStyle: React.CSSProperties = {
  position: 'relative',
  minHeight: '100vh',
  background: '#fafafe',
  color: 'var(--text-1)',
  overflow: 'hidden',
};

const meshBgStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  pointerEvents: 'none',
  background: [
    'radial-gradient(circle at 18% 16%, rgba(13, 148, 136, 0.10), transparent 45%)',
    'radial-gradient(circle at 82% 22%, rgba(56, 189, 248, 0.10), transparent 45%)',
    'radial-gradient(circle at 12% 86%, rgba(74, 222, 128, 0.10), transparent 50%)',
    'radial-gradient(circle at 86% 78%, rgba(244, 114, 182, 0.06), transparent 50%)',
  ].join(','),
  zIndex: 0,
};

const noiseBgStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  pointerEvents: 'none',
  opacity: 0.045,
  mixBlendMode: 'overlay',
  backgroundImage:
    'url("data:image/svg+xml;utf8,'
    + '<svg xmlns=\'http://www.w3.org/2000/svg\' width=\'160\' height=\'160\'>'
    + '<filter id=\'n\'><feTurbulence type=\'fractalNoise\' baseFrequency=\'0.8\' numOctaves=\'2\' stitchTiles=\'stitch\'/></filter>'
    + '<rect width=\'100%\' height=\'100%\' filter=\'url(%23n)\'/></svg>")',
  zIndex: 0,
};

const spineStyle: React.CSSProperties = {
  position: 'fixed',
  top: 0,
  bottom: 0,
  left: 0,
  width: 4,
  background: TEAL,
  zIndex: 2,
};

const contentColStyle: React.CSSProperties = {
  position: 'relative',
  zIndex: 1,
  paddingLeft: 12,
  display: 'flex',
  flexDirection: 'column',
  minHeight: '100vh',
};

const headerStyle: React.CSSProperties = {
  position: 'sticky',
  top: 0,
  zIndex: 3,
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  padding: '10px 18px',
  background: 'rgba(255, 255, 255, 0.66)',
  backdropFilter: 'blur(14px) saturate(125%)',
  WebkitBackdropFilter: 'blur(14px) saturate(125%)',
  borderBottom: '1px solid rgba(0,0,0,0.05)',
};

const backBtnStyle: React.CSSProperties = {
  border: '1px solid rgba(0,0,0,0.08)',
  background: 'rgba(255,255,255,0.6)',
  padding: '4px 10px',
  fontSize: 12,
  borderRadius: 6,
  cursor: 'pointer',
  color: 'var(--text-2)',
};

const breadcrumbStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  fontSize: 12,
  overflow: 'hidden',
};

const crumbWorkspaceStyle: React.CSSProperties = {
  color: 'var(--text-3)',
};

const crumbSepStyle: React.CSSProperties = {
  color: 'var(--text-4)',
};

const crumbThreadStyle: React.CSSProperties = {
  color: 'var(--text-2)',
  fontWeight: 500,
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  maxWidth: 220,
};

const crumbTaskStyle: React.CSSProperties = {
  color: TEAL,
  fontWeight: 600,
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
};

const modeToggleStyle: React.CSSProperties = {
  display: 'flex',
  gap: 2,
  padding: 2,
  background: 'rgba(0,0,0,0.04)',
  borderRadius: 8,
};

function modeBtnStyle(active: boolean): React.CSSProperties {
  return {
    padding: '4px 10px',
    fontSize: 11,
    border: 'none',
    background: active ? '#fff' : 'transparent',
    color: active ? TEAL : 'var(--text-3)',
    borderRadius: 6,
    cursor: 'pointer',
    fontWeight: 600,
    boxShadow: active ? '0 1px 2px rgba(0,0,0,0.06)' : 'none',
  };
}

const altitudeBandStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 14,
  padding: '8px 18px',
  background: TEAL_BG,
  borderBottom: `1px solid ${TEAL_BORDER}`,
  fontSize: 12,
};

const bandGlyphStyle: React.CSSProperties = {
  fontWeight: 700,
  letterSpacing: '0.08em',
  color: TEAL,
};

const bandTitleStyle: React.CSSProperties = {
  fontWeight: 600,
  color: 'var(--text-1)',
};

const bandBranchStyle: React.CSSProperties = {
  color: 'var(--text-3)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
};

const bandPrStyle: React.CSSProperties = {
  color: TEAL,
  fontWeight: 600,
};

const bandSpacerStyle: React.CSSProperties = { flex: 1 };

const bandRenameBtnStyle: React.CSSProperties = {
  border: '1px solid rgba(15, 118, 110, 0.18)',
  background: 'rgba(15, 118, 110, 0.06)',
  color: TEAL,
  cursor: 'pointer',
  padding: '2px 6px',
  fontSize: 11,
  lineHeight: 1,
  borderRadius: 6,
  flexShrink: 0,
};

const bandRenameInputStyle: React.CSSProperties = {
  fontWeight: 600,
  fontSize: 13,
  padding: '4px 8px',
  borderRadius: 8,
  border: `1px solid ${TEAL}`,
  background: '#fff',
  outline: 'none',
  color: 'var(--text-1)',
  minWidth: 220,
};

const bandRenameSaveStyle: React.CSSProperties = {
  border: 'none',
  background: TEAL,
  color: '#fff',
  cursor: 'pointer',
  padding: '3px 8px',
  fontSize: 11,
  fontWeight: 700,
  borderRadius: 6,
};

const bandRenameCancelStyle: React.CSSProperties = {
  border: '1px solid rgba(0,0,0,0.10)',
  background: '#fff',
  color: 'var(--text-2)',
  cursor: 'pointer',
  padding: '3px 8px',
  fontSize: 11,
  borderRadius: 6,
};

const bandRenameErrorStyle: React.CSSProperties = {
  fontSize: 11,
  color: '#b91c1c',
  marginLeft: 6,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  maxWidth: 240,
};

function diffBtnStyle(active: boolean): React.CSSProperties {
  return {
    padding: '4px 10px',
    fontSize: 12,
    border: `1px solid ${TEAL_BORDER}`,
    background: active ? TEAL : '#fff',
    color: active ? '#fff' : TEAL,
    borderRadius: 6,
    fontWeight: 600,
    cursor: 'pointer',
  };
}

const railLinkBtnStyle2: React.CSSProperties = {
  fontSize: 10,
  padding: '2px 6px',
  border: `1px solid ${TEAL_BORDER}`,
  background: '#fff',
  color: TEAL,
  borderRadius: 6,
  cursor: 'pointer',
  fontWeight: 600,
};

const bodyGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 320px',
  gap: 0,
  padding: 0,
  flex: 1,
  alignItems: 'stretch',
  minHeight: 0,
};

const railStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
  padding: '14px 16px 14px 16px',
  borderLeft: '1px solid rgba(0,0,0,0.08)',
  background: 'rgba(248, 247, 252, 0.5)',
  overflowY: 'auto',
  maxHeight: 'calc(100vh - 96px)',
};

/* ── Terminal-mode dark shell styles (whole-page takeover) ────── */

const pageDarkStyle: React.CSSProperties = {
  position: 'relative',
  minHeight: '100vh',
  background: '#0a0e14',
  color: '#cdd6f4',
  overflow: 'hidden',
};

const spineDarkStyle: React.CSSProperties = {
  position: 'fixed',
  top: 0,
  bottom: 0,
  left: 0,
  width: 4,
  background: '#0d9488',
  zIndex: 2,
};

const headerDarkStyle: React.CSSProperties = {
  position: 'sticky',
  top: 0,
  zIndex: 3,
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  padding: '8px 18px',
  background: '#1a1f29',
  borderBottom: '1px solid #0f1318',
  color: '#cdd6f4',
};

const backArrowDarkStyle: React.CSSProperties = {
  width: 28,
  height: 28,
  border: '1px solid #2a2f3a',
  background: '#0d1117',
  borderRadius: 6,
  fontSize: 14,
  color: '#cdd6f4',
  cursor: 'pointer',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
};

const termBreadcrumbBrandStyle: React.CSSProperties = {
  color: '#94a3b8',
  fontSize: 12,
};

const termBreadcrumbSepStyle: React.CSSProperties = {
  color: '#475569',
  fontSize: 12,
};

const crumbThreadBtnDarkStyle: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  padding: 0,
  fontSize: 13,
  fontWeight: 600,
  color: '#f8fafc',
  cursor: 'pointer',
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  maxWidth: 320,
};

const termHeaderBranchChipStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 11,
  color: '#22c55e',
  background: 'rgba(34, 197, 94, 0.10)',
  border: '1px solid rgba(34, 197, 94, 0.25)',
  padding: '2px 8px',
  borderRadius: 6,
};

const termHeaderPrStyle: React.CSSProperties = {
  fontSize: 11,
  color: '#7c3aed',
  background: 'rgba(124, 58, 237, 0.10)',
  border: '1px solid rgba(124, 58, 237, 0.25)',
  padding: '2px 8px',
  borderRadius: 6,
  fontWeight: 600,
};

const termHeaderStatusStyle: React.CSSProperties = {
  fontSize: 11,
  color: '#fb923c',
  fontStyle: 'italic',
};

const termCtxBadgeStyle: React.CSSProperties = {
  fontSize: 10,
  color: '#94a3b8',
  background: '#0d1117',
  border: '1px solid #2a2f3a',
  padding: '3px 10px',
  borderRadius: 6,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
};

const termExitBtnStyle: React.CSSProperties = {
  border: '1px solid rgba(34, 197, 94, 0.30)',
  background: 'rgba(34, 197, 94, 0.12)',
  color: '#22c55e',
  padding: '4px 10px',
  fontSize: 11,
  borderRadius: 6,
  cursor: 'pointer',
  fontWeight: 600,
};

function topDiffDarkStyle(active: boolean): React.CSSProperties {
  return {
    padding: '4px 10px',
    fontSize: 11,
    border: '1px solid #2a2f3a',
    background: active ? '#0d9488' : '#0d1117',
    color: active ? '#0a0e14' : '#cdd6f4',
    borderRadius: 6,
    fontWeight: 600,
    cursor: 'pointer',
    fontFeatureSettings: '"tnum"',
    fontVariantNumeric: 'tabular-nums',
  };
}

const chatCardDarkStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  background: '#0a0e14',
  border: '1px solid #1f2937',
  borderRadius: 14,
  overflow: 'hidden',
  minHeight: 0,
  color: '#cdd6f4',
};

const composerCardDarkStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  background: '#0d1117',
  border: '1px solid #1f2937',
  borderRadius: 14,
  overflow: 'hidden',
  flexShrink: 0,
  color: '#cdd6f4',
};

const backArrowBtnStyle: React.CSSProperties = {
  width: 28,
  height: 28,
  border: '1px solid rgba(0,0,0,0.10)',
  background: '#fff',
  borderRadius: 8,
  fontSize: 14,
  color: 'var(--text-2)',
  cursor: 'pointer',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
  transition: 'background 140ms ease, transform 140ms ease',
};

const brandStyle: React.CSSProperties = {
  width: 22,
  height: 22,
  borderRadius: 6,
  background: 'linear-gradient(135deg, #16a34a, #15803d)',
  color: '#fff',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 12,
  fontWeight: 800,
  letterSpacing: '0.04em',
  flexShrink: 0,
};

const crumbThreadBtnStyle: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  padding: 0,
  fontSize: 13,
  fontWeight: 600,
  color: 'var(--text-2)',
  cursor: 'pointer',
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  maxWidth: 280,
};

const headerSpacerStyle: React.CSSProperties = { flex: 1 };

function topDiffBtnStyle(active: boolean): React.CSSProperties {
  return {
    padding: '4px 10px',
    fontSize: 11,
    border: '1px solid rgba(0,0,0,0.10)',
    background: active ? TEAL : '#fff',
    color: active ? '#fff' : 'var(--text-2)',
    borderRadius: 6,
    fontWeight: 600,
    cursor: 'pointer',
    fontFeatureSettings: '"tnum"',
    fontVariantNumeric: 'tabular-nums',
  };
}

function statusPillStyle(status: string): React.CSSProperties {
  const tone = status === 'RUNNING' ? '#16a34a' : status === 'ERRORED' ? '#b91c1c' : '#475569';
  return {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 6,
    fontSize: 10,
    padding: '3px 10px',
    borderRadius: 999,
    border: `1px solid ${tone}40`,
    color: tone,
    background: `${tone}14`,
    fontWeight: 700,
    letterSpacing: '0.06em',
  };
}

function statusDotStyle(status: string): React.CSSProperties {
  const tone = status === 'RUNNING' ? '#16a34a' : status === 'ERRORED' ? '#b91c1c' : '#475569';
  return {
    width: 6,
    height: 6,
    borderRadius: 999,
    background: tone,
    boxShadow: status === 'RUNNING' ? `0 0 0 2px ${tone}30` : 'none',
  };
}

const menuDotsStyle: React.CSSProperties = {
  width: 24,
  height: 24,
  borderRadius: 6,
  border: 'none',
  background: 'transparent',
  color: 'var(--text-3)',
  fontSize: 16,
  cursor: 'pointer',
};

const bandStatusStyle: React.CSSProperties = {
  color: 'var(--text-4)',
  fontStyle: 'italic',
  fontSize: 11,
};

const chatCardStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  background: 'rgba(255,255,255,0.78)',
  border: '1px solid rgba(0,0,0,0.08)',
  borderRadius: 14,
  boxShadow: '0 4px 18px rgba(0,0,0,0.04)',
  overflow: 'hidden',
  minHeight: 0,
};

const loadingCenterStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 12,
  color: 'var(--text-3)',
  fontStyle: 'italic',
};

const composerCardStyle: React.CSSProperties = {
  // Two-zone card: top region holds the anchor + textarea on a white
  // background; the footer row sits inside a tinted bottom band with
  // its own top divider so the glyph row reads as dedicated chrome
  // instead of bleeding into the textarea below it.
  display: 'flex',
  flexDirection: 'column',
  background: '#ffffff',
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 14,
  boxShadow:
    '0 4px 14px rgba(0,0,0,0.04),'
    + ' inset 0 1px 0 rgba(255,255,255,0.8)',
  flexShrink: 0,
  overflow: 'hidden',
};

const composerTopStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '10px 14px 8px',
};

const composerGlyphStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-4)',
  padding: '0 2px',
  cursor: 'default',
};

const composerAutoTagStyle: React.CSSProperties = {
  fontSize: 10,
  padding: '2px 8px',
  background: 'rgba(13, 148, 136, 0.10)',
  color: TEAL,
  border: `1px solid ${TEAL_BORDER}`,
  borderRadius: 999,
  fontWeight: 700,
  letterSpacing: '0.04em',
  marginRight: 6,
};

const queuedHintStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--text-4)',
  fontStyle: 'italic',
  textAlign: 'right',
  marginTop: 2,
};

const railThreadAnchorStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 600,
  color: 'var(--text-3)',
  letterSpacing: '0.02em',
  padding: '0 4px',
  marginBottom: -4,
};

const viewDiffBtnStyle: React.CSSProperties = {
  marginTop: 10,
  width: '100%',
  padding: '6px 10px',
  fontSize: 11,
  border: `1px solid ${TEAL_BORDER}`,
  background: '#fff',
  color: TEAL,
  borderRadius: 8,
  cursor: 'pointer',
  fontWeight: 600,
};

const shipPrimaryStyle: React.CSSProperties = {
  width: '100%',
  padding: '12px 14px',
  fontSize: 13,
  border: 'none',
  background: 'linear-gradient(135deg, #0d9488, #0891b2)',
  color: '#fff',
  borderRadius: 10,
  fontWeight: 700,
  letterSpacing: '0.02em',
  cursor: 'pointer',
  boxShadow:
    '0 6px 18px rgba(13,148,136,0.25),'
    + ' 0 1px 2px rgba(0,0,0,0.04),'
    + ' inset 0 1px 0 rgba(255,255,255,0.2)',
};

// Terminal-state Ship: the task already merged (or errored), so the
// button reads as a static "Shipped" / "Errored" pill rather than a
// CTA — flat surface, no shadow, not-allowed cursor.
const shipShippedStyle: React.CSSProperties = {
  width: '100%',
  padding: '12px 14px',
  fontSize: 13,
  border: '1px solid rgba(0,0,0,0.08)',
  background: 'rgba(0,0,0,0.04)',
  color: 'var(--text-3)',
  borderRadius: 10,
  fontWeight: 700,
  letterSpacing: '0.02em',
  cursor: 'not-allowed',
};

// Shipped variant — a calm but distinct deep-purple wash so the
// task's terminal-success state is visible at a glance without
// reading like a fresh CTA. Errored keeps the neutral grey pill.
const shipShippedDoneStyle: React.CSSProperties = {
  width: '100%',
  padding: '12px 14px',
  fontSize: 13,
  border: '1px solid rgba(91, 33, 182, 0.42)',
  background: 'linear-gradient(135deg, #5b21b6 0%, #6d28d9 100%)',
  color: '#f5f3ff',
  borderRadius: 10,
  fontWeight: 700,
  letterSpacing: '0.02em',
  cursor: 'not-allowed',
  boxShadow:
    '0 6px 18px rgba(91,33,182,0.28),'
    + ' inset 0 1px 0 rgba(255,255,255,0.18)',
};

const commitsListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

const commitRowStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  fontSize: 12,
};

const commitTitleStyle: React.CSSProperties = {
  color: 'var(--text-1)',
  fontWeight: 500,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const commitMetaStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  fontSize: 10,
};

const commitShaStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  color: 'var(--text-3)',
};

const commitTimeStyle: React.CSSProperties = {
  color: 'var(--text-4)',
};

const checkpointsListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

const checkpointRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  alignItems: 'flex-start',
  fontSize: 11,
};

const checkpointDotStyle: React.CSSProperties = {
  width: 6,
  height: 6,
  borderRadius: 999,
  background: TEAL,
  marginTop: 6,
  flexShrink: 0,
};

const checkpointTitleStyle: React.CSSProperties = {
  fontWeight: 600,
  color: 'var(--text-1)',
};

const checkpointBlurbStyle: React.CSSProperties = {
  color: 'var(--text-3)',
  fontSize: 10,
  lineHeight: 1.4,
  marginTop: 2,
  overflow: 'hidden',
  display: '-webkit-box',
  WebkitLineClamp: 2,
  WebkitBoxOrient: 'vertical',
};

const ctxLabelRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'baseline',
  fontSize: 11,
  marginBottom: 6,
};

const ctxNumStyle: React.CSSProperties = {
  color: 'var(--text-4)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontVariantNumeric: 'tabular-nums',
};

const ctxTrackStyle: React.CSSProperties = {
  height: 6,
  background: 'rgba(0,0,0,0.06)',
  borderRadius: 999,
  overflow: 'hidden',
};

const ctxFillStyle: React.CSSProperties = {
  height: '100%',
  borderRadius: 999,
  transition: 'width 140ms ease',
};

const railSectionStyle: React.CSSProperties = {
  background: 'rgba(255,255,255,0.72)',
  backdropFilter: 'blur(14px) saturate(125%)',
  WebkitBackdropFilter: 'blur(14px) saturate(125%)',
  border: '1px solid rgba(0,0,0,0.06)',
  borderRadius: 14,
  padding: 12,
  boxShadow: '0 4px 18px rgba(0,0,0,0.04)',
};

const railHeadStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.06em',
  color: 'var(--text-2)',
  marginBottom: 6,
};

const railHeadMutedStyle: React.CSSProperties = {
  fontWeight: 500,
  color: 'var(--text-4)',
  letterSpacing: '0.04em',
};


const mainStyle: React.CSSProperties = {
  // Bounded main column: chat card grows to fill, composer card
  // sits anchored to the bottom of the column. The maxHeight reserve
  // is bumped to 116px (was 96) so the column ends ~20px above the
  // viewport bottom, and the bottom padding doubles to 28 — net
  // effect is the composer card's bottom boundary clears the screen
  // edge with a comfortable margin instead of being clipped.
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
  paddingTop: 14,
  paddingLeft: 14,
  paddingRight: 14,
  paddingBottom: 28,
  minHeight: 0,
  maxHeight: 'calc(100vh - 116px)',
  overflow: 'hidden',
};

const conversationScrollStyle: React.CSSProperties = {
  background: 'rgba(255,255,255,0.78)',
  backdropFilter: 'blur(14px) saturate(125%)',
  WebkitBackdropFilter: 'blur(14px) saturate(125%)',
  border: '1px solid rgba(0,0,0,0.06)',
  borderRadius: 14,
  padding: 18,
  boxShadow: '0 4px 18px rgba(0,0,0,0.04)',
  maxHeight: 'calc(100vh - 320px)',
  overflowY: 'auto',
};

const forkedMarkerStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'center',
  fontSize: 11,
  color: 'var(--text-4)',
  marginBottom: 16,
  padding: '6px 12px',
  background: TEAL_BG,
  borderRadius: 999,
  width: 'fit-content',
  margin: '0 auto 16px',
  border: `1px solid ${TEAL_BORDER}`,
};

const chatListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
};

function bubbleRowStyle(isUser: boolean): React.CSSProperties {
  return {
    display: 'flex',
    flexDirection: 'column',
    alignItems: isUser ? 'flex-end' : 'flex-start',
    gap: 4,
  };
}

function bubbleHeadStyle(isUser: boolean): React.CSSProperties {
  return {
    fontSize: 10,
    color: 'var(--text-4)',
    letterSpacing: '0.04em',
    textTransform: 'uppercase',
    fontWeight: 600,
    paddingLeft: isUser ? 0 : 4,
    paddingRight: isUser ? 4 : 0,
  };
}

function bubbleStyle(role: 'user' | 'assistant' | 'tool' | 'system'): React.CSSProperties {
  const base: React.CSSProperties = {
    maxWidth: '80%',
    padding: '10px 14px',
    borderRadius: 12,
    fontSize: 13,
    lineHeight: 1.55,
    whiteSpace: 'pre-wrap',
    overflowWrap: 'anywhere',
  };
  if (role === 'user') {
    return { ...base, background: TEAL, color: '#fff', borderBottomRightRadius: 4 };
  }
  if (role === 'assistant') {
    return { ...base, background: '#fff', border: '1px solid rgba(0,0,0,0.08)', borderBottomLeftRadius: 4 };
  }
  if (role === 'tool') {
    return { ...base, background: 'rgba(0,0,0,0.04)', color: 'var(--text-2)', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 12 };
  }
  return { ...base, background: 'rgba(0,0,0,0.04)', color: 'var(--text-3)', fontStyle: 'italic' };
}

/* ── Terminal-mode styles (thread-detail-terminal.png) ─────────── */

const terminalShellStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  background: '#0a0e14',
  color: '#cdd6f4',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
  minHeight: 0,
  position: 'relative',
};

const tmuxTopBarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 2,
  background: '#1a1f29',
  padding: '4px 8px',
  fontSize: 11,
  borderBottom: '1px solid #0f1318',
};

function tmuxPaneStyle(state: 'active' | 'activeAlt' | 'inactive'): React.CSSProperties {
  if (state === 'active') {
    return {
      background: '#16a34a',
      color: '#0a0e14',
      padding: '2px 10px',
      fontWeight: 700,
    };
  }
  if (state === 'activeAlt') {
    return {
      background: '#22c55e',
      color: '#0a0e14',
      padding: '2px 10px',
      fontWeight: 700,
    };
  }
  return {
    background: 'transparent',
    color: '#94a3b8',
    padding: '2px 10px',
  };
}

const tmuxBrandStyle: React.CSSProperties = {
  background: '#0d9488',
  color: '#0a0e14',
  padding: '2px 8px',
  fontWeight: 700,
  marginRight: 4,
};

const tmuxSpacerStyle: React.CSSProperties = { flex: 1 };

const tmuxRightHintStyle: React.CSSProperties = {
  color: '#94a3b8',
  fontSize: 10,
  letterSpacing: '0.02em',
};

const tmuxStatusBarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 2,
  background: '#0d9488',
  padding: '4px 8px',
  fontSize: 10,
  borderTop: '1px solid #0f1318',
  color: '#0a0e14',
};

const tmuxStatNumStyle: React.CSSProperties = {
  color: '#0a0e14',
  background: 'rgba(10, 14, 20, 0.18)',
  padding: '1px 8px',
  marginLeft: 4,
  fontWeight: 600,
};

const terminalTitleStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  padding: '10px 14px',
  borderBottom: '1px solid rgba(255,255,255,0.04)',
};

const terminalThreadTitleStyle: React.CSSProperties = {
  color: '#f8fafc',
  fontWeight: 700,
  fontSize: 13,
};

const terminalBranchStyle: React.CSSProperties = {
  color: '#22c55e',
  background: 'rgba(34, 197, 94, 0.10)',
  padding: '1px 8px',
  borderRadius: 4,
  fontSize: 11,
};

const terminalScrollStyle: React.CSSProperties = {
  flex: 1,
  overflowY: 'auto',
  padding: '10px 14px 14px',
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  minHeight: 0,
};

const termInfoStyle: React.CSSProperties = {
  color: '#64748b',
  fontStyle: 'italic',
};

function termRowStyle(_m: ThreadMessageDto): React.CSSProperties {
  return {
    display: 'flex',
    gap: 8,
    lineHeight: 1.5,
    whiteSpace: 'pre-wrap',
    overflowWrap: 'anywhere',
  };
}

function termGlyphStyle(m: ThreadMessageDto): React.CSSProperties {
  let color = '#64748b';
  if (m.role === 'user') color = '#22c55e';
  else if (m.role === 'assistant') color = '#fb923c';
  else if (m.type === 'tool_call' || m.type === 'tool_result') color = '#60a5fa';
  else if (m.type === 'turn_done') color = '#22c55e';
  return {
    color,
    width: 14,
    flexShrink: 0,
    fontWeight: 700,
  };
}

function termContentStyle(m: ThreadMessageDto): React.CSSProperties {
  let color = '#cdd6f4';
  if (m.role === 'user') color = '#dcfce7';
  else if (m.role === 'assistant' && m.type === 'thinking') color = '#94a3b8';
  else if (m.role === 'assistant') color = '#fed7aa';
  else if (m.type === 'tool_call') color = '#bfdbfe';
  else if (m.type === 'tool_result') color = '#94a3b8';
  else if (m.type === 'turn_done') color = '#86efac';
  else if (m.type === 'error') color = '#fca5a5';
  return {
    color,
    flex: 1,
    minWidth: 0,
    fontStyle: m.type === 'thinking' ? 'italic' : 'normal',
  };
}

const termCwdHintStyle: React.CSSProperties = {
  position: 'absolute',
  bottom: 28,
  left: 14,
  color: '#475569',
  fontSize: 10,
  pointerEvents: 'none',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  maxWidth: '60%',
};

const vitalsListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  display: 'grid',
  gap: 4,
};

const contextRowsStyle: React.CSSProperties = {
  display: 'grid',
  gap: 4,
};

const vitalsRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'baseline',
  fontSize: 12,
  gap: 8,
};

const vitalsLabelStyle: React.CSSProperties = {
  margin: 0,
  color: 'var(--text-3)',
  textTransform: 'lowercase',
  letterSpacing: '0.02em',
};

const vitalsValueStyle: React.CSSProperties = {
  margin: 0,
  color: 'var(--text-1)',
};

const shipBtnStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 12px',
  fontSize: 13,
  border: 'none',
  background: 'linear-gradient(135deg, #0d9488, #0891b2)',
  color: '#fff',
  borderRadius: 8,
  fontWeight: 700,
  letterSpacing: '0.02em',
  cursor: 'pointer',
  boxShadow: '0 4px 12px rgba(13,148,136,0.20)',
};

const shipHintStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--text-4)',
  marginTop: 6,
  textAlign: 'center',
};

const composerStyle: React.CSSProperties = {
  position: 'sticky',
  bottom: 0,
  background: 'rgba(255,255,255,0.86)',
  backdropFilter: 'blur(14px) saturate(125%)',
  WebkitBackdropFilter: 'blur(14px) saturate(125%)',
  borderTop: '1px solid rgba(0,0,0,0.06)',
  padding: '8px 18px 12px',
  zIndex: 2,
};

const composerAnchorStyle: React.CSSProperties = {
  fontSize: 10,
  letterSpacing: '0.04em',
  color: TEAL,
  fontWeight: 600,
  marginBottom: 4,
};

const composerBranchStyle: React.CSSProperties = {
  color: 'var(--text-3)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontWeight: 500,
};

const composerInputStyle: React.CSSProperties = {
  width: '100%',
  padding: '10px 12px',
  border: `1px solid ${TEAL_BORDER}`,
  borderRadius: 10,
  background: 'rgba(255,255,255,0.86)',
  fontSize: 13,
  fontFamily: 'inherit',
  resize: 'vertical',
  outline: 'none',
};

const composerFooterStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  gap: 8,
  fontSize: 10,
  color: 'var(--text-4)',
  // Dedicated chrome strip — tinted background and a top divider so
  // the glyph row visually anchors to the bottom of the card and the
  // "Replying in Task n" line above the textarea has room to breathe.
  padding: '8px 14px 10px',
  background: 'rgba(248, 250, 252, 0.85)',
  borderTop: '1px solid rgba(0,0,0,0.06)',
};

const composerScopeStyle: React.CSSProperties = {
  padding: '1px 6px',
  background: TEAL_BG,
  borderRadius: 6,
  color: TEAL,
  fontWeight: 600,
  letterSpacing: '0.04em',
};

const composerFooterHintStyle: React.CSSProperties = {
  flex: 1,
  fontStyle: 'italic',
};

const sendBtnStyle: React.CSSProperties = {
  padding: '4px 14px',
  fontSize: 12,
  border: 'none',
  background: TEAL,
  color: '#fff',
  borderRadius: 6,
  cursor: 'pointer',
  fontWeight: 600,
};

/* ── Diff three-column styles ─────────────────────────────────────── */

const diffGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 280px 1.6fr',
  gap: 14,
  padding: '14px 18px',
  flex: 1,
  alignItems: 'start',
  minHeight: 'calc(100vh - 280px)',
};

const diffConvColStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
  maxHeight: 'calc(100vh - 280px)',
  overflow: 'hidden',
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
  maxHeight: 'calc(100vh - 280px)',
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
  maxHeight: 'calc(100vh - 280px)',
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

const loadingStyle: React.CSSProperties = {
  padding: 18,
  color: 'var(--text-3)',
  fontStyle: 'italic',
};

const emptyStyle: React.CSSProperties = {
  padding: '6px 2px',
  fontSize: 11,
  color: 'var(--text-3)',
  lineHeight: 1.5,
};

const floatErrStyle: React.CSSProperties = {
  position: 'fixed',
  bottom: 12,
  right: 12,
  padding: '8px 12px',
  background: '#fee2e2',
  border: '1px solid #fecaca',
  color: '#991b1b',
  fontSize: 12,
  borderRadius: 8,
  zIndex: 4,
};
