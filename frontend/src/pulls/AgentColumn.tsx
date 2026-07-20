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
import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { usePR } from '../pr/usePR';
import { useAgentReviewState } from '../review/useAgentReviewState';
import type { AgentReviewData, ReviewRoundRow } from '../review/agentReviewTypes';
import { formatCents } from '../review/agentReviewTypes';
import type { LocalPRBundle } from '../types/localPr';
import {
  buildConversationModel, roundChip,
  type AgentConversationModel, type ConversationInvestigator, type ConversationProgressState,
} from './agentColumnModel';
import '../css/pulls.css';

type AsyncAction = boolean | Promise<boolean>;

const MONO = "'SF Mono',Menlo,ui-monospace,monospace";
const PURPLE = '#8250df';
const BLUE = '#0969da';
const ACCENT_COLORS: Record<ConversationInvestigator['accent'], string> = {
  blue: BLUE,
  amber: '#bf8700',
  purple: PURPLE,
};

function accentColor(row: ConversationInvestigator): string {
  return ACCENT_COLORS[row.accent];
}

function BranchIcon({ size = 11 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="6" cy="12" r="2.3" /><path d="M8.5 12H21" /><path d="m15 8.5 3.5 3.5-3.5 3.5" />
    </svg>
  );
}

function PullIcon() {
  return (
    <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="6" cy="5.5" r="2.4" /><circle cx="6" cy="18.5" r="2.4" /><circle cx="18" cy="18.5" r="2.4" /><path d="M6 8v8" /><path d="M11.5 5.5H15a3 3 0 0 1 3 3V16" />
    </svg>
  );
}

function Chevron({ down = false, size = 11 }: { down?: boolean; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={down ? 'm6 9 6 6 6-6' : 'm9 18 6-6-6-6'} />
    </svg>
  );
}

function TimelineRail({ marker, solid = false, pulse = false, top = 14 }: {
  marker: string;
  solid?: boolean;
  pulse?: boolean;
  top?: number;
}) {
  return (
    <div style={{ width: 36, position: 'relative', flexShrink: 0 }}>
      <span style={{ position: 'absolute', left: 11, top: 0, bottom: 0, width: 2, background: '#eceef0' }} />
      <span style={solid
        ? { position: 'absolute', left: 7, top, width: 10, height: 10, background: marker, transform: 'rotate(45deg)', borderRadius: 2 }
        : { position: 'absolute', left: 7, top, width: 10, height: 10, background: '#fff', border: `2.5px solid ${marker}`, transform: 'rotate(45deg)', borderRadius: 2, animation: pulse ? 'pulseDot 1.6s infinite' : undefined }} />
    </div>
  );
}

function ObjectiveRail({ marker, pulse, top = 18 }: { marker: string; pulse: boolean; top?: number }) {
  return (
    <div style={{ width: 36, position: 'relative', flexShrink: 0 }}>
      <span style={{ position: 'absolute', left: 11, top: 0, bottom: 0, width: 2, background: '#eceef0' }} />
      <span style={{ position: 'absolute', left: 8, top, width: 8, height: 8, borderRadius: '50%', background: marker, animation: pulse ? 'pulseDot 1.6s infinite' : undefined }} />
    </div>
  );
}

function leadStatus(row: ConversationInvestigator, running: boolean): { label: string; color: string } {
  if (row.state === 'running') return { label: `investigating · ${row.stepCount} steps`, color: BLUE };
  if (row.state === 'queued') return { label: 'queued', color: '#8b949e' };
  if (running) return { label: `resolved ${row.findings.resolved || row.findings.accepted}`, color: '#1a7f37' };
  if (row.findings.questions > 0) return { label: `${row.findings.questions} ${row.findings.questions === 1 ? 'question' : 'questions'}`, color: '#9a6700' };
  if (row.findings.refuted > 0) return { label: `kept ${row.findings.kept} · refuted ${row.findings.refuted}`, color: '#57606a' };
  if (row.findings.kept > 0) return { label: `kept ${row.findings.kept}`, color: '#57606a' };
  return { label: 'no findings', color: '#1a7f37' };
}

