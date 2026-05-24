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
  ThreadDto,
  ThreadMessageDto,
  ThreadSettingsDto,
  ThreadTurnDto,
  WorkUnitTaskDto,
} from '../types';
import { StructuredConversation } from './StructuredConversation';
import { useThreadTasks } from './useThreadTasks';

type Props = {
  threadId: string;
  onBack: () => void;
  /** Enter a specific task's window (Open → / double-click). Phase 3
   *  replaces the current ThreadDetailPage with the proper task-detail
   *  shell; until then this still routes to that page with the focused
   *  taskId so the user can pick up the agent conversation. */
  onOpenTask: (taskId: string) => void;
};

const ACTIVE_STATUSES = new Set([
  'PENDING', 'RUNNING', 'AWAITING', 'IDLE',
  'AWAITING_REVIEW', 'NEEDS_ATTENTION',
]);

/**
 * Trunk window for a Thread — the planning altitude. Owns no branch
 * and no diff; the conversation here is the cross-task plan, and the
 * left rail is the orchestration surface (tasks lane, Next/Ship,
 * vitals, scheduler). Per the workspace/thread/task design doc, this
 * is one of two configurations of the same shell — the other is the
 * task-detail window (Phase 3).
 *
 * <p>Identity trio (per the design's "made unmistakable at a glance"
 * rule): full-height slate spine, slate altitude band, and a
 * "Replying in the thread · planning" composer anchor.
 */
