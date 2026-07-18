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
import { useRef, useState } from 'react';
import type { CSSProperties, KeyboardEvent } from 'react';
import { usePR } from '../pr/usePR';
import { useAgentReviewState } from '../review/useAgentReviewState';
import type { AgentReviewData, ReviewRoundRow } from '../review/agentReviewTypes';
import { formatCents } from '../review/agentReviewTypes';
import {
  buildSpine, reviewedShas, roundChip, roundIsLive, roundStats,
  type RoundChipTone, type SpineEntry,
} from './agentColumnModel';
import '../css/pulls.css';

/**
 * The workspace agent-review column (docs/mockups/design/pr-redesign/
 * Workspace PRs.dc.html, "Agent review task" view) — swaps in for the
 * work-list column while the PR detail pane stays open on the right.
 * Data + actions come from the same useAgentReviewState hook the round
 * page uses; the round page keeps working in parallel.
 */

const TONE: Record<RoundChipTone, { c: string; bd: string | null; bg: string; dot: string }> = {
  complete: { c: '#59636e', bd: null, bg: '#eceef0', dot: '#8b949e' },
  cancelled: { c: '#59636e', bd: null, bg: '#eceef0', dot: '#8b949e' },
  questions: { c: '#9a6700', bd: 'rgba(154,103,0,0.3)', bg: '#fff8e5', dot: '#bf8700' },
  running: { c: '#7c3aed', bd: 'rgba(139,92,246,0.3)', bg: 'rgba(139,92,246,0.08)', dot: '#8b5cf6' },
  queued: { c: '#7c3aed', bd: 'rgba(139,92,246,0.3)', bg: 'rgba(139,92,246,0.08)', dot: '#8b5cf6' },
  errored: { c: '#cf222e', bd: 'rgba(207,34,46,0.3)', bg: '#ffebe9', dot: '#cf222e' },
};

const shaChipStyle: CSSProperties = { fontFamily: "'SF Mono',ui-monospace,Menlo,monospace", fontSize: 11, color: '#0969da', background: '#ddf4ff', borderRadius: 5, padding: '1px 7px' };
const entryChipStyle: CSSProperties = { fontSize: 10, fontWeight: 600, color: '#59636e', border: '1px solid #e1e5e9', borderRadius: 999, padding: '1px 7px' };
const DOT: Record<SpineEntry['kind'], string> = { planning: '#8b5cf6', investigation: '#eceef0', verification: '#eceef0' };

function Chevron({ size, strokeWidth = 2.2 }: { size: number; strokeWidth?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round">
      <path d="m9 18 6-6-6-6" />
    </svg>
  );
}

function FoldChip({ tone, label }: { tone: RoundChipTone; label: string }) {
  const t = TONE[tone];
  return (
    <span style={{ fontSize: 11, fontWeight: 600, color: t.c, background: t.bg, border: t.bd === null ? undefined : `1px solid ${t.bd}`, borderRadius: 999, padding: '2px 10px', whiteSpace: 'nowrap', flexShrink: 0 }}>
      {label}
    </span>
  );
}

function SpineDot({ color, top = 4 }: { color: string; top?: number }) {
  return <span style={{ position: 'absolute', left: -16, top, width: 9, height: 9, borderRadius: '50%', background: color, border: '2px solid #fff' }} />;
}

function EntryHead({ label, agent, chips }: { label: string; agent: string; chips: string[] }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
      <span style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.06em', color: '#17191c' }}>{label}</span>
      <span style={{ fontSize: 11, color: '#8b949e' }}>{agent}</span>
      {chips.map(chip => <span key={chip} style={entryChipStyle}>{chip}</span>)}
    </div>
  );
}