function RoundMilestone({ round, roundNumber, model }: {
  round: ReviewRoundRow;
  roundNumber: number;
  model: AgentConversationModel;
}) {
  const running = model.running;
  const state = roundChip(round);
  const color = running ? BLUE : '#9a6700';
  const scope = round.scope === 'full' ? 'full' : 're-verification';
  return (
    <div style={{ display: 'flex' }}>
      <TimelineRail marker={color} />
      <div style={{ flex: 1, minWidth: 0, padding: '3px 0 10px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '8px 0 2px' }}>
          <span style={{ fontSize: 11, fontWeight: 800, letterSpacing: '0.07em', color: '#17191c' }}>ROUND {roundNumber}</span>
          <span style={{ fontSize: 10.5, fontWeight: 600, color, background: running ? '#ddf4ff' : '#fff8c5', border: `1px solid ${running ? 'rgba(9,105,218,0.3)' : 'rgba(212,167,44,0.45)'}`, borderRadius: 999, padding: '1px 9px', whiteSpace: 'nowrap' }}>
            {running ? `in progress · ${model.durationLabel ?? 'now'}` : state.label}
          </span>
          <span style={{ fontSize: 11, color: '#8b949e', minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {scope} scope · {model.totals.findings.total} findings{running ? ' so far' : ''} · {formatCents(round.cost_cents)}
          </span>
          <span style={{ flex: 1, height: 1, background: '#eef0f2', minWidth: 12 }} />
          <span style={{ fontSize: 10.5, color: '#a5abb2', whiteSpace: 'nowrap' }}>{model.dateLabel}</span>
        </div>
      </div>
    </div>
  );
}

function LeadReviewerCard({ round, model }: { round: ReviewRoundRow; model: AgentConversationModel }) {
  const active = model.investigators.find(row => row.state === 'running');
  const liveText = model.running
    ? active === undefined ? 'planning objectives…' : `investigator ${active.number} active — streaming`
    : `aggregating${model.totals.findings.questions > 0 ? ` — ${model.totals.findings.questions} ${model.totals.findings.questions === 1 ? 'question' : 'questions'} queued for author` : '…'}`;
  const liveColor = model.running ? BLUE : '#9a6700';
  return (
    <div style={{ display: 'flex' }}>
      <div style={{ width: 36, position: 'relative', flexShrink: 0 }}><span style={{ position: 'absolute', left: 11, top: 0, bottom: 0, width: 2, background: '#eceef0' }} /></div>
      <div style={{ flex: 1, minWidth: 0, padding: '0 0 14px' }}>
        <div style={{ border: '1px solid rgba(130,80,223,0.3)', background: 'rgba(130,80,223,0.04)', borderRadius: 11, padding: '11px 14px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ width: 22, height: 22, borderRadius: '50%', background: 'rgba(130,80,223,0.12)', border: '1.5px solid #8250df', color: PURPLE, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 10, fontWeight: 800, flexShrink: 0 }}>L</span>
            <span style={{ fontSize: 13, fontWeight: 500, color: '#17191c' }}>Lead reviewer</span>
            <span style={{ fontSize: 11.5, color: '#8b949e', minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>panel of {model.investigators.length} investigators · deterministic plan</span>
            <span style={{ marginLeft: 'auto', fontSize: 11.5, fontWeight: 600, color: PURPLE, whiteSpace: 'nowrap' }}>{model.doneObjectives} of {model.objectives.length} objectives</span>
          </div>
          <div style={{ display: 'flex', gap: 4, marginTop: 10 }}>
            {model.objectives.map((objective, index) => (
              <span
                key={objective.id}
                title={`Objective ${index + 1} — ${objective.state}`}
                style={{ flex: 1, height: 6, borderRadius: 999, background: model.running ? objective.state === 'done' ? PURPLE : objective.state === 'running' ? BLUE : '#eceef0' : PURPLE, animation: model.running && objective.state === 'running' ? 'pulseDot 1.6s infinite' : undefined }}
              />
            ))}
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 3, marginTop: 9 }}>
            {model.investigators.map(row => {
              const status = leadStatus(row, model.running);
              const tint = accentColor(row);
              return (
                <div key={row.assignmentId} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11.5, padding: '2px 0' }}>
                  <span style={{ width: 15, height: 15, borderRadius: 4, background: `${tint}14`, color: tint, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 8.5, fontWeight: 800, flexShrink: 0 }}>{row.number}</span>
                  <span style={{ color: '#454c54', minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{row.objectiveTitle}</span>
                  <span style={{ marginLeft: 'auto', display: 'inline-flex', alignItems: 'center', gap: 5, color: status.color, fontWeight: 600, flexShrink: 0 }}>
                    {row.state === 'running' && <span style={{ width: 6, height: 6, borderRadius: '50%', background: BLUE, animation: 'pulseDot 1.6s infinite' }} />}
                    {status.label}
                  </span>
                </div>
              );
            })}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginTop: 8, paddingTop: 8, borderTop: '1px solid rgba(130,80,223,0.15)', fontSize: 11.5, color: '#57606a' }}>
            <span>{model.totals.steps} steps</span><span>·</span><span>{formatCents(round.cost_cents)} of {formatCents(round.budget_json.cost_cap_cents)}</span><span>·</span><span>{model.totals.findings.total} findings so far</span>
            <span style={{ marginLeft: 'auto', display: 'inline-flex', alignItems: 'center', gap: 5, color: liveColor, fontWeight: 600, whiteSpace: 'nowrap' }}><span style={{ width: 7, height: 7, borderRadius: '50%', background: model.running ? BLUE : '#d4a72c', animation: 'pulseDot 2s infinite' }} />{liveText}</span>
          </div>
        </div>
      </div>
    </div>
  );
}

function PlanningMilestone({ round, model }: { round: ReviewRoundRow; model: AgentConversationModel }) {
  const api = model.investigators.filter(row => row.scope === 'api').length;
  const cli = model.investigators.filter(row => row.scope === 'cli').length;
  const source = round.scope === 'full' ? 'deterministic' : 'from round-1 questions';
  const countWord = (count: number) => ['zero', 'one', 'two', 'three'][count] ?? String(count);
  const prose = round.scope === 'full'
    ? `Full scope — ${model.objectives.length} objectives assigned across the panel. Reviewing the changed code against the deterministic plan; ${countWord(api)} api ${api === 1 ? 'investigator' : 'investigators'}${cli > 0 ? ` and ${countWord(cli)} cli ${cli === 1 ? 'investigator' : 'investigators'}` : ''}.`
    : `Re-verification scope — ${model.objectives.length} objectives derived from round-1 questions and the author’s new commits. Objectives run in dependency order; findings stream into the round as each completes.`;
  return (
    <div style={{ display: 'flex' }}>
      <TimelineRail marker="#8b949e" />
      <div style={{ flex: 1, minWidth: 0, padding: '3px 0 12px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '8px 0 6px' }}>
          <span style={{ fontSize: 11, fontWeight: 800, letterSpacing: '0.07em', color: '#17191c' }}>PLANNING</span>
          <span style={{ fontSize: 10.5, fontWeight: 600, color: '#57606a', background: '#f6f8fa', border: '1px solid #e7e9ec', borderRadius: 999, padding: '1px 9px' }}>done · {model.objectives.length} objectives · {source}</span>
          <span style={{ flex: 1, height: 1, background: '#eef0f2' }} />
        </div>
        <div style={{ fontSize: 13.5, color: '#1f2328', lineHeight: 1.65 }}>{prose}</div>
      </div>
    </div>
  );
}

function InvestigatorStatus({ state }: { state: ConversationProgressState }) {
  if (state === 'done') {
    return <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11, fontWeight: 600, color: '#1a7f37', flexShrink: 0 }}><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m4.5 12.5 5 5 10-11" /></svg>done</span>;
  }
  if (state === 'running') {
    return <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11, fontWeight: 600, color: BLUE, flexShrink: 0 }}><span style={{ width: 7, height: 7, borderRadius: '50%', background: BLUE, animation: 'pulseDot 1.6s infinite' }} />running</span>;
  }
  return <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11, fontWeight: 600, color: '#8b949e', flexShrink: 0 }}><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><circle cx="12" cy="12" r="8.5" /><path d="M12 8v4.5l3 2" strokeLinecap="round" /></svg>queued</span>;
}