export default function ThreadTrunkPage({ threadId, onBack, onOpenTask }: Props) {
  const [thread, setThread] = useState<ThreadDto | null>(null);
  const [threadError, setThreadError] = useState<string | null>(null);
  const { tasks, error: tasksError, refresh: refreshTasks } = useThreadTasks(threadId);
  const [turns, setTurns] = useState<ThreadTurnDto[] | null>(null);
  const [messages, setMessages] = useState<ThreadMessageDto[] | null>(null);
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
  const [advancing, setAdvancing] = useState<'next' | 'ship' | null>(null);
  const [advanceError, setAdvanceError] = useState<string | null>(null);
  const [composerInput, setComposerInput] = useState('');
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);

  const loadThread = useCallback(async () => {
    try {
      const t = await window.bridge.getTask(threadId);
      setThread(t);
      setThreadError(null);
    }
    catch (e) {
      setThreadError(e instanceof Error ? e.message : String(e));
    }
  }, [threadId]);

  useEffect(() => {
    void loadThread();
  }, [loadThread]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.getTaskTurns(threadId);
        if (!cancelled) setTurns(list);
      }
      catch {
        if (!cancelled) setTurns([]);
      }
    })();
    return () => { cancelled = true; };
  }, [threadId]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.getTaskMessages(threadId);
        if (!cancelled) setMessages(list);
      }
      catch {
        if (!cancelled) setMessages([]);
      }
    })();
    return () => { cancelled = true; };
  }, [threadId]);

  // Trunk-planning slice of the transcript. {@code taskId === null}
  // is the documented marker for cross-task planning rows; per-task
  // rows belong to the task-detail window, not the trunk.
  const trunkMessages = useMemo(
    () => messages === null ? [] : messages.filter(m => m.taskId === null),
    [messages]);
  const orderedTasksAsc = useMemo(
    () => tasks === null ? [] : [...tasks].sort((a, b) => a.seq - b.seq),
    [tasks]);

  // Pre-select the foreground (newest non-terminal) task so Next/Ship
  // have a target without the user having to click first.
  useEffect(() => {
    if (tasks === null || selectedTaskId !== null) return;
    const foreground = newestActiveTask(tasks);
    if (foreground !== null) {
      setSelectedTaskId(foreground.id);
    }
  }, [tasks, selectedTaskId]);

  const orderedTasks = useMemo(
    () => tasks === null ? [] : [...tasks].sort((a, b) => b.seq - a.seq),
    [tasks]);
  const foreground = useMemo(
    () => tasks === null ? null : newestActiveTask(tasks), [tasks]);
  const parkedCount = useMemo(
    () => tasks === null ? 0 : tasks.filter(
      t => t.status === 'AWAITING_REVIEW' || t.status === 'NEEDS_ATTENTION').length,
    [tasks]);
  const needsAttention = useMemo(
    () => tasks === null ? [] : tasks.filter(t => t.status === 'NEEDS_ATTENTION'),
    [tasks]);
  const scheduler = useMemo(() => summariseScheduler(turns), [turns]);
  const isReviewFlow = thread?.flow === 'review';

  const refreshMessages = useCallback(async () => {
    try {
      const list = await window.bridge.getTaskMessages(threadId);
      setMessages(list);
    }
    catch { /* keep last good list */ }
  }, [threadId]);

  const onSendTrunk = useCallback(async () => {
    const text = composerInput.trim();
    if (text.length === 0 || sending) return;
    setSending(true);
    setSendError(null);
    try {
      await window.bridge.sendTrunkMessage(threadId, text);
      setComposerInput('');
      await refreshMessages();
    }
    catch (e) {
      setSendError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSending(false);
    }
  }, [composerInput, sending, threadId, refreshMessages]);

  const onAdvance = useCallback(async (mode: 'next' | 'ship') => {
    if (foreground === null || advancing !== null) return;
    const verb = mode === 'next' ? 'Park & start next' : 'Ship';
    const ok = window.confirm(
      `${verb}: task ${foreground.seq}`
      + (foreground.branchName !== null ? ` (${foreground.branchName})` : '')
      + (mode === 'next'
        ? ' — parked at AWAITING_REVIEW; new task cut from main.'
        : ' — closes the task and reaps the worktree.'));
    if (!ok) return;
    setAdvancing(mode);
    setAdvanceError(null);
    try {
      if (mode === 'next') {
        await window.bridge.parkAndStartNext(threadId, foreground.id);
      }
      else {
        await window.bridge.shipAndContinue(threadId, foreground.id);
      }
      await Promise.all([loadThread(), refreshTasks()]);
    }
    catch (e) {
      setAdvanceError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setAdvancing(null);
    }
  }, [foreground, advancing, threadId, loadThread, refreshTasks]);

  const title = thread?.title ?? 'Loading…';
  const taskCount = tasks?.length ?? 0;

  return (
    <div style={pageStyle}>
      <div style={meshBgStyle} aria-hidden />
      <div style={noiseBgStyle} aria-hidden />
      <div style={spineStyle} aria-hidden />

      <div style={contentColStyle}>
        <header style={headerStyle}>
          <button type="button" onClick={onBack} style={backBtnStyle}>← Threads</button>
          <span style={titleStyle}>{title}</span>
          {thread !== null && (
            <span style={statusBadgeStyle(thread.status)}>
              {thread.status}{taskCount > 0 && ` · ${taskCount} task${taskCount === 1 ? '' : 's'}`}
            </span>
          )}
        </header>

        <div style={altitudeBandStyle}>
          <span style={bandGlyphStyle}>◆ THREAD</span>
          <span style={bandTitleStyle}>{title}</span>
          <span style={bandHintStyle}>
            {thread?.flow === 'review'
              ? 'review flow · references a PR · multi-agent panel'
              : 'planning & orchestration · no branch · build flow'}
          </span>
          {thread !== null && (
            <span style={flowBadgeStyle(thread.flow)}>
              {thread.flow === 'review' ? 'REVIEW' : 'BUILD'}
            </span>
          )}
        </div>

        <div style={bodyGridStyle}>
          <aside style={railStyle}>
            {needsAttention.length > 0 && (
              <section style={attentionBannerStyle}>
                <div style={attentionTitleStyle}>
                  ⚠ {needsAttention.length} task{needsAttention.length === 1 ? '' : 's'} need{needsAttention.length === 1 ? 's' : ''} you
                </div>
                <ul style={attentionListStyle}>
                  {needsAttention.map(t => (
                    <li key={t.id} style={attentionRowStyle}>
                      <span style={attentionLabelStyle}>
                        {taskLabel(t)} · seq {t.seq}
                      </span>
                      <button
                        type="button"
                        onClick={() => onOpenTask(t.id)}
                        style={attentionJumpBtnStyle}
                        title="Jump into this task and take the lease"
                      >
                        Jump in →
                      </button>
                    </li>
                  ))}
                </ul>
                <div style={attentionHintStyle}>
                  Automation parked these at NEEDS_ATTENTION. Jump in to take
                  the lease from the headless worker.
                </div>
              </section>
            )}

            <section style={railSectionStyle}>
              <div style={railHeadStyle}>
                <span>TASKS IN THIS THREAD</span>
                {parkedCount > 0 && <span style={railHeadMutedStyle}>{parkedCount} parked</span>}
              </div>
              {tasksError !== null && (
                <div style={errStyle}>Could not load tasks: {tasksError}</div>
              )}
              {tasks !== null && tasks.length === 0 && (
                <div style={emptyStyle}>
                  No tasks yet — this thread is in brainstorm mode. The first
                  coding turn will materialise <em>Task 1</em>.
                </div>
              )}
              {orderedTasks.length > 0 && (
                <ul style={listStyle}>
                  {orderedTasks.map(t => (
                    <TaskCard
                      key={t.id}
                      task={t}
                      selected={t.id === selectedTaskId}
                      isForeground={foreground?.id === t.id}
                      onSelect={() => setSelectedTaskId(t.id)}
                      onOpen={() => onOpenTask(t.id)}
                    />
                  ))}
                </ul>
              )}
              {isReviewFlow ? (
                <div style={reviewFlowNoticeStyle}>
                  This is a review thread — the panel reviews a PR rather than
                  cutting branches. Next / Ship apply only to build flows.
                </div>
              ) : (
                <>
                  <div style={advanceRowStyle}>
                    <button
                      type="button"
                      onClick={() => { void onAdvance('next'); }}
                      disabled={foreground === null || advancing !== null}
                      style={nextBtnStyle}
                      title={foreground === null
                        ? 'No foreground task — Next needs a task to park'
                        : `Next: park task ${foreground.seq} at AWAITING_REVIEW and start the next from main`}
                    >
                      {advancing === 'next' ? 'Parking…' : 'Next →'}
                    </button>
                    <button
                      type="button"
                      onClick={() => { void onAdvance('ship'); }}
                      disabled={foreground === null || advancing !== null}
                      style={shipBtnStyle}
                      title={foreground === null
                        ? 'No foreground task — Ship needs a task to finalise'
                        : `Ship: finalise task ${foreground.seq} (worktree reaps)`}
                    >
                      {advancing === 'ship' ? 'Shipping…' : 'Ship'}
                    </button>
                  </div>
                  <div style={advanceHintStyle}>
                    Next parks &amp; starts next · Ship finalises this task
                  </div>
                </>
              )}
              {advanceError !== null && (
                <div style={errStyle}>{advanceError}</div>
              )}
            </section>

            <section style={railSectionStyle}>
              <div style={railHeadStyle}>
                <span>THREAD VITALS</span>
                <span style={railHeadMutedStyle}>aggregated</span>
              </div>
              <VitalsTable thread={thread} />
            </section>

            <section style={railSectionStyle}>
              <div style={railHeadStyle}>
                <span>SCHEDULER</span>
                <span style={railHeadMutedStyle}>fair-share</span>
              </div>
              <SchedulerTable summary={scheduler} />
            </section>

            <CheckpointsSection threadId={threadId} />

            <SettingsSection threadId={threadId} />
          </aside>

          <main style={mainStyle}>
            {messages === null ? (
              <div style={planningPlaceholderStyle}>
                <h2 style={planningTitleStyle}>Trunk planning</h2>
                <p style={planningBodyStyle}>Loading planning conversation…</p>
              </div>
            ) : trunkMessages.length === 0 ? (
              <div style={planningPlaceholderStyle}>
                <h2 style={planningTitleStyle}>Trunk planning</h2>
                <p style={planningBodyStyle}>
                  This is the thread's planning altitude — the map across all
                  tasks. The trunk owns no branch and no diff; talk here is
                  the cross-task plan, and each task forks from this
                  conversation at creation.
                </p>
                <p style={planningBodyStyle}>
                  {tasks === null
                    ? 'Loading tasks…'
                    : tasks.length === 0
                      ? 'No tasks yet. Open a task once one materialises, or start one from your next prompt.'
                      : foreground !== null
                        ? <>Foreground task: <strong>{taskLabel(foreground)}</strong> (seq {foreground.seq}). Use <kbd>Open →</kbd> on a card to enter its window.</>
                        : 'No foreground task — every task in this thread is parked or shipped.'}
                </p>
              </div>
            ) : (
              <div style={planningScrollStyle}>
                <StructuredConversation
                  messages={trunkMessages}
                  pendingPermission={null}
                  onDecide={noopDecide}
                  modelName={thread?.model ?? ''}
                  tasks={orderedTasksAsc}
                />
              </div>
            )}
          </main>
        </div>

        <footer style={composerStyle}>
          <div style={composerAnchorStyle}>
            ↻ Replying in the thread · planning
          </div>
          <textarea
            value={composerInput}
            onChange={e => setComposerInput(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter' && (e.metaKey || e.ctrlKey) && !sending) {
                e.preventDefault();
                void onSendTrunk();
              }
            }}
            placeholder="Plan the next slice, ask about the feature, or start a new task…"
            disabled={sending}
            rows={3}
            style={composerTextareaStyle}
          />
          <div style={composerFooterStyle}>
            <span style={composerScopeStyle}>▸ Thread</span>
            <span style={composerFooterHintStyle}>
              ⌘↵ send · no branch here — the trunk plans; tasks do the work
            </span>
            <button
              type="button"
              onClick={() => { void onSendTrunk(); }}
              disabled={sending || composerInput.trim().length === 0}
              style={composerSendBtnStyle}
            >
              {sending ? 'Sending…' : 'Send'}
            </button>
          </div>
          {sendError !== null && (
            <div style={errStyle}>{sendError}</div>
          )}
        </footer>
      </div>

      {threadError !== null && (
        <div style={floatErrStyle}>Could not load thread: {threadError}</div>
      )}
    </div>
  );
}