function Spine({ data, round, index }: { data: AgentReviewData; round: ReviewRoundRow; index: number }) {
  const chip = roundChip(round);
  const stats = roundStats(data, round.id);
  const shas = reviewedShas(data, round);
  return (
    <div style={{ position: 'relative', paddingLeft: 16, marginTop: 6 }}>
      <div style={{ position: 'absolute', left: 4, top: 6, bottom: 6, width: 2, background: '#e9ebee' }} />
      {buildSpine(data, round).map((entry, at) => (
        <div key={at} style={{ position: 'relative', marginBottom: 14 }}>
          <SpineDot color={DOT[entry.kind]} />
          {entry.kind === 'planning' ? (
            <>
              <EntryHead label="PLANNING" agent="planner" chips={entry.chips} />
              <div style={{ fontSize: 12.5, color: '#1f2328', lineHeight: 1.55, marginTop: 5 }}>
                {entry.scopeLabel} scope — <b>{entry.objectivesLabel}</b> assigned{entry.reviewers.length > 0 ? ` to ${entry.reviewers}` : ''}.{' '}
                {entry.scopeLabel === 'Full'
                  ? 'The panel is reviewing the changed code against the deterministic plan.'
                  : 'Only affected findings and their dependency spans are being re-verified.'}
              </div>
            </>
          ) : (
            <>
              <EntryHead label={entry.kind === 'verification' ? 'VERIFICATION' : 'INVESTIGATION'} agent={entry.agent} chips={entry.chips} />
              <div style={{ fontSize: 12.5, color: '#1f2328', lineHeight: 1.55, marginTop: 5 }}>{entry.summary}</div>
              {entry.sub !== null && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 5, fontSize: 11.5, color: '#59636e', cursor: 'pointer' }}>
                  <Chevron size={10} strokeWidth={2.4} />{entry.sub.label} · {entry.sub.steps} steps
                </div>
              )}
            </>
          )}
        </div>
      ))}
      {!roundIsLive(round) && (
        <div style={{ position: 'relative' }}>
          <SpineDot color="#bf8700" top={8} />
          <div style={{ border: '1px solid #d5dbe1', borderRadius: 10, padding: '10px 13px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: 13, fontWeight: 700, color: '#17191c' }}>Round {index + 1} {chip.label}</span>
              <span style={{ marginLeft: 'auto', fontSize: 11.5, color: '#59636e', whiteSpace: 'nowrap' }}>
                {stats.findings} findings · <span style={{ fontFamily: "'SF Mono',ui-monospace,Menlo,monospace" }}>{formatCents(round.cost_cents)}</span>
              </span>
            </div>
            {shas !== null && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginTop: 7, fontSize: 11.5, color: '#59636e' }}>
                Reviewed<span style={shaChipStyle}>{shas.text}</span>{shas.count} {shas.count === 1 ? 'commit' : 'commits'}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

export default function AgentColumn({ prId, workspaceId, onBack }: {
  prId: string;
  workspaceId?: string | null;
  onBack: () => void;
}) {
  const { bundle, refresh } = usePR(prId);
  const {
    data, latestRound, latestRoundNumber, startRound, sendRoundMessage,
  } = useAgentReviewState(bundle, refresh, undefined, workspaceId);
  const [openIds, setOpenIds] = useState<Set<string> | null>(null);
  const [mode, setMode] = useState<'steer' | 'new-round'>('steer');
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const latestId = latestRound?.id;
  // A freshly appended round becomes the expanded one, like the round page.
  const previousLatest = useRef(latestId);
  if (previousLatest.current !== latestId) {
    previousLatest.current = latestId;
    if (openIds !== null) setOpenIds(null);
  }
  const isOpen = (id: string) => openIds === null ? id === latestId : openIds.has(id);
  const toggle = (id: string) => setOpenIds(current => {
    const next = new Set(current ?? (latestId === undefined ? [] : [latestId]));
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  const canSteer = latestRound !== undefined && latestRound.status === 'RUNNING'
    && latestRound.message_gate_open !== false;
  const nextRoundNumber = (data?.rounds.length ?? 0) + 1;
  const send = async () => {
    const body = text.trim();
    if (body.length === 0 || sending || latestRound === undefined) return;
    setSending(true);
    const ok = mode === 'new-round'
      ? await startRound(body)
      : canSteer ? await sendRoundMessage(latestRound.id, 'panel', body) : false;
    if (ok) {
      setText('');
      setMode('steer');
    }
    setSending(false);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    void send();
  };

  const headTone = latestRound === undefined ? null : TONE[roundChip(latestRound).tone];
  const cap = latestRound?.budget_json.cost_cap_cents ?? 0;
  const spentPct = latestRound === undefined || cap === 0 ? 0 : Math.min(100, latestRound.cost_cents / cap * 100);
  const localSource = latestRound?.capabilities_json.source_mode === 'local-source';

  return (
    <div style={{ flex: 1, minWidth: 280, display: 'flex', flexDirection: 'column', minHeight: 0, background: '#fff' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '9px 12px', borderBottom: '1px solid #e7e9ec', flexShrink: 0, flexWrap: 'wrap' }}>
        <button className="pl-hov-btn" onClick={onBack} title="Back to pull requests" style={{ display: 'inline-flex', alignItems: 'center', gap: 5, padding: '4px 10px', border: '1px solid #d5dbe1', background: '#fff', borderRadius: 8, fontSize: 12.5, fontWeight: 600, color: '#454c54', cursor: 'pointer', flexShrink: 0 }}>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="m15 18-6-6 6-6" /></svg>Back
        </button>
        <button
          className="pl-hov-agent-dash"
          onClick={() => { setMode('new-round'); inputRef.current?.focus(); }}
          disabled={data === null}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 6, border: '1.5px dashed rgba(139,92,246,0.45)', background: 'transparent', borderRadius: 8, padding: '4px 11px', fontSize: 12, fontWeight: 600, color: '#7c3aed', cursor: 'pointer', flexShrink: 0 }}
        >
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"><path d="M12 5v14" /><path d="M5 12h14" /></svg>Trigger next round
        </button>
        <span style={{ flex: 1 }} />
        {latestRound !== undefined && (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, minWidth: 0 }} title={`Round ${latestRoundNumber} budget · ${formatCents(latestRound.cost_cents)} of ${formatCents(cap)} spent`}>
            <span style={{ fontSize: 10, fontWeight: 700, letterSpacing: '0.07em', color: '#8b949e', whiteSpace: 'nowrap' }}>ROUND {latestRoundNumber} BUDGET</span>
            <span style={{ width: 70, height: 5, background: '#eceef0', borderRadius: 999, flexShrink: 0 }}>
              <span style={{ display: 'block', width: `${spentPct}%`, height: '100%', background: 'linear-gradient(90deg,#a78bfa,#7c3aed)', borderRadius: 999 }} />
            </span>
            <span style={{ fontFamily: "'SF Mono',ui-monospace,Menlo,monospace", fontSize: 11, color: '#59636e', whiteSpace: 'nowrap' }}>{formatCents(latestRound.cost_cents)} / {formatCents(cap)}</span>
          </span>
        )}
        {localSource && (
          <span style={{ fontSize: 10, fontWeight: 700, letterSpacing: '0.07em', color: '#1a7f37', border: '1px solid rgba(31,136,61,0.3)', background: '#f2fbf4', borderRadius: 7, padding: '3px 9px', whiteSpace: 'nowrap' }}>LOCAL SOURCE</span>
        )}
        {latestRound !== undefined && headTone !== null && (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 10, fontWeight: 700, letterSpacing: '0.07em', color: headTone.c, border: `1px solid ${headTone.bd ?? headTone.bg}`, background: headTone.bg, borderRadius: 7, padding: '3px 9px', whiteSpace: 'nowrap' }}>
            <span style={{ width: 6, height: 6, borderRadius: '50%', background: headTone.dot }} />
            {roundChip(latestRound).label.toUpperCase()} · {formatCents(latestRound.cost_cents)}
          </span>
        )}
      </div>
      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '12px 14px 20px' }}>
        {data === null && (
          <div style={{ padding: '24px 12px', fontSize: 13, color: '#8b949e', textAlign: 'center' }}>No agent review yet.</div>
        )}
        {data?.rounds.map((round, index) => {
          const chip = roundChip(round);
          const stats = roundStats(data, round.id);
          const open = isOpen(round.id);
          const shas = reviewedShas(data, round);
          return (
            <div key={round.id}>
              <div className="pl-hov-btn" onClick={() => toggle(round.id)} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '7px 4px', borderRadius: 7, cursor: 'pointer', marginTop: index === 0 ? 0 : 2 }}>
                <span style={{ display: 'inline-flex', color: '#8b949e', flexShrink: 0, transform: open ? 'rotate(90deg)' : 'none', transition: 'transform 0.15s' }}><Chevron size={11} /></span>
                <span style={{ fontSize: 12, fontWeight: 800, letterSpacing: '0.05em', color: '#17191c', whiteSpace: 'nowrap' }}>ROUND {index + 1}</span>
                <span style={{ fontSize: 12, color: '#8b949e', whiteSpace: 'nowrap' }}>{round.scope} scope</span>
                <span style={{ fontSize: 12, color: '#59636e', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>· {stats.findings} findings{stats.rejected > 0 ? ` · ${stats.rejected} rejected` : ''} · {formatCents(round.cost_cents)}</span>
                <span style={{ flex: 1, height: 1, background: '#e7e9ec', minWidth: 12 }} />
                <FoldChip tone={chip.tone} label={chip.label} />
              </div>
              {open && round.id === latestId && <Spine data={data} round={round} index={index} />}
              {open && round.id !== latestId && (
                <div style={{ padding: '2px 4px 8px 23px', fontSize: 12, color: '#59636e', lineHeight: 1.5 }}>
                  {stats.findings} findings{stats.rejected > 0 ? ` · ${stats.rejected} rejected by the verifier` : ''}{shas !== null && <> · reviewed <span style={shaChipStyle}>{shas.text}</span></>}
                </div>
              )}
            </div>
          );
        })}
      </div>
      <div style={{ borderTop: '1px solid #e7e9ec', padding: '9px 12px 11px', flexShrink: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 11.5, color: '#59636e', paddingBottom: 7 }}>
          To
          <button className="pl-hov-btn" title="Provider picker not wired yet" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '3px 10px', border: '1px solid #d5dbe1', background: '#fff', borderRadius: 999, fontSize: 11.5, fontWeight: 600, color: '#7c3aed', cursor: 'pointer' }}>
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="5" y="9" width="14" height="10" rx="2.5" /><path d="M12 9V5.5" /><circle cx="12" cy="4" r="1.4" /><path d="M9 13.5v1.6" /><path d="M15 13.5v1.6" /></svg>
            Review panel
            <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
          </button>
          {mode === 'new-round' ? `queues round ${nextRoundNumber}` : `steers round ${latestRoundNumber}`}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <input
            ref={inputRef}
            value={text}
            onChange={event => setText(event.target.value)}
            onKeyDown={onKeyDown}
            disabled={sending || (mode === 'steer' && !canSteer)}
            placeholder={mode === 'new-round'
              ? `Describe what round ${nextRoundNumber} should check…`
              : `Steer round ${latestRoundNumber} — seed a hypothesis, or answer a question…`}
            aria-label={mode === 'new-round' ? `Describe round ${nextRoundNumber}` : `Steer round ${latestRoundNumber}`}
            style={{ flex: 1, minWidth: 0, border: '1px solid #d5dbe1', borderRadius: 9, padding: '8px 12px', fontSize: 12.5, color: '#17191c', background: '#fff' }}
          />
          <span
            className="pl-hov-send"
            onClick={() => { void send(); }}
            role="button"
            aria-label={mode === 'new-round' ? `Start round ${nextRoundNumber}` : 'Send'}
            style={{ width: 30, height: 30, borderRadius: '50%', background: '#17191c', color: '#fff', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', flexShrink: 0, opacity: text.trim().length === 0 || sending ? 0.5 : 1 }}
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 19V5" /><path d="m5 12 7-7 7 7" /></svg>
          </span>
        </div>
      </div>
    </div>
  );
}