function InvestigatorCard({ row }: { row: ConversationInvestigator }) {
  const running = row.state === 'running';
  const queued = row.state === 'queued';
  const [traceOpen, setTraceOpen] = useState(row.traceOpen);
  useEffect(() => {
    setTraceOpen(row.traceOpen);
  }, [row.assignmentId, row.state, row.traceOpen]);
  const tint = accentColor(row);
  const marker = running ? BLUE : queued ? '#c6cbd1' : tint;
  const rail = running ? BLUE : queued ? '#d5dbe1' : tint;
  return (
    <div data-investigator-state={row.state} style={{ display: 'flex' }}>
      <ObjectiveRail marker={marker} pulse={running} />
      <div style={{ flex: 1, minWidth: 0, padding: '3px 0 12px' }}>
        <div style={{ border: `1px solid ${running ? 'rgba(9,105,218,0.4)' : '#e1e5e9'}`, borderRadius: 11, background: '#fff', boxShadow: '0 1px 2px rgba(0,0,0,0.03)', overflow: 'hidden', opacity: queued ? 0.62 : 1 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '10px 13px', borderLeft: `3px solid ${rail}` }}>
            <span style={{ width: 24, height: 24, borderRadius: 7, background: `${tint}14`, color: tint, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 11, fontWeight: 800, flexShrink: 0 }}>{row.number}</span>
            <span style={{ minWidth: 0, display: 'flex', flexDirection: 'column', gap: 1, flex: 1 }}>
              <span style={{ fontSize: 13, fontWeight: 500, color: '#17191c', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{row.objectiveTitle}</span>
              <span style={{ fontSize: 11, color: '#8b949e' }}>Investigator {row.number} · {row.agent}</span>
            </span>
            <span style={{ fontSize: 10, fontWeight: 700, color: tint, background: `${tint}14`, borderRadius: 5, padding: '2px 7px', flexShrink: 0 }}>{row.scope}</span>
            <span style={{ fontFamily: MONO, fontSize: 10.5, color: '#8b949e', flexShrink: 0 }}>{row.costLabel}</span>
            <InvestigatorStatus state={row.state} />
          </div>
          <div style={{ padding: '2px 13px 11px' }}>
            {!queued && (
              <details className="agent-review-v2__trace" open={traceOpen} onToggle={event => setTraceOpen(event.currentTarget.open)}>
                <summary style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '2px 6px 4px 0', cursor: 'pointer', color: '#8b949e', fontSize: 12.5, listStyle: 'none' }}>
                  {row.foldLabel}<span style={{ fontSize: 11.5, color: '#b6bcc2' }}>{row.stepCount} steps</span><span className="agent-review-v2__trace-chevron" style={{ display: 'inline-flex', transition: 'transform .15s' }}><Chevron size={12} /></span>
                </summary>
                <div style={{ margin: '2px 0 6px 3px', padding: '7px 0 3px 14px', borderLeft: '2px solid #eceef0', display: 'flex', flexDirection: 'column', gap: 6 }}>
                  {row.trace.map((step, index) => (
                    <div key={step.id || index} data-live={step.live ? 'true' : undefined} style={{ display: 'flex', gap: 8, fontSize: 12, color: step.live ? BLUE : '#8b949e', lineHeight: 1.5, alignItems: 'baseline' }}>
                      {step.live
                        ? <span style={{ width: 7, height: 7, borderRadius: '50%', background: BLUE, animation: 'pulseDot 1.6s infinite', flexShrink: 0, alignSelf: 'center' }} />
                        : <span style={{ color: '#c6cbd1' }}>›</span>}
                      <span>{(step.reason.trim() || step.action).replace(/[.:]\s*$/, '')} <span style={{ fontFamily: MONO, fontSize: 11, color: '#57606a' }}>{step.target}</span>{step.post}</span>
                    </div>
                  ))}
                </div>
              </details>
            )}
            <div style={{ fontSize: 13, color: row.state === 'done' ? '#1f2328' : '#57606a', lineHeight: 1.6, fontStyle: row.state === 'done' ? 'normal' : 'italic' }}>{row.summary}</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 8, flexWrap: 'wrap' }}>
              {row.chips.map(chip => {
                const palette = chip.tone === 'success'
                  ? { color: '#1a7f37', background: '#dafbe1', border: 'rgba(45,164,78,0.3)' }
                  : chip.tone === 'question'
                    ? { color: '#9a6700', background: '#fff8c5', border: 'rgba(212,167,44,0.45)' }
                    : { color: '#57606a', background: '#f6f8fa', border: '#e7e9ec' };
                return <span key={chip.label} style={{ fontSize: 10.5, fontWeight: 700, color: palette.color, background: palette.background, border: `1px solid ${palette.border}`, borderRadius: 999, padding: '1px 9px' }}>{chip.label}</span>;
              })}
              <span style={{ marginLeft: 'auto', fontSize: 11, color: '#a5abb2' }}>{row.timeLabel}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function RoundTail({ round, roundNumber, model }: { round: ReviewRoundRow; roundNumber: number; model: AgentConversationModel }) {
  if (model.running) {
    return (
      <div style={{ display: 'flex' }}>
        <ObjectiveRail marker={BLUE} pulse top={16} />
        <div style={{ flex: 1, minWidth: 0, padding: '3px 0 14px' }}>
          <div style={{ border: '1px solid rgba(9,105,218,0.3)', background: '#f4f9ff', borderRadius: 11, padding: '11px 14px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: 13, fontWeight: 500, color: '#17191c' }}>Round {roundNumber} running</span>
              <span style={{ fontSize: 10.5, fontWeight: 600, color: BLUE, background: '#ddf4ff', border: '1px solid rgba(9,105,218,0.3)', borderRadius: 999, padding: '1px 9px' }}>{model.doneObjectives} of {model.objectives.length} objectives done</span>
              <span style={{ marginLeft: 'auto', fontFamily: MONO, fontSize: 11, color: '#57606a' }}>{model.totals.findings.total} findings so far · {formatCents(round.cost_cents)}</span>
            </div>
            <div style={{ fontSize: 12.5, color: '#454c54', lineHeight: 1.6, marginTop: 7 }}>Round summary posts here when all objectives complete. You can steer the running investigators from the composer, or stop the round and keep findings gathered so far.</div>
          </div>
        </div>
      </div>
    );
  }
  const state = roundChip(round);
  return (
    <div style={{ display: 'flex' }}>
      <TimelineRail marker="#9a6700" solid />
      <div style={{ flex: 1, minWidth: 0, padding: '3px 0 14px' }}>
        <div style={{ border: '1px solid rgba(212,167,44,0.45)', background: '#fffdf4', borderRadius: 11, padding: '12px 14px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontSize: 13, fontWeight: 500, color: '#17191c' }}>Round {roundNumber} — {state.label}</span>
            <span style={{ fontSize: 10.5, fontWeight: 600, color: '#9a6700', background: '#fff8c5', border: '1px solid rgba(212,167,44,0.45)', borderRadius: 999, padding: '1px 9px' }}>{model.totals.findings.questions > 0 ? 'needs author' : 'complete'}</span>
            <span style={{ marginLeft: 'auto', fontFamily: MONO, fontSize: 11, color: '#57606a' }}>{model.totals.findings.total} findings · {formatCents(round.cost_cents)}</span>
          </div>
          <div style={{ fontSize: 12.5, color: '#454c54', lineHeight: 1.6, marginTop: 7 }}>Panel kept <b style={{ fontWeight: 600 }}>{model.totals.findings.kept} findings</b> with line-level evidence, refuted {model.totals.findings.refuted}, and raised <b style={{ fontWeight: 600 }}>{model.totals.findings.questions} questions</b> for the author. No blocking defects found.</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 9, flexWrap: 'wrap' }}>
            <span style={{ fontSize: 10.5, fontWeight: 700, color: '#82071e', background: '#ffebe9', border: '1px solid rgba(207,34,46,0.3)', borderRadius: 999, padding: '1px 9px' }}>{model.totals.findings.blocking} blocking</span>
            <span style={{ fontSize: 10.5, fontWeight: 700, color: '#57606a', background: '#f6f8fa', border: '1px solid #e7e9ec', borderRadius: 999, padding: '1px 9px' }}>{model.totals.findings.kept} kept{model.totals.findings.keptLabels.length > 0 ? ` · ${model.totals.findings.keptLabels.join(' ')}` : ''}</span>
            <span style={{ fontSize: 10.5, fontWeight: 700, color: '#9a6700', background: '#fff8c5', border: '1px solid rgba(212,167,44,0.45)', borderRadius: 999, padding: '1px 9px' }}>{model.totals.findings.questions} questions</span>
            {model.reviewedCommits !== null && <><span style={{ fontSize: 11, color: '#8b949e' }}>reviewed</span><span style={{ fontFamily: MONO, fontSize: 10.5, color: BLUE, background: '#ddf4ff', borderRadius: 5, padding: '1px 6px' }}>{model.reviewedCommits.text}</span><span style={{ fontSize: 11, color: '#8b949e' }}>· {model.reviewedCommits.count} {model.reviewedCommits.count === 1 ? 'commit' : 'commits'}</span></>}
            <span style={{ flex: 1 }} />
            <a href="#review-questions" style={{ fontSize: 12, fontWeight: 600, color: BLUE, textDecoration: 'none' }}>Post questions to author</a>
            <a href="#review-findings" style={{ fontSize: 12, color: BLUE, textDecoration: 'none' }}>Open findings ›</a>
          </div>
        </div>
      </div>
    </div>
  );
}

export function AgentReviewConversation({ bundle, data, round, roundNumber, trunkLabel, onBack, onTogglePanel, onStopRound, onStartRound, onSendMessage }: {
  bundle: LocalPRBundle;
  data: AgentReviewData;
  round: ReviewRoundRow;
  roundNumber: number;
  trunkLabel?: string;
  onBack: () => void;
  onTogglePanel?: () => void;
  onStopRound: (roundId: string) => void;
  onStartRound: (seed: string) => AsyncAction;
  onSendMessage: (roundId: string, target: string, text: string) => AsyncAction;
}) {
  const model = buildConversationModel(data, round);
  const [mode, setMode] = useState<'steer' | 'new-round'>('steer');
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [usageOpen, setUsageOpen] = useState(false);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const canSteer = round.status === 'RUNNING' && round.message_gate_open !== false;
  const cap = round.budget_json.cost_cap_cents;
  const spentPct = cap === 0 ? 0 : Math.min(100, round.cost_cents / cap * 100);
  const stateColor = model.running ? BLUE : '#9a6700';
  const stateBg = model.running ? '#ddf4ff' : '#fff8c5';
  const pr = bundle.pr;
  const prStatus = pr.status === 'merged' ? 'MERGED' : pr.status === 'closed' ? 'CLOSED' : 'OPEN';
  const displayTrunkLabel = trunkLabel ?? (pr.branchName || pr.repo?.split('/').at(-1) || 'Pull requests');

  const triggerNextRound = () => {
    setMode('new-round');
    requestAnimationFrame(() => inputRef.current?.focus());
  };
  const send = async () => {
    const body = text.trim();
    if (body.length === 0 || sending) return;
    setSending(true);
    const ok = mode === 'new-round'
      ? await onStartRound(body)
      : canSteer ? await onSendMessage(round.id, 'panel', body) : false;
    if (ok) { setText(''); setMode('steer'); }
    setSending(false);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== 'Enter' || event.shiftKey) return;
    event.preventDefault();
    void send();
  };
  const placeholder = mode === 'new-round'
    ? `Describe what round ${data.rounds.length + 1} should check…`
    : model.running
      ? 'Steer the running round — add a hypothesis, narrow scope, or ask for status…'
      : `Steer round ${roundNumber} — seed a hypothesis, or answer a question…`;

  return (
    <div className="agent-review-v2" data-agent-review-state={model.running ? 'running' : 'finished'} style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', minHeight: 0, background: '#fff', color: '#1f2328', fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif', fontSize: 14, WebkitFontSmoothing: 'antialiased' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 20px', borderBottom: '1px solid #eef0f2', flexShrink: 0 }}>
        <button type="button" className="agent-review-v2__trunk" onClick={onBack} title="Back to pull requests" style={{ display: 'inline-flex', alignItems: 'center', gap: 5, maxWidth: 140, fontSize: 12, color: '#454c54', border: '1px solid #e7e9ec', background: '#fafafa', borderRadius: 7, padding: '3px 9px', cursor: 'pointer', whiteSpace: 'nowrap', flexShrink: 0 }}><span style={{ color: PURPLE, display: 'inline-flex', flexShrink: 0 }}><BranchIcon /></span><span style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis' }}>{displayTrunkLabel}</span></button>
        <span style={{ color: '#c6cbd1', display: 'inline-flex', flexShrink: 0 }}><Chevron /></span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 10, fontWeight: 800, letterSpacing: '0.06em', padding: '3px 9px', borderRadius: 6, background: stateBg, color: stateColor, flexShrink: 0 }}>REVIEW ROUND</span>
        <span style={{ fontSize: 15, fontWeight: 700, color: '#17191c', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 180, flex: 1 }}>{pr.title}</span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 10.5, fontWeight: 700, color: '#1a7f37', background: '#dafbe1', borderRadius: 6, padding: '3px 8px', flexShrink: 0 }}><PullIcon />#{pr.remotePrNumber ?? '—'} {prStatus}</span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, flexShrink: 0 }} title={`Round ${roundNumber} budget · ${formatCents(round.cost_cents)} of ${formatCents(cap)}`}>
          <span style={{ width: 56, height: 5, background: '#eceef0', borderRadius: 999, overflow: 'hidden' }}><span style={{ display: 'block', width: `${spentPct}%`, height: '100%', background: model.running ? BLUE : PURPLE, borderRadius: 999 }} /></span>
          <span style={{ fontFamily: MONO, fontSize: 10.5, color: '#57606a' }}>{formatCents(round.cost_cents)}/{(cap / 100).toFixed(2)}</span>
        </span>
        {model.running ? <>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11, fontWeight: 700, color: BLUE, background: '#ddf4ff', borderRadius: 999, padding: '4px 11px', flexShrink: 0 }}><span style={{ width: 7, height: 7, borderRadius: '50%', background: BLUE, animation: 'pulseDot 1.6s infinite' }} />{round.status === 'QUEUED' ? 'queued' : round.message_gate_open === false ? 'finalizing' : 'running'} · {model.durationLabel ?? 'now'}</span>
          <button type="button" className="agent-review-v2__stop" onClick={() => onStopRound(round.id)} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, border: '1px solid rgba(207,34,46,0.35)', background: '#fff', borderRadius: 8, padding: '5px 11px', fontSize: 12, fontWeight: 600, color: '#cf222e', cursor: 'pointer', flexShrink: 0 }}><svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="2" /></svg>{round.status === 'QUEUED' ? 'Cancel' : 'Stop round'}</button>
        </> : (
          <button type="button" className="agent-review-v2__trigger" onClick={triggerNextRound} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, border: '1.5px dashed rgba(130,80,223,0.45)', background: 'transparent', borderRadius: 8, padding: '5px 11px', fontSize: 12, fontWeight: 600, color: PURPLE, cursor: 'pointer', flexShrink: 0 }}><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true"><path d="M12 5v14" /><path d="M5 12h14" /></svg>Trigger next round</button>
        )}
        <button type="button" className="agent-review-v2__panel-toggle" onClick={onTogglePanel} title="Toggle PR panel" aria-label="Toggle PR panel" style={{ width: 28, height: 28, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', border: 0, background: 'transparent', borderRadius: 7, color: '#6e7781', flexShrink: 0 }}><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2.2" /><path d="M15 4v16" /></svg></button>
      </div>

      <div className="agent-review-v2__feed" style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '18px 28px 10px' }}>
        <div style={{ maxWidth: 780, margin: '0 auto' }}>
          <RoundMilestone round={round} roundNumber={roundNumber} model={model} />
          <LeadReviewerCard round={round} model={model} />
          <PlanningMilestone round={round} model={model} />
          {model.investigators.map(row => <InvestigatorCard key={row.assignmentId} row={row} />)}
          <RoundTail round={round} roundNumber={roundNumber} model={model} />
        </div>
      </div>

      <div style={{ padding: '6px 28px 16px', flexShrink: 0 }}>
        <div style={{ maxWidth: 780, margin: '0 auto' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginBottom: 8 }}>
            <button type="button" className="agent-review-v2__recipient" onClick={() => { setMode('steer'); inputRef.current?.focus(); }} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 500, color: PURPLE, background: 'rgba(130,80,223,0.05)', border: '1px solid rgba(130,80,223,0.35)', borderRadius: 999, padding: '4px 12px', cursor: 'pointer' }}><svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 3.5c.6 3.6 2.9 5.9 6.5 6.5-3.6.6-5.9 2.9-6.5 6.5-.6-3.6-2.9-5.9-6.5-6.5 3.6-.6 5.9-2.9 6.5-6.5Z" /></svg>To · Review panel<Chevron down /></button>
            <span style={{ fontSize: 11.5, color: '#8b949e' }}>{mode === 'new-round' ? `queues round ${data.rounds.length + 1}` : model.running ? 'steers the running round' : `steers round ${roundNumber}`}</span>
            <span style={{ marginLeft: 'auto', fontSize: 11, color: '#a5abb2' }}>round {roundNumber}{model.running ? ' · running' : ''} · {formatCents(round.cost_cents)} / {formatCents(cap)}</span>
          </div>
          <div style={{ border: '1.5px solid #d0d7de', borderRadius: 14, background: '#fff', boxShadow: '0 1px 3px rgba(0,0,0,0.04)' }}>
            <textarea className="agent-review-v2__composer-input" ref={inputRef} rows={1} value={text} onChange={event => setText(event.target.value)} onKeyDown={onKeyDown} disabled={sending} placeholder={placeholder} aria-label={mode === 'new-round' ? `Describe round ${data.rounds.length + 1}` : `Steer round ${roundNumber}`} style={{ display: 'block', width: '100%', height: 32, resize: 'none', overflow: 'hidden', border: 0, outline: 0, padding: '12px 14px 4px', fontSize: 13.5, color: '#17191c', background: 'transparent', lineHeight: 'normal' }} />
            <div style={{ display: 'flex', alignItems: 'center', gap: 2, padding: '6px 10px 10px' }}>
              <button type="button" className="agent-review-v2__control" title="Add context" style={{ width: 26, height: 26, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', border: 0, background: 'transparent', borderRadius: 7, color: '#6e7781' }}><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg></button>
              <button type="button" className="agent-review-v2__control" title="Model picker" style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 12.5, color: '#454c54', padding: '4px 9px', border: 0, background: 'transparent', borderRadius: 7, cursor: 'pointer' }}>Claude Opus 4.8<span style={{ color: '#8b949e', display: 'inline-flex' }}><Chevron down /></span></button>
              <button type="button" className="agent-review-v2__control" title="Effort picker" style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 12.5, color: '#454c54', padding: '4px 9px', border: 0, background: 'transparent', borderRadius: 7, cursor: 'pointer' }}>Medium<span style={{ color: '#8b949e', display: 'inline-flex' }}><Chevron down /></span></button>
              <span style={{ flex: 1 }} />
              <span style={{ position: 'relative', display: 'inline-flex' }}>
                <button type="button" className="agent-review-v2__usage" onClick={() => setUsageOpen(open => !open)} title="Usage" aria-expanded={usageOpen} style={{ width: 26, height: 26, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', border: 0, background: 'transparent', borderRadius: '50%' }}><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="10" r="7.5" stroke="#e1e5e9" strokeWidth="2.5" /><circle cx="10" cy="10" r="7.5" stroke="#2da44e" strokeWidth="2.5" strokeLinecap="round" strokeDasharray="1.9 45.2" transform="rotate(-90 10 10)" /></svg></button>
                {usageOpen && <div style={{ position: 'absolute', bottom: 34, right: -40, width: 240, background: '#fff', border: '1px solid #e1e5e9', borderRadius: 11, boxShadow: '0 10px 30px rgba(0,0,0,0.12)', padding: '4px 0', zIndex: 5 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '9px 14px' }}><span style={{ fontSize: 12.5, color: '#57606a' }}>Plan</span><span style={{ flex: 1, height: 5, background: '#eceef0', borderRadius: 999, overflow: 'hidden' }}><span style={{ display: 'block', width: '4%', height: '100%', background: '#2da44e', borderRadius: 999 }} /></span><span style={{ fontSize: 12.5, fontWeight: 600, color: '#17191c', whiteSpace: 'nowrap' }}>4% used</span></div>
                  <div style={{ height: 1, background: '#f0f2f4' }} />
                  <div style={{ display: 'flex', alignItems: 'center', padding: '9px 14px' }}><span style={{ fontSize: 12.5, color: '#57606a' }}>Session</span><span style={{ marginLeft: 'auto', fontSize: 12.5, fontWeight: 600, color: '#17191c' }}>827 AI credits</span></div>
                </div>}
              </span>
              <button type="button" className="agent-review-v2__send" onClick={() => { void send(); }} disabled={text.trim().length === 0 || sending || (mode === 'steer' && !canSteer)} aria-label={mode === 'new-round' ? `Start round ${data.rounds.length + 1}` : 'Send'} style={{ width: 30, height: 30, borderRadius: '50%', border: 0, background: '#24292f', color: '#fff', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', marginLeft: 4 }}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 19V5M5 12l7-7 7 7" /></svg></button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export default function AgentColumn({ prId, workspaceId, trunkLabel, onBack, onTogglePanel }: {
  prId: string;
  workspaceId?: string | null;
  trunkLabel?: string;
  onBack: () => void;
  onTogglePanel?: () => void;
}) {
  const { bundle, refresh } = usePR(prId);
  const {
    data, latestRound, latestRoundNumber, startRound, sendRoundMessage, cancelRound,
  } = useAgentReviewState(bundle, refresh, undefined, workspaceId, true);

  if (data === null || latestRound === undefined || bundle === null) {
    return <div style={{ flex: 1, minWidth: 0, display: 'grid', placeItems: 'center', minHeight: 0, background: '#fff', color: '#8b949e', fontSize: 13 }}>No agent review yet.</div>;
  }
  return (
    <AgentReviewConversation
      bundle={bundle}
      data={data}
      round={latestRound}
      roundNumber={latestRoundNumber}
      trunkLabel={trunkLabel}
      onBack={onBack}
      onTogglePanel={onTogglePanel}
      onStopRound={cancelRound}
      onStartRound={startRound}
      onSendMessage={sendRoundMessage}
    />
  );
}