function CheckpointsSection({ threadId }: { threadId: string }) {
  const [checkpoints, setCheckpoints] = useState<
    import('../types').ThreadCheckpointDto[] | null
  >(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.getTaskCheckpoints(threadId);
        if (!cancelled) setCheckpoints(list);
      }
      catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
  }, [threadId]);

  // Overall + the most-recent segments, capped to keep the rail tight.
  const overall = checkpoints?.find(c => c.isOverall) ?? null;
  const segments = (checkpoints ?? [])
    .filter(c => !c.isOverall && c.supersededAt === null)
    .sort((a, b) => b.seq - a.seq)
    .slice(0, 4);

  return (
    <section style={railSectionStyle}>
      <div style={railHeadStyle}>
        <span>CHECKPOINTS</span>
        <span style={railHeadMutedStyle}>
          {checkpoints === null
            ? '…'
            : `thread × ${(overall ? 1 : 0) + segments.length}`}
        </span>
      </div>
      {error !== null && <div style={errStyle}>{error}</div>}
      {checkpoints !== null && checkpoints.length === 0 && (
        <div style={emptyStyle}>
          No checkpoints yet. The summariser writes one once enough tokens
          have accumulated in the conversation.
        </div>
      )}
      {(overall !== null || segments.length > 0) && (
        <ul style={checkpointListStyle}>
          {overall !== null && (
            <li style={checkpointRowStyle}>
              <span style={{ ...checkpointDotStyle, background: SLATE }} aria-hidden />
              <div style={checkpointBodyStyle}>
                <div style={checkpointTitleStyle}>Thread summary</div>
                <div style={checkpointBlurbStyle} title={overall.summaryMd}>
                  {previewBlurb(overall.summaryMd)}
                </div>
              </div>
            </li>
          )}
          {segments.map(seg => (
            <li key={seg.id} style={checkpointRowStyle}>
              <span style={{ ...checkpointDotStyle, background: '#7c3aed' }} aria-hidden />
              <div style={checkpointBodyStyle}>
                <div style={checkpointTitleStyle}>cp · seq {seg.seq}</div>
                <div style={checkpointBlurbStyle} title={seg.summaryMd}>
                  {previewBlurb(seg.summaryMd)}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function previewBlurb(md: string): string {
  // Take the first non-empty line, trim markdown bullets, cap at 140.
  const line = md.split('\n').map(l => l.trim()).find(l => l.length > 0) ?? '';
  const stripped = line.replace(/^[-*•]\s*/, '').replace(/^#+\s*/, '');
  return stripped.length > 140 ? stripped.slice(0, 137) + '…' : stripped;
}

function SettingsSection({ threadId }: { threadId: string }) {
  const [settings, setSettings] = useState<ThreadSettingsDto | null>(null);
  const [editing, setEditing] = useState(false);
  const [maxRunning, setMaxRunning] = useState<string>('');
  const [softCost, setSoftCost] = useState<string>('');
  const [hardCost, setHardCost] = useState<string>('');
  const [promptAddendum, setPromptAddendum] = useState<string>('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const s = await window.bridge.getThreadSettings(threadId);
      setSettings(s);
      if (s.overriddenAt === null) {
        setMaxRunning('');
        setSoftCost('');
        setHardCost('');
        setPromptAddendum('');
      }
      else {
        setMaxRunning(String(s.maxRunningTasks));
        setSoftCost(String(s.softCostUsdMilli));
        setHardCost(String(s.hardCostUsdMilli));
        setPromptAddendum(s.promptAddendum ?? '');
      }
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [threadId]);

  useEffect(() => { void refresh(); }, [refresh]);

  const onSave = useCallback(async () => {
    setSaving(true);
    setError(null);
    try {
      await window.bridge.putThreadSettings(threadId, {
        maxRunningTasks: maxRunning.trim() === '' ? null : Number(maxRunning),
        softCostUsdMilli: softCost.trim() === '' ? null : Number(softCost),
        hardCostUsdMilli: hardCost.trim() === '' ? null : Number(hardCost),
        promptAddendum: promptAddendum.trim() === '' ? null : promptAddendum,
      });
      await refresh();
      setEditing(false);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSaving(false);
    }
  }, [threadId, maxRunning, softCost, hardCost, promptAddendum, refresh]);

  const onReset = useCallback(async () => {
    setSaving(true);
    setError(null);
    try {
      await window.bridge.clearThreadSettings(threadId);
      await refresh();
      setEditing(false);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSaving(false);
    }
  }, [threadId, refresh]);

  return (
    <section style={railSectionStyle}>
      <div style={railHeadStyle}>
        <span>THREAD SETTINGS</span>
        <span style={railHeadMutedStyle}>
          {settings === null ? '…' : settings.overriddenAt === null ? 'inherited' : 'overridden'}
        </span>
      </div>
      {!editing && settings !== null && (
        <>
          <dl style={{ margin: 0, padding: 0, display: 'grid', gap: 4 }}>
            <SettingsRow label="Max running tasks" value={String(settings.maxRunningTasks)} />
            <SettingsRow label="Soft cost cap" value={`$${(settings.softCostUsdMilli / 1000).toFixed(2)}`} />
            <SettingsRow label="Hard cost cap" value={`$${(settings.hardCostUsdMilli / 1000).toFixed(2)}`} />
          </dl>
          {settings.promptAddendum !== null && (
            <div style={addendumPreviewStyle} title={settings.promptAddendum}>
              prompt: {settings.promptAddendum.length > 40
                ? settings.promptAddendum.slice(0, 40) + '…'
                : settings.promptAddendum}
            </div>
          )}
          <button
            type="button"
            onClick={() => setEditing(true)}
            style={settingsEditBtnStyle}
          >
            Edit
          </button>
        </>
      )}
      {editing && (
        <div style={settingsFormStyle}>
          <label style={settingsLabelStyle}>
            Max running tasks
            <input
              type="number"
              min={1}
              value={maxRunning}
              onChange={e => setMaxRunning(e.target.value)}
              placeholder="inherit"
              style={settingsInputStyle}
            />
          </label>
          <label style={settingsLabelStyle}>
            Soft cost cap (milli-USD)
            <input
              type="number"
              min={0}
              value={softCost}
              onChange={e => setSoftCost(e.target.value)}
              placeholder="inherit"
              style={settingsInputStyle}
            />
          </label>
          <label style={settingsLabelStyle}>
            Hard cost cap (milli-USD)
            <input
              type="number"
              min={0}
              value={hardCost}
              onChange={e => setHardCost(e.target.value)}
              placeholder="inherit"
              style={settingsInputStyle}
            />
          </label>
          <label style={settingsLabelStyle}>
            Prompt addendum
            <textarea
              value={promptAddendum}
              onChange={e => setPromptAddendum(e.target.value)}
              placeholder="appended onto workspace memory"
              rows={3}
              style={{ ...settingsInputStyle, fontFamily: 'inherit' }}
            />
          </label>
          <div style={settingsBtnRowStyle}>
            <button
              type="button"
              onClick={() => { void onSave(); }}
              disabled={saving}
              style={settingsSaveBtnStyle}
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
            <button
              type="button"
              onClick={() => setEditing(false)}
              disabled={saving}
              style={settingsCancelBtnStyle}
            >
              Cancel
            </button>
            {settings?.overriddenAt !== null && (
              <button
                type="button"
                onClick={() => { void onReset(); }}
                disabled={saving}
                style={settingsResetBtnStyle}
                title="Clear overrides and revert to inheritance"
              >
                Reset
              </button>
            )}
          </div>
        </div>
      )}
      {error !== null && (
        <div style={errStyle}>{error}</div>
      )}
    </section>
  );
}

function SettingsRow({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
      <dt style={{ margin: 0, color: 'var(--text-3)' }}>{label}</dt>
      <dd style={{ margin: 0, color: 'var(--text-1)', fontVariantNumeric: 'tabular-nums' }}>
        {value}
      </dd>
    </div>
  );
}

const addendumPreviewStyle: React.CSSProperties = {
  marginTop: 6,
  padding: '4px 8px',
  background: 'rgba(0,0,0,0.04)',
  borderRadius: 6,
  fontSize: 10,
  color: 'var(--text-3)',
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
};

const settingsEditBtnStyle: React.CSSProperties = {
  marginTop: 8,
  width: '100%',
  padding: '5px 8px',
  fontSize: 11,
  border: '1px dashed rgba(0,0,0,0.12)',
  background: 'transparent',
  borderRadius: 6,
  color: 'var(--text-2)',
  cursor: 'pointer',
};

const settingsFormStyle: React.CSSProperties = {
  display: 'grid',
  gap: 8,
};

const settingsLabelStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  fontSize: 10,
  color: 'var(--text-3)',
  fontWeight: 600,
  letterSpacing: '0.04em',
};

const settingsInputStyle: React.CSSProperties = {
  padding: '4px 6px',
  fontSize: 12,
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 6,
  background: '#fff',
  outline: 'none',
};

const settingsBtnRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 6,
  marginTop: 4,
};

const settingsSaveBtnStyle: React.CSSProperties = {
  flex: 1,
  padding: '5px 8px',
  fontSize: 11,
  border: 'none',
  background: 'linear-gradient(135deg, #7c3aed, #6366f1)',
  color: '#fff',
  borderRadius: 6,
  cursor: 'pointer',
  fontWeight: 600,
};

const settingsCancelBtnStyle: React.CSSProperties = {
  flex: 1,
  padding: '5px 8px',
  fontSize: 11,
  border: '1px solid rgba(0,0,0,0.10)',
  background: '#fff',
  color: 'var(--text-2)',
  borderRadius: 6,
  cursor: 'pointer',
};

const settingsResetBtnStyle: React.CSSProperties = {
  padding: '5px 8px',
  fontSize: 11,
  border: '1px solid rgba(220, 38, 38, 0.30)',
  background: '#fff',
  color: '#dc2626',
  borderRadius: 6,
  cursor: 'pointer',
};

function newestActiveTask(tasks: WorkUnitTaskDto[]): WorkUnitTaskDto | null {
  return tasks
    .filter(t => ACTIVE_STATUSES.has(t.status))
    .reduce<WorkUnitTaskDto | null>(
      (acc, t) => acc === null || t.seq > acc.seq ? t : acc, null);
}

function summariseScheduler(turns: ThreadTurnDto[] | null) {
  if (turns === null) return { running: 0, queued: 0, cli: 0, api: 0 };
  let running = 0;
  let queued = 0;
  let cli = 0;
  let api = 0;
  for (const t of turns) {
    if (t.status === 'RUNNING') {
      running++;
      if (t.lane === 'CLI') cli++; else api++;
    }
    else if (t.status === 'QUEUED') {
      queued++;
    }
  }
  return { running, queued, cli, api };
}

function TaskCard({
  task, selected, isForeground, onSelect, onOpen,
}: {
  task: WorkUnitTaskDto;
  selected: boolean;
  isForeground: boolean;
  onSelect: () => void;
  onOpen: () => void;
}) {
  const labelText = taskLabel(task);
  return (
    <li
      onClick={onSelect}
      onDoubleClick={onOpen}
      style={taskCardStyle(selected, isForeground)}
    >
      <div style={taskCardHeadStyle}>
        <span style={glyphStyle(task)} aria-hidden>{glyphChar(task)}</span>
        <span style={taskCardTitleStyle}>{labelText}</span>
        <button
          type="button"
          onClick={(e) => { e.stopPropagation(); onOpen(); }}
          style={openBtnStyle(selected)}
          title={`Enter Task ${task.seq}'s window`}
        >
          Open →
        </button>
      </div>
      <div style={taskCardMetaStyle}>
        {task.branchName !== null && (
          <span style={branchStyle}>{task.branchName}</span>
        )}
        {task.prNumber !== null && (
          <span style={prStyle}>PR #{task.prNumber}</span>
        )}
        <span style={taskStatusPillStyle(task.status)}>{statusLabel(task.status)}</span>
      </div>
    </li>
  );
}

function VitalsTable({ thread }: { thread: ThreadDto | null }) {
  if (thread === null) {
    return <div style={emptyStyle}>—</div>;
  }
  const cost = formatCost(thread.costUsdMilli);
  const tokens = `${formatTokens(thread.tokensIn)} → ${formatTokens(thread.tokensOut)}`;
  const runtime = formatRuntime(thread.createdAt, thread.endedAt);
  return (
    <dl style={vitalsListStyle}>
      <VitalRow label="Cost (all tasks)" value={cost} />
      <VitalRow label="Tokens" value={tokens} />
      <VitalRow label="Runtime" value={runtime} />
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

function SchedulerTable({
  summary,
}: {
  summary: { running: number; queued: number; cli: number; api: number };
}) {
  return (
    <dl style={vitalsListStyle}>
      <VitalRow label="Running" value={String(summary.running)} />
      <VitalRow label="Queued" value={String(summary.queued)} />
      <VitalRow label="CLI lane" value={String(summary.cli)} />
      <VitalRow label="API lane" value={String(summary.api)} />
    </dl>
  );
}

function taskLabel(task: WorkUnitTaskDto): string {
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

function glyphChar(task: WorkUnitTaskDto): string {
  if (task.status === 'COMPLETED') return '✓';
  if (task.status === 'ERRORED') return '⨯';
  if (task.status === 'AWAITING_REVIEW' || task.status === 'NEEDS_ATTENTION') return '⏸';
  if (task.status === 'RUNNING' || task.status === 'AWAITING') return '●';
  return '○';
}

function statusLabel(status: string): string {
  if (status === 'AWAITING_REVIEW') return 'awaiting';
  if (status === 'NEEDS_ATTENTION') return 'needs you';
  return status.toLowerCase();
}

function formatCost(milli: number): string {
  return `$${(milli / 1000).toFixed(2)}`;
}

function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

function formatRuntime(createdAt: string, endedAt: string | null): string {
  const start = Date.parse(createdAt);
  const end = endedAt !== null ? Date.parse(endedAt) : Date.now();
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return '—';
  const secs = Math.floor((end - start) / 1000);
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  const s = secs % 60;
  if (h > 0) return `${h}h ${m}m ${s}s`;
  if (m > 0) return `${m}m ${s.toString().padStart(2, '0')}s`;
  return `${s}s`;
}

/* ── Styles ────────────────────────────────────────────────────────── */

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
    'radial-gradient(circle at 18% 16%, rgba(124, 58, 237, 0.10), transparent 45%)',
    'radial-gradient(circle at 82% 22%, rgba(56, 189, 248, 0.10), transparent 45%)',
    'radial-gradient(circle at 12% 86%, rgba(244, 114, 182, 0.08), transparent 50%)',
    'radial-gradient(circle at 86% 78%, rgba(74, 222, 128, 0.08), transparent 50%)',
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

const SLATE = '#475569';
const SLATE_BG = 'rgba(71, 85, 105, 0.10)';
const SLATE_BORDER = 'rgba(71, 85, 105, 0.30)';

const spineStyle: React.CSSProperties = {
  position: 'fixed',
  top: 0,
  bottom: 0,
  left: 0,
  width: 4,
  background: SLATE,
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

const titleStyle: React.CSSProperties = {
  fontSize: 14,
  fontWeight: 600,
  letterSpacing: '0.005em',
  flex: 1,
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
};

function statusBadgeStyle(status: string): React.CSSProperties {
  const tone = status === 'RUNNING' ? '#16a34a' : status === 'ERRORED' ? '#b91c1c' : '#475569';
  return {
    fontSize: 10,
    padding: '2px 8px',
    borderRadius: 999,
    border: `1px solid ${tone}55`,
    color: tone,
    background: `${tone}10`,
    fontWeight: 700,
    letterSpacing: '0.04em',
  };
}

const altitudeBandStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 14,
  padding: '8px 18px',
  background: SLATE_BG,
  borderBottom: `1px solid ${SLATE_BORDER}`,
  fontSize: 12,
};

const bandGlyphStyle: React.CSSProperties = {
  fontWeight: 700,
  letterSpacing: '0.08em',
  color: SLATE,
};

const bandTitleStyle: React.CSSProperties = {
  fontWeight: 600,
  color: 'var(--text-1)',
};

const bandHintStyle: React.CSSProperties = {
  color: 'var(--text-3)',
};

const bodyGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '260px 1fr',
  gap: 14,
  padding: '14px 18px',
  flex: 1,
  alignItems: 'start',
};

const railStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
  position: 'sticky',
  top: 72,
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
  alignItems: 'baseline',
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

const listStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
};

function taskCardStyle(selected: boolean, isForeground: boolean): React.CSSProperties {
  return {
    padding: '8px 10px',
    border: selected ? '2px solid #7c3aed' : '1px solid rgba(0,0,0,0.08)',
    background: selected ? 'rgba(124,58,237,0.06)' : isForeground ? 'rgba(22,163,74,0.04)' : '#fff',
    borderRadius: 10,
    cursor: 'pointer',
    transition: 'background 140ms ease, transform 140ms ease',
    boxShadow: selected ? '0 2px 8px rgba(124,58,237,0.10)' : undefined,
  };
}

const taskCardHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
};

function glyphStyle(task: WorkUnitTaskDto): React.CSSProperties {
  const base: React.CSSProperties = {
    width: 14,
    fontSize: 12,
    textAlign: 'center',
  };
  if (task.status === 'COMPLETED') return { ...base, color: '#16a34a' };
  if (task.status === 'ERRORED') return { ...base, color: '#b91c1c' };
  if (task.status === 'AWAITING_REVIEW' || task.status === 'NEEDS_ATTENTION') return { ...base, color: '#d97706' };
  if (task.status === 'RUNNING' || task.status === 'AWAITING') return { ...base, color: '#2563eb' };
  return { ...base, color: 'var(--text-4)' };
}

const taskCardTitleStyle: React.CSSProperties = {
  flex: 1,
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

function openBtnStyle(selected: boolean): React.CSSProperties {
  return {
    fontSize: 10,
    padding: '2px 6px',
    border: '1px solid ' + (selected ? '#7c3aed55' : 'rgba(0,0,0,0.10)'),
    background: selected ? 'rgba(124,58,237,0.10)' : '#fff',
    color: selected ? '#6d28d9' : 'var(--text-2)',
    borderRadius: 6,
    cursor: 'pointer',
    fontWeight: 600,
  };
}

const taskCardMetaStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  marginTop: 4,
  flexWrap: 'wrap',
};

const branchStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--text-4)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  maxWidth: '60%',
};

const prStyle: React.CSSProperties = {
  fontSize: 10,
  padding: '1px 6px',
  background: 'rgba(0, 0, 0, 0.06)',
  borderRadius: 999,
  color: 'var(--text-2)',
};

function taskStatusPillStyle(status: string): React.CSSProperties {
  let bg = 'rgba(0,0,0,0.06)';
  let color = 'var(--text-2)';
  if (status === 'COMPLETED') { bg = 'rgba(22,163,74,0.12)'; color = '#15803d'; }
  else if (status === 'ERRORED') { bg = 'rgba(185,28,28,0.12)'; color = '#991b1b'; }
  else if (status === 'AWAITING_REVIEW' || status === 'NEEDS_ATTENTION') {
    bg = 'rgba(217,119,6,0.14)'; color = '#9a3412';
  }
  else if (status === 'RUNNING') { bg = 'rgba(37,99,235,0.12)'; color = '#1d4ed8'; }
  return {
    fontSize: 9,
    padding: '1px 6px',
    borderRadius: 999,
    background: bg,
    color,
    fontWeight: 700,
    letterSpacing: '0.04em',
    textTransform: 'lowercase',
  };
}

function flowBadgeStyle(flow: 'build' | 'review'): React.CSSProperties {
  const isReview = flow === 'review';
  return {
    marginLeft: 'auto',
    fontSize: 10,
    padding: '2px 8px',
    borderRadius: 999,
    fontWeight: 700,
    letterSpacing: '0.06em',
    background: isReview ? 'rgba(37, 99, 235, 0.10)' : SLATE_BG,
    color: isReview ? '#1d4ed8' : SLATE,
    border: `1px solid ${isReview ? 'rgba(37,99,235,0.30)' : SLATE_BORDER}`,
  };
}

const reviewFlowNoticeStyle: React.CSSProperties = {
  marginTop: 10,
  padding: 10,
  fontSize: 11,
  color: '#1d4ed8',
  background: 'rgba(37, 99, 235, 0.06)',
  border: '1px solid rgba(37,99,235,0.20)',
  borderRadius: 8,
  lineHeight: 1.5,
};

const attentionBannerStyle: React.CSSProperties = {
  background: 'rgba(217, 119, 6, 0.10)',
  border: '1px solid rgba(217, 119, 6, 0.30)',
  borderRadius: 14,
  padding: 12,
  boxShadow: '0 4px 18px rgba(217,119,6,0.10)',
};

const attentionTitleStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 700,
  color: '#9a3412',
  marginBottom: 8,
};

const attentionListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
};

const attentionRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  fontSize: 11,
};

