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
import type { ThreadDto, ThreadMessageDto, WorkUnitTaskDto } from '../types';
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

type Mode = 'conversation' | 'terminal';

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
  const { tasks } = useThreadTasks(threadId);
  const composerRef = useRef<HTMLTextAreaElement | null>(null);

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

  return (
    <div style={pageStyle}>
      <div style={meshBgStyle} aria-hidden />
      <div style={noiseBgStyle} aria-hidden />
      <div style={spineStyle} aria-hidden />

      <div style={contentColStyle}>
        <header style={headerStyle}>
          <button type="button" onClick={onBackToTrunk} style={backBtnStyle}>
            ↑ Thread
          </button>
          <span style={breadcrumbStyle}>
            <span style={crumbWorkspaceStyle}>Workspace</span>
            <span style={crumbSepStyle}>›</span>
            <span style={crumbThreadStyle}>{thread?.title ?? 'Thread'}</span>
            <span style={crumbSepStyle}>›</span>
            <span style={crumbTaskStyle}>{taskTitle}</span>
          </span>
          <ModeToggle mode={mode} onChange={setMode} />
        </header>

        <div style={altitudeBandStyle}>
          <span style={bandGlyphStyle}>● TASK{taskSeq !== null && ` ${taskSeq}`}</span>
          <span style={bandTitleStyle}>{taskTitle}</span>
          {taskBranch !== null && (
            <span style={bandBranchStyle}>⎇ {taskBranch}</span>
          )}
          {taskPr !== null && (
            <span style={bandPrStyle}>⊜ PR #{taskPr}</span>
          )}
          <div style={bandSpacerStyle} />
          <button
            type="button"
            style={diffBtnStyle}
            title="Open the three-column diff (built in a later phase)"
            disabled
          >
            ⇄ Diff
          </button>
        </div>

        <div style={bodyGridStyle}>
          <main style={mainStyle}>
            {mode === 'conversation' && (
              <ConversationView messages={messages} threadTitle={thread?.title ?? null} />
            )}
            {mode === 'terminal' && (
              <TerminalPlaceholder
                messages={messages}
                cwd={task?.workingDir ?? null}
                branch={taskBranch}
              />
            )}
          </main>

          <aside style={railStyle}>
            <section style={railSectionStyle}>
              <div style={railHeadStyle}>
                <span>COMMITS</span>
                <button
                  type="button"
                  style={railLinkBtnStyle}
                  disabled
                  title="View code diff — built in a later phase"
                >
                  ⇄ View diff
                </button>
              </div>
              <div style={emptyStyle}>
                Commit list lands with the three-column diff view.
              </div>
            </section>

            <section style={railSectionStyle}>
              <div style={railHeadStyle}>
                <span>TASK METRICS</span>
                <span style={railHeadMutedStyle}>this task</span>
              </div>
              <MetricsTable task={task} />
            </section>

            <section style={railSectionStyle}>
              <div style={railHeadStyle}>
                <span>CONTEXT</span>
              </div>
              <div style={contextRowsStyle}>
                <ContextRow label="Branch" value={taskBranch ?? '—'} mono />
                <ContextRow label="Base" value={task?.baseBranch ?? '—'} mono />
                <ContextRow label="Worktree" value={task?.worktreePath ?? '—'} mono truncate />
              </div>
            </section>

            <section style={railSectionStyle}>
              <div style={railHeadStyle}>
                <span>REWIND CHECKPOINTS</span>
              </div>
              <div style={emptyStyle}>
                Per-task rewind checkpoints land in a later phase.
              </div>
            </section>

            <section style={railSectionStyle}>
              <button
                type="button"
                onClick={() => { void onShip(); }}
                disabled={task === null || shipping}
                style={shipBtnStyle}
                title={task === null
                  ? 'No task loaded yet'
                  : `Ship Task ${task.seq} and return to the thread trunk`}
              >
                {shipping ? 'Shipping…' : `Ship Task ${task?.seq ?? ''}`.trim()}
              </button>
              <div style={shipHintStyle}>
                Ship finalises the task and returns to the trunk.
              </div>
            </section>
          </aside>
        </div>

        <footer style={composerStyle}>
          <div style={composerAnchorStyle}>
            ↻ Replying in → Task {taskSeq ?? ''} {taskBranch !== null && (
              <span style={composerBranchStyle}>· ⎇ {taskBranch}</span>
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
          <div style={composerFooterStyle}>
            <span style={composerScopeStyle}>● Task {taskSeq ?? ''}</span>
            <span style={composerFooterHintStyle}>
              ⌘↵ send · the trunk plans, this task does the work
            </span>
            <button
              type="button"
              onClick={() => { void onSend(); }}
              disabled={sending || input.trim().length === 0}
              style={sendBtnStyle}
            >
              {sending ? 'Sending…' : 'Send'}
            </button>
          </div>
        </footer>
      </div>

      {error !== null && (
        <div style={floatErrStyle}>{error}</div>
      )}
    </div>
  );
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

function TerminalPlaceholder({
  messages, cwd, branch,
}: {
  messages: ThreadMessageDto[] | null;
  cwd: string | null;
  branch: string | null;
}) {
  return (
    <div style={terminalStyle}>
      <div style={terminalBannerStyle}>
        $ task scrollback · {cwd ?? '—'} · {branch ?? '—'}
      </div>
      {messages === null ? (
        <div style={terminalLineStyle}>loading…</div>
      ) : messages.length === 0 ? (
        <div style={terminalLineStyle}>(no messages yet)</div>
      ) : (
        <pre style={terminalScrollStyle}>
          {messages.map(m => `[${m.role}/${m.type}] ${previewBody(m).slice(0, 200)}`).join('\n')}
        </pre>
      )}
      <div style={terminalNoteStyle}>
        Terminal-styled chrome ports onto this shell in a later polish
        pass; for now this is a faithful scrollback dump.
      </div>
    </div>
  );
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

const diffBtnStyle: React.CSSProperties = {
  padding: '4px 10px',
  fontSize: 12,
  border: `1px solid ${TEAL_BORDER}`,
  background: '#fff',
  color: TEAL,
  borderRadius: 6,
  fontWeight: 600,
  cursor: 'not-allowed',
  opacity: 0.7,
};

const bodyGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 280px',
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

const railLinkBtnStyle: React.CSSProperties = {
  fontSize: 10,
  padding: '2px 6px',
  border: `1px solid ${TEAL_BORDER}`,
  background: '#fff',
  color: TEAL,
  borderRadius: 6,
  cursor: 'not-allowed',
  fontWeight: 600,
  opacity: 0.7,
};

const mainStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
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

const terminalStyle: React.CSSProperties = {
  background: '#0a0e14',
  color: '#cdd6f4',
  borderRadius: 14,
  padding: 14,
  border: '1px solid rgba(0,0,0,0.18)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
  maxHeight: 'calc(100vh - 320px)',
  overflow: 'auto',
};

const terminalBannerStyle: React.CSSProperties = {
  color: '#94a3b8',
  paddingBottom: 8,
  borderBottom: '1px solid rgba(255,255,255,0.06)',
  marginBottom: 8,
};

const terminalLineStyle: React.CSSProperties = {
  color: '#cdd6f4',
};

const terminalScrollStyle: React.CSSProperties = {
  margin: 0,
  whiteSpace: 'pre-wrap',
  overflowWrap: 'anywhere',
};

const terminalNoteStyle: React.CSSProperties = {
  marginTop: 12,
  paddingTop: 8,
  borderTop: '1px solid rgba(255,255,255,0.06)',
  color: '#64748b',
  fontStyle: 'italic',
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
  marginTop: 6,
  fontSize: 10,
  color: 'var(--text-4)',
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
