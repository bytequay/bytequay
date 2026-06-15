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
import { useState } from 'react';
import type { ThreadDto, WorkUnitTaskDto } from '../types';
import { FLOW_STEPPER_NODES, isSlotOccupying, phaseLabel } from './taskPhase';

/**
 * Task-detail page in the QUEUED state — the task row exists and its
 * agent session pre-warms while it waits for a compute slot. Distinct
 * full-page layout from a running task (slate identity, all-future
 * FlowStepper with a ⏳ pre-node, a dashed opening-prompt preview, and
 * an amber composer that writes to the opening prompt rather than the
 * conversation). Mirrors docs/mockups/v2/tasks/_src/task-detail-queued.html
 * — faithful to layout and hierarchy, not pixel-perfect.
 */
export function QueuedTaskView(props: {
  threadId: string;
  task: WorkUnitTaskDto;
  thread: ThreadDto | null;
  siblingTasks: WorkUnitTaskDto[];
  onBackToTrunk: () => void;
  onChanged: () => void;
}): React.ReactElement {
  const { threadId, task, thread, siblingTasks, onBackToTrunk, onChanged } = props;
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const taskName = task.name ?? `Task ${task.seq}`;
  const parallelSlots = thread?.parallelSlots ?? 1;
  const slotHolder = siblingTasks.find((t) => t.id !== task.id && isSlotOccupying(t.phase)) ?? null;
  const holderName = slotHolder ? (slotHolder.name ?? `Task ${slotHolder.seq}`) : null;
  const slotsInUse = slotHolder ? 1 : 0;
  const liveQueue = (thread?.queue ?? [])
    .filter((q) => q.status === 'PENDING' || q.status === 'MATERIALIZED');
  const myEntry = (thread?.queue ?? []).find((q) => q.materializedTaskId === task.id) ?? null;
  const myPosition = myEntry?.position ?? null;

  async function setAsOpening(): Promise<void> {
    const text = input.trim();
    if (text === '' || busy) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await window.bridge.setOpeningPrompt(threadId, task.id, text, 'append');
      setInput('');
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const ctxHolder = slotHolder
    ? `slot ${slotsInUse}/${parallelSlots} in use by ${holderName}`
      + (slotHolder ? ` (${phaseLabel(slotHolder.phase)})` : '')
      + ' · will auto-start when slot frees'
    : `slot ${slotsInUse}/${parallelSlots} free · starting shortly`;

  return (
    <div style={PAGE}>
      <div style={SPINE} aria-hidden />
      <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
        {/* top bar */}
        <header style={TOP}>
          <button type="button" style={BACK} onClick={onBackToTrunk}>
            ← {thread?.title ?? 'Thread'}
          </button>
          <span style={{ flex: 1 }} />
          <span style={PHASE_CHIP} title="phase QUEUED · group Idle">
            <span style={PHASE_GLYPH} />QUEUED <span style={PHASE_SUB}>· Idle</span>
          </span>
        </header>

        {/* altitude bar */}
        <div style={ALTBAR}>
          <span style={ALT_BADGE}>◇ TASK {task.seq}</span>
          <span style={ALT_TITLE}>{taskName}</span>
          <span style={ALT_META}>
            ⏳ waiting · cut off {myEntry?.branchBase === 'STACKED_ON_PREVIOUS' ? 'previous' : 'main'}
            {task.branchName ? <> · branch will be <code style={CODE}>{task.branchName}</code></> : null}
          </span>
        </div>

        {/* FlowStepper — all future, with a ⏳ pre-node */}
        <div style={FLOW_BAND}>
          <div style={STEPPER}>
            <span style={PRE_NODE} title="QUEUED — waiting for compute slot">⏳</span>
            {FLOW_STEPPER_NODES.map((label, i) => (
              <FlowNode key={label} label={label} last={i === FLOW_STEPPER_NODES.length - 1} />
            ))}
          </div>
          <div style={CTX_LINE}><b>⏳ Queued</b> · {ctxHolder}</div>
        </div>

        {/* DevAgenda — all open */}
        <div style={AGENDA}>
          <div style={AGENDA_HEAD}>
            <span>4 milestones</span>
            <span style={AGENDA_N}>(0 done · 0 in progress · 4 open)</span>
          </div>
          <div style={AGENDA_ITEMS}>
            {['Implement', 'Validate', 'Internal review', 'Push & wait for CI'].map((m) => (
              <span key={m} style={AGENDA_IT}><span style={AGENDA_GL}>◻</span>{m}</span>
            ))}
          </div>
        </div>

        {/* body: conversation preview + right rail */}
        <div style={BODY}>
          <div style={CONVO}>
            <div style={SCROLL}>
              <div style={BOUNDARY}>
                <span style={BOUNDARY_LN} />
                <span style={BOUNDARY_PILL}>
                  ⏳ Queued{myPosition ? ` · materialized from queue pos ${myPosition}` : ''}
                  {' '}· agent session pre-warming
                </span>
                <span style={BOUNDARY_LN} />
              </div>

              <div style={PREVIEW}>
                <span style={PREVIEW_AV}>A</span>
                <div style={PCARD}>
                  <div style={PCARD_WHO}>
                    Will start with <span style={PCARD_T}>· what the agent reads on its first turn</span>
                  </div>
                  <div style={PCARD_LBL}>⏳ Opening prompt — editable in the composer below</div>
                  {task.openingPrompt && task.openingPrompt.trim() !== ''
                    ? task.openingPrompt.split('\n').map((line, i) => (
                      <p key={i} style={{ ...PCARD_P, marginTop: i === 0 ? 0 : 6 }}>{line}</p>
                    ))
                    : <p style={PCARD_P}>No opening prompt yet — type below to seed the agent's
                      first turn.</p>}
                </div>
              </div>

              <div style={EMPTY_HINT}>
                The conversation will appear here when the agent's slot opens.<br />
                You can refine the opening prompt below in the meantime — anything you type
                accumulates into the agent's first turn.
              </div>
            </div>

            {/* composer — enabled, writes to the opening prompt */}
            <div style={COMPOSER}>
              <div style={Q_STRIP}>
                <span style={{ fontSize: 14 }}>⏳</span>
                <span style={{ fontWeight: 700 }}>Queued · waiting for slot</span>
                <span style={Q_V}>slot {slotsInUse}/{parallelSlots} in use</span>
                <span style={Q_HELP}>Your input here becomes part of the agent's first turn.</span>
              </div>
              <div style={CBOX}>
                <span style={CBOX_P}>›</span>
                <textarea
                  style={CBOX_T}
                  placeholder="Add to the opening prompt…"
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
                      e.preventDefault();
                      void setAsOpening();
                    }
                  }}
                />
              </div>
              {error && <div style={ERR}>{error}</div>}
              <div style={FOOT}>
                <span style={SCOPE}>▸ Task {task.seq} opening</span>
                <span><span style={KBD}>⌘</span>+<span style={KBD}>↵</span> append to opening</span>
                <button
                  style={{ ...SEND_BTN, opacity: input.trim() === '' || busy ? 0.5 : 1 }}
                  disabled={input.trim() === '' || busy}
                  onClick={() => void setAsOpening()}
                >↵ Set as opening</button>
              </div>
            </div>
          </div>

          {/* right rail */}
          <aside style={RAIL}>
            <button type="button" style={UP_THREAD} onClick={onBackToTrunk}>
              ↑ Thread · {thread?.title ?? 'Thread'}
            </button>

            <div style={SEC}>
              <div style={SEC_H}><span>Phase</span><span style={SEC_R}>precise state</span></div>
              <div style={PHASECARD}>
                <span style={{ ...PHASE_CHIP, fontSize: 10.5 }}><span style={PHASE_GLYPH} />QUEUED</span>
                <div style={PHASE_DESC}>
                  Task row exists; the agent session is pre-warming so the{' '}
                  <b>QUEUED → IMPLEMENTING</b> transition takes ms when the slot opens. No compute
                  consumed yet.
                </div>
              </div>
            </div>

            <div style={SEC}>
              <div style={SEC_H}><span>Slot</span></div>
              <div style={SLOTCARD}>
                <SlotRow l="Held by" v={holderName ? `${holderName}` : 'free'} />
                <SlotRow l="Their phase" v={slotHolder ? phaseLabel(slotHolder.phase) : '—'} warn={!!slotHolder} />
                <SlotRow l="Parallel slots" v={`${slotsInUse} / ${parallelSlots}`} />
                <SlotRow
                  l="Position in queue"
                  v={myPosition ? `${myPosition} of ${liveQueue.length}` : '—'}
                />
                <SlotRow l="ETA" v={slotHolder ? `on ${holderName} ship` : 'starting'} muted />
              </div>
            </div>

            <div style={SEC}>
              <div style={SEC_H}><span>Plan</span><span style={SEC_R}>editable while QUEUED</span></div>
              <div style={EDITCARD}>
                <b>Plan is editable.</b> The opening prompt is editable from the composer below
                until the slot opens. Once the agent reads it, the plan is frozen.
              </div>
            </div>

            <div style={SEC}>
              <div style={SEC_H}><span>Linked PR</span></div>
              <div style={{ ...PHASECARD, opacity: 0.65 }}>
                <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                  No PR yet · will open when Task ships
                </div>
              </div>
            </div>
          </aside>
        </div>
      </div>
    </div>
  );
}