const attentionLabelStyle: React.CSSProperties = {
  flex: 1,
  color: '#9a3412',
  fontWeight: 600,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const attentionJumpBtnStyle: React.CSSProperties = {
  padding: '4px 10px',
  fontSize: 10,
  border: 'none',
  background: '#d97706',
  color: '#fff',
  borderRadius: 6,
  cursor: 'pointer',
  fontWeight: 700,
  letterSpacing: '0.04em',
};

const attentionHintStyle: React.CSSProperties = {
  marginTop: 8,
  fontSize: 10,
  color: '#9a3412',
  lineHeight: 1.5,
};

const advanceRowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: 6,
  marginTop: 10,
};

const nextBtnStyle: React.CSSProperties = {
  padding: '6px 8px',
  fontSize: 12,
  border: 'none',
  background: 'linear-gradient(135deg, #7c3aed, #6366f1)',
  color: '#fff',
  borderRadius: 8,
  fontWeight: 600,
  cursor: 'pointer',
};

const shipBtnStyle: React.CSSProperties = {
  padding: '6px 8px',
  fontSize: 12,
  border: '1px solid rgba(0,0,0,0.10)',
  background: '#fff',
  color: 'var(--text-1)',
  borderRadius: 8,
  fontWeight: 600,
  cursor: 'pointer',
};

const advanceHintStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--text-4)',
  marginTop: 6,
  textAlign: 'center',
};

const vitalsListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  display: 'grid',
  gap: 4,
};

const vitalsRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'baseline',
  fontSize: 12,
};

const vitalsLabelStyle: React.CSSProperties = {
  margin: 0,
  color: 'var(--text-3)',
};

const vitalsValueStyle: React.CSSProperties = {
  margin: 0,
  color: 'var(--text-1)',
  fontFeatureSettings: '"tnum"',
  fontVariantNumeric: 'tabular-nums',
};

const mainStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
};

const planningPlaceholderStyle: React.CSSProperties = {
  background: 'rgba(255,255,255,0.72)',
  backdropFilter: 'blur(14px) saturate(125%)',
  WebkitBackdropFilter: 'blur(14px) saturate(125%)',
  border: '1px solid rgba(0,0,0,0.06)',
  borderRadius: 14,
  padding: 18,
  boxShadow: '0 4px 18px rgba(0,0,0,0.04)',
  minHeight: 200,
};

// The transcript scroll container. StructuredConversation walks up to
// find the nearest scrolling ancestor for its stick-to-bottom logic, so
// this wrapper supplies overflow:auto and a bounded max-height anchored
// to the viewport. The composer is sticky at the page bottom and the
// header/altitude band sit at the top, so this max-height keeps the
// chat from pushing the composer off-screen on long histories.
const planningScrollStyle: React.CSSProperties = {
  background: 'rgba(255,255,255,0.72)',
  backdropFilter: 'blur(14px) saturate(125%)',
  WebkitBackdropFilter: 'blur(14px) saturate(125%)',
  border: '1px solid rgba(0,0,0,0.06)',
  borderRadius: 14,
  padding: '12px 14px',
  boxShadow: '0 4px 18px rgba(0,0,0,0.04)',
  maxHeight: 'calc(100vh - 240px)',
  overflow: 'auto',
};