function FlowNode({ label, last }: { label: string; last: boolean }): React.ReactElement {
  return (
    <>
      <div style={NODE}><div style={NODE_DOT} /><div style={NODE_NM}>{label}</div></div>
      {!last && <div style={CONNECTOR} />}
    </>
  );
}

function SlotRow(props: { l: string; v: string; warn?: boolean; muted?: boolean }): React.ReactElement {
  return (
    <div style={SLOT_ROW}>
      <span style={SLOT_L}>{props.l}</span>
      <span style={{ ...SLOT_V, ...(props.warn ? { color: '#92400e' } : {}),
        ...(props.muted ? { color: 'var(--text-muted)', fontWeight: 400 } : {}) }}>{props.v}</span>
    </div>
  );
}

const PAGE: React.CSSProperties = {
  position: 'relative', minHeight: '100vh',
  background: 'radial-gradient(40% 50% at 10% 14%, rgba(124,92,255,0.10), transparent 70%), #fafafe',
  color: 'var(--text-primary)',
};
const SPINE: React.CSSProperties = {
  position: 'absolute', left: 0, top: 0, bottom: 0, width: 6, zIndex: 9,
  background: 'linear-gradient(180deg, #94a3b8, #64748b)',
};
const TOP: React.CSSProperties = {
  height: 50, display: 'flex', alignItems: 'center', gap: 9, padding: '0 20px',
  background: 'rgba(255,255,255,0.7)', borderBottom: '1px solid rgba(124,92,255,0.12)',
  position: 'sticky', top: 0, zIndex: 8, backdropFilter: 'blur(14px)',
};
const BACK: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 7, padding: '5px 11px',
  border: '1px solid var(--border)', background: 'rgba(255,255,255,0.7)', borderRadius: 999,
  fontSize: 12.5, color: 'var(--text-secondary)', cursor: 'pointer',
};
const PHASE_CHIP: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 6, padding: '4px 11px 4px 9px',
  borderRadius: 999, fontSize: 11, fontWeight: 700, letterSpacing: '.03em',
  background: 'var(--surface-soft)', color: 'var(--text-muted)', border: '1px solid var(--border-soft)',
};
const PHASE_GLYPH: React.CSSProperties = {
  width: 7, height: 7, borderRadius: '50%', background: '#9ca3af',
};
const PHASE_SUB: React.CSSProperties = { fontWeight: 500, opacity: 0.8, fontSize: 10.5 };
const ALTBAR: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 12, padding: '9px 22px',
  background: 'linear-gradient(90deg, rgba(148,163,184,0.16), rgba(148,163,184,0.04))',
  borderBottom: '1px solid rgba(100,116,139,0.22)',
};
const ALT_BADGE: React.CSSProperties = {
  fontSize: 11, fontWeight: 800, letterSpacing: '.05em', padding: '3px 11px', borderRadius: 999,
  color: '#fff', background: 'linear-gradient(135deg, #94a3b8, #475569)',
};
const ALT_TITLE: React.CSSProperties = { fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' };
const ALT_META: React.CSSProperties = {
  fontSize: 11.5, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)',
};
const CODE: React.CSSProperties = { fontFamily: 'var(--font-mono)' };
const FLOW_BAND: React.CSSProperties = {
  padding: '13px 22px 11px', background: 'rgba(255,255,255,0.72)',
  borderBottom: '1px solid var(--border-soft)',
};
const STEPPER: React.CSSProperties = {
  display: 'flex', alignItems: 'flex-start', gap: 0, maxWidth: 1080, margin: '0 auto',
  padding: '0 8px', justifyContent: 'center',
};
const PRE_NODE: React.CSSProperties = {
  width: 22, height: 22, borderRadius: '50%', background: '#fef3c7', border: '2px solid #d97706',
  display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 11,
  color: '#92400e', marginRight: 8,
};
const NODE: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 5, minWidth: 70,
};
const NODE_DOT: React.CSSProperties = {
  width: 18, height: 18, borderRadius: '50%', background: '#fff', border: '2px solid var(--border-strong)',
};
const NODE_NM: React.CSSProperties = {
  fontSize: 10.5, color: 'var(--text-muted)', fontWeight: 600, whiteSpace: 'nowrap', padding: '0 4px',
};
const CONNECTOR: React.CSSProperties = {
  height: 2, flex: 1, background: 'var(--border-soft)', marginTop: 9, minWidth: 12,
};
const CTX_LINE: React.CSSProperties = {
  textAlign: 'center', marginTop: 10, fontSize: 11.5, color: 'var(--text-muted)',
};
const AGENDA: React.CSSProperties = {
  background: 'linear-gradient(180deg, rgba(148,163,184,0.10), rgba(255,255,255,0))',
  borderBottom: '1px solid rgba(100,116,139,0.18)', padding: '9px 22px',
};
const AGENDA_HEAD: React.CSSProperties = {
  display: 'flex', alignItems: 'baseline', gap: 9, fontSize: 10.5, fontWeight: 800,
  color: 'var(--text-muted)', letterSpacing: '.04em', textTransform: 'uppercase',
};
const AGENDA_N: React.CSSProperties = {
  fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)', letterSpacing: 0,
  textTransform: 'none', fontWeight: 600,
};
const AGENDA_ITEMS: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 18, marginTop: 4, flexWrap: 'wrap',
};
const AGENDA_IT: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11.5, color: 'var(--text-muted)',
};
const AGENDA_GL: React.CSSProperties = { fontSize: 13, color: 'var(--border-strong)' };
const BODY: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: '1fr 280px', flex: 1, minHeight: 460,
};
const CONVO: React.CSSProperties = { display: 'flex', flexDirection: 'column', minWidth: 0 };
const SCROLL: React.CSSProperties = {
  flex: 1, overflowY: 'auto', padding: '18px 32px 8px', display: 'flex', flexDirection: 'column',
  gap: 14,
};
const BOUNDARY: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 12, margin: '2px 0', color: 'var(--text-muted)',
  fontSize: 11.5,
};
const BOUNDARY_LN: React.CSSProperties = {
  flex: 1, height: 1,
  background: 'linear-gradient(90deg, transparent, rgba(148,163,184,0.30) 30%, rgba(148,163,184,0.30) 70%, transparent)',
};
const BOUNDARY_PILL: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 7, padding: '4px 14px',
  background: 'rgba(148,163,184,0.10)', border: '1px solid rgba(148,163,184,0.28)', borderRadius: 999,
  whiteSpace: 'nowrap', color: 'var(--text-secondary)',
};
const PREVIEW: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: '32px 1fr', gap: 11, maxWidth: '80%', alignSelf: 'flex-start',
  opacity: 0.85,
};
const PREVIEW_AV: React.CSSProperties = {
  width: 32, height: 32, borderRadius: 10, background: 'linear-gradient(135deg, #94a3b8, #475569)',
  color: '#fff', display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
  fontSize: 12, fontWeight: 700,
};
const PCARD: React.CSSProperties = {
  background: 'rgba(255,255,255,0.86)', border: '1.5px dashed rgba(148,163,184,0.45)',
  borderRadius: '5px 15px 15px 15px', padding: '11px 15px',
};
const PCARD_WHO: React.CSSProperties = {
  fontSize: 11, fontWeight: 700, color: '#475569', marginBottom: 5, display: 'flex',
  alignItems: 'center', gap: 8,
};
const PCARD_T: React.CSSProperties = {
  color: 'var(--text-subtle)', fontWeight: 500, fontSize: 10.5, fontStyle: 'italic',
};
const PCARD_LBL: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 9.5, fontWeight: 800,
  letterSpacing: '.04em', padding: '1px 7px', borderRadius: 999, background: 'rgba(245,158,11,0.10)',
  color: '#92400e', border: '1px solid #fcd34d', marginBottom: 6, textTransform: 'uppercase',
};
const PCARD_P: React.CSSProperties = {
  fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.55, margin: 0, fontStyle: 'italic',
};
const EMPTY_HINT: React.CSSProperties = {
  alignSelf: 'center', margin: '22px 0 8px', padding: '11px 18px', background: 'rgba(255,255,255,0.55)',
  border: '1px dashed var(--border)', borderRadius: 12, fontSize: 11, color: 'var(--text-subtle)',
  maxWidth: 480, textAlign: 'center', lineHeight: 1.5,
};
const COMPOSER: React.CSSProperties = {
  padding: '12px 24px 15px', borderTop: '1px solid var(--border)', background: 'rgba(255,255,255,0.62)',
};
const Q_STRIP: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 10, padding: '7px 13px', background: 'rgba(245,158,11,0.08)',
  border: '1px solid #fcd34d', borderRadius: 10, fontSize: 11.5, color: '#92400e', marginBottom: 9,
};
const Q_V: React.CSSProperties = {
  fontFamily: 'var(--font-mono)', color: '#7c2d12', fontWeight: 600, fontSize: 11, padding: '1px 7px',
  borderRadius: 999, background: 'rgba(245,158,11,0.14)', border: '1px solid rgba(245,158,11,0.32)',
};
const Q_HELP: React.CSSProperties = {
  color: '#7c2d12', marginLeft: 'auto', fontStyle: 'italic', fontSize: 11,
};
const CBOX: React.CSSProperties = {
  display: 'flex', alignItems: 'flex-start', gap: 11, background: 'rgba(255,255,255,0.9)',
  border: '1px solid var(--border)', borderLeft: '3px solid #d97706', borderRadius: 15,
  padding: '11px 15px',
};
const CBOX_P: React.CSSProperties = {
  color: '#d97706', fontWeight: 700, fontFamily: 'var(--font-mono)', fontSize: 15,
};
const CBOX_T: React.CSSProperties = {
  flex: 1, color: 'var(--text-primary)', fontSize: 13, lineHeight: 1.55, border: 0, outline: 'none',
  background: 'transparent', resize: 'vertical', minHeight: 22, fontFamily: 'inherit',
};
const FOOT: React.CSSProperties = {
  marginTop: 9, display: 'flex', alignItems: 'center', gap: 10, fontSize: 11, color: 'var(--text-subtle)',
};
const SCOPE: React.CSSProperties = {
  background: 'rgba(245,158,11,0.10)', color: '#92400e', border: '1px solid #fcd34d', padding: '2px 9px',
  borderRadius: 999, fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 700,
};
const KBD: React.CSSProperties = {
  background: 'var(--surface)', border: '1px solid var(--border)', padding: '0 5px', borderRadius: 4,
  fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--text-secondary)',
};
const SEND_BTN: React.CSSProperties = {
  marginLeft: 'auto', padding: '4px 12px', background: 'linear-gradient(135deg,#fbbf24,#d97706)',
  color: '#fff', border: 0, borderRadius: 9, fontSize: 11.5, fontWeight: 800, cursor: 'pointer',
};
const RAIL: React.CSSProperties = {
  borderLeft: '1px solid var(--border-soft)', background: 'rgba(248,250,252,0.6)',
  padding: '14px 13px 24px', display: 'flex', flexDirection: 'column', gap: 14, overflowY: 'auto',
};
const UP_THREAD: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 6, alignSelf: 'flex-start', padding: '5px 11px',
  border: '1px solid var(--border)', background: 'rgba(255,255,255,0.7)', borderRadius: 999,
  fontSize: 12, color: '#0d9488', fontWeight: 600, cursor: 'pointer',
};
const SEC: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 6 };
const SEC_H: React.CSSProperties = {
  fontSize: 9.5, fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase',
  letterSpacing: '.06em', padding: '0 2px', display: 'flex', alignItems: 'baseline',
};
const SEC_R: React.CSSProperties = {
  marginLeft: 'auto', color: 'var(--text-subtle)', fontWeight: 500, letterSpacing: 0,
  textTransform: 'none', fontSize: 10,
};
const PHASECARD: React.CSSProperties = {
  background: 'rgba(255,255,255,0.7)', border: '1px solid var(--border-soft)', borderRadius: 10,
  padding: '9px 11px',
};
const PHASE_DESC: React.CSSProperties = {
  fontSize: 11, color: 'var(--text-muted)', marginTop: 6, lineHeight: 1.45,
};
const SLOTCARD: React.CSSProperties = {
  background: 'rgba(255,255,255,0.7)', border: '1px solid var(--border-soft)', borderRadius: 10,
  padding: '9px 11px',
};
const SLOT_ROW: React.CSSProperties = {
  display: 'flex', alignItems: 'baseline', padding: '5px 0', borderBottom: '1px dashed var(--border-soft)',
  fontSize: 12,
};
const SLOT_L: React.CSSProperties = { color: 'var(--text-muted)', fontSize: 10.5 };
const SLOT_V: React.CSSProperties = {
  marginLeft: 'auto', color: 'var(--text-primary)', fontWeight: 700, fontFamily: 'var(--font-mono)',
  fontSize: 11,
};
const EDITCARD: React.CSSProperties = {
  background: 'rgba(245,158,11,0.05)', border: '1px dashed #fcd34d', borderRadius: 10,
  padding: '9px 11px', fontSize: 11, color: '#92400e', lineHeight: 1.5,
};
const ERR: React.CSSProperties = {
  marginTop: 8, fontSize: 11, color: '#b91c1c', background: 'rgba(239,68,68,0.08)',
  border: '1px solid rgba(239,68,68,0.25)', borderRadius: 8, padding: '6px 9px',
};