const noopDecide = () => { /* trunk view is read-only in this phase */ };

const checkpointListStyle: React.CSSProperties = {
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
  marginTop: 6,
  flexShrink: 0,
};

const checkpointBodyStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
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

const planningTitleStyle: React.CSSProperties = {
  margin: '0 0 8px',
  fontSize: 14,
  fontWeight: 700,
  letterSpacing: '0.02em',
  color: SLATE,
};

const planningBodyStyle: React.CSSProperties = {
  margin: '8px 0',
  fontSize: 13,
  lineHeight: 1.55,
  color: 'var(--text-2)',
};

const composerStyle: React.CSSProperties = {
  // Pinned directly to the viewport bottom. {@code position:sticky}
  // anchored against the column's bottom edge, and when the column
  // grew taller than 100vh the composer slid past the viewport and
  // got clipped by the page's overflow:hidden. {@code fixed} sidesteps
  // that — the composer always sits at the visible bottom regardless
  // of how tall the rail or transcript above it gets. {@code left:4}
  // clears the 4px slate spine that runs the full window height.
  position: 'fixed',
  left: 4,
  right: 0,
  bottom: 40,
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
  color: SLATE,
  fontWeight: 600,
  marginBottom: 4,
};

const composerTextareaStyle: React.CSSProperties = {
  width: '100%',
  padding: '10px 12px',
  border: `1px solid ${SLATE_BORDER}`,
  borderRadius: 10,
  background: 'rgba(255,255,255,0.86)',
  fontSize: 13,
  fontFamily: 'inherit',
  color: 'var(--text-1)',
  resize: 'vertical',
  outline: 'none',
  boxSizing: 'border-box',
};

const composerSendBtnStyle: React.CSSProperties = {
  padding: '4px 14px',
  fontSize: 12,
  border: 'none',
  background: SLATE,
  color: '#fff',
  borderRadius: 6,
  cursor: 'pointer',
  fontWeight: 600,
};

const composerFooterStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  marginTop: 4,
  fontSize: 10,
  color: 'var(--text-4)',
};

const composerScopeStyle: React.CSSProperties = {
  padding: '1px 6px',
  background: SLATE_BG,
  borderRadius: 6,
  color: SLATE,
  fontWeight: 600,
  letterSpacing: '0.04em',
};

const composerFooterHintStyle: React.CSSProperties = {
  fontStyle: 'italic',
};

const emptyStyle: React.CSSProperties = {
  padding: '6px 2px',
  fontSize: 11,
  color: 'var(--text-3)',
  lineHeight: 1.5,
};

const errStyle: React.CSSProperties = {
  padding: '6px 8px',
  marginTop: 6,
  fontSize: 11,
  color: '#b91c1c',
  background: 'rgba(185, 28, 28, 0.06)',
  border: '1px solid rgba(185,28,28,0.18)',
  borderRadius: 6,
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
