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
import { Fragment, useId, useState, type CSSProperties, type ReactNode } from 'react';
import { CheckIcon, ChevronRightIcon } from './TaskBrainDesignIcons';
import '../css/plan-pipeline-card.css';

export type Risk = 'low' | 'medium' | 'high';

export interface PlanStep {
  /** 1-based, as authored by the agent — shown verbatim on the step card. */
  n: number;
  /** One-line label for the pipeline card. */
  short: string;
  /** Longer rationale shown when the step is expanded. */
  detail?: string;
  /** Every path attached to the authored step. */
  files?: string[];
  /** Per-step risk, as recorded by the planning agent. */
  risk: 'low' | 'med' | 'high' | 'opt';
}

export interface PlanPolicy {
  minApprovals: 0 | 1 | 2;
  autoApprove: boolean;
  autoMerge: boolean;
}

export interface Plan {
  rev: number;
  /** 'draft' — still being written/revised, not yet finalized; Approve stays
   *  disabled (the card explains why). 'ready' — finalized, awaiting the
   *  user's approval. */
  status: 'draft' | 'ready' | 'running' | 'approved';
  /** ONE concise sentence: what + why. Backtick-wrapped spans render as
   *  inline mono chips (e.g. "…new param `maxSize`."). */
  goal: string;
  risk: Risk;
  /** Display label for effort — capitalised into the "… effort" pill. */
  effort: string;
  confidence: Risk;
  /** Background & caveats (folded, default closed). */
  why?: string[];
  /** How steps are checked. */
  validation?: string;
  /** Explicit scope boundaries recorded by the planner. */
  outOfScope?: string[];
  /** Whether the agent may push autonomously after validation. */
  pushStrategy: 'autonomous' | 'await_approval';
  /** Why it's worth doing (folded, default closed). */
  value?: string;
  steps: PlanStep[];
  policy: PlanPolicy;
}

export interface PipelinePlanCardProps {
  plan: Plan;
  /** ISO 8601 approval time — shown on the footer once the plan is approved. */
  approvedAt?: string;
  /** Fired on any segmented-control change; persist immediately — the policy
   *  is authoritative even before approval. Omit to render the toolbar
   *  read-only (values shown, not editable). */
  onPolicyChange?: (next: PlanPolicy) => void;
  /** Freeze the plan and start development. Omit to disable the Approve button. */
  onApprove?: () => void;
  /** Send typed revision feedback; the plan stays paused until sent. Omit to
   *  hide the Request-revision affordance. */
  onRequestRevision?: (text: string) => void;
}

const STATUS_LABEL: Record<Plan['status'], string> = {
  draft: 'Plan drafting',
  ready: 'Plan ready',
  running: 'Brain reviewing plan',
  approved: 'Plan approved',
};

const STATUS_DOT_COLOR: Record<Plan['status'], string> = {
  draft: '#9a6700',
  ready: '#2da44e',
  running: '#2da44e',
  approved: '#2da44e',
};

const MONO = "'SF Mono', Menlo, ui-monospace, monospace";

function cap(word: string): string {
  return word.charAt(0).toUpperCase() + word.slice(1);
}

/** ISO 8601 → local "YYYY-MM-DD HH:mm:ss"; echoes the input if unparseable. */
function fmtApprovedAt(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

const pillStyle: CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 10.5, fontWeight: 600,
  color: '#454c54', background: '#f6f8fa', border: '1px solid #e7e9ec', borderRadius: 999, padding: '2px 9px',
};

const goalChipStyle: CSSProperties = {
  fontFamily: MONO, fontSize: 12, color: '#57606a', background: '#f6f8fa',
  border: '1px solid #eceef0', borderRadius: 5, padding: '1px 6px',
};

const foldPanelStyle: CSSProperties = {
  marginTop: 11, padding: '12px 14px', background: '#f6f8fa', border: '1px solid #eceef0',
  borderRadius: 9, fontSize: 13, color: '#57606a', lineHeight: 1.6,
};

const segContainerStyle: CSSProperties = {
  display: 'inline-flex', border: '1px solid #d5dbe1', background: '#fff', borderRadius: 8, overflow: 'hidden',
};

/** Split a goal sentence into text + backtick-delimited mono chips. */
function renderGoal(text: string): ReactNode[] {
  return text.split(/`([^`]+)`/g).map((seg, i) =>
    i % 2 === 1
      ? <span key={i} style={goalChipStyle}>{seg}</span>
      : <Fragment key={i}>{seg}</Fragment>);
}

function SendIcon() {
  return (
    <svg
      width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" aria-hidden focusable="false"
    >
      <path d="M22 2 11 13" />
      <path d="M22 2 15 22l-4-9-9-4z" />
    </svg>
  );
}

const stepRiskLabel: Record<PlanStep['risk'], string> = {
  low: 'Low risk', med: 'Medium risk', high: 'High risk', opt: 'Optional',
};

function StepCard({ step }: { step: PlanStep }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const detail = step.detail?.trim();
  const files = step.files?.filter(path => path.trim() !== '') ?? [];
  const riskLabel = stepRiskLabel[step.risk];
  return (
    <li style={{
      border: '1px solid #e7e9ec', borderRadius: 9, background: '#fff', overflow: 'hidden',
    }}>
      <button
        type="button"
        className="ppc-step-toggle"
        aria-expanded={open}
        aria-controls={panelId}
        aria-label={`${open ? 'Collapse' : 'Expand'} step ${step.n}: ${step.short}. ${riskLabel}`}
        onClick={() => setOpen(value => !value)}
        style={{
          width: '100%', display: 'flex', alignItems: 'center', gap: 9, padding: '10px 12px',
          color: '#1f2328', background: '#fff', border: 'none', cursor: 'pointer', textAlign: 'left',
        }}
      >
        <span style={{
          width: 21, height: 21, borderRadius: 6, background: '#24292f', color: '#fff', fontFamily: MONO,
          fontSize: 10.5, fontWeight: 700, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
        }}>
          {step.n}
        </span>
        <span className="ppc-step-title" style={{ flex: 1, minWidth: 0, fontSize: 12.5, fontWeight: 600, lineHeight: 1.45, overflowWrap: 'anywhere' }}>
          {step.short}
        </span>
        <span style={pillStyle}>{riskLabel}</span>
        <span aria-hidden style={{ display: 'inline-flex', transform: open ? 'rotate(90deg)' : undefined, transition: 'transform .15s' }}>
          <ChevronRightIcon size={10} />
        </span>
      </button>
      {open && (
        <div id={panelId} style={{ padding: '0 12px 12px 42px', borderTop: '1px solid #f0f2f4', color: '#57606a', fontSize: 12, lineHeight: 1.55 }}>
          <div style={{ marginTop: 10 }}>
            <strong style={{ color: '#1f2328' }}>Rationale</strong>
            <div>{detail !== undefined && detail !== '' ? detail : 'No additional rationale recorded.'}</div>
          </div>
          <div style={{ marginTop: 9 }}>
            <strong style={{ color: '#1f2328' }}>Files</strong>
            {files.length === 0 ? (
              <div>No files specified.</div>
            ) : (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 4 }}>
                {files.map((path, index) => (
                  <span key={`${path}-${index}`} style={{
                    fontFamily: MONO, fontSize: 10.5, color: '#57606a', background: '#f6f8fa',
                    border: '1px solid #eceef0', borderRadius: 5, padding: '2px 6px', overflowWrap: 'anywhere',
                  }}>
                    {path}
                  </span>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </li>
  );
}

function SummarySection({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div style={{ minWidth: 0, padding: '11px 12px', background: '#f6f8fa', border: '1px solid #eceef0', borderRadius: 9 }}>
      <div style={{ fontSize: 10, fontWeight: 700, letterSpacing: '0.05em', color: '#57606a', textTransform: 'uppercase', marginBottom: 5 }}>
        {label}
      </div>
      <div style={{ fontSize: 12, color: '#1f2328', lineHeight: 1.5, overflowWrap: 'anywhere' }}>{children}</div>
    </div>
  );
}

function FoldToggle({ label, open, onToggle }: { label: string; open: boolean; onToggle: () => void }) {
  return (
    <button
      type="button" className="ppc-fold" onClick={onToggle} aria-expanded={open}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer', color: '#59636e',
        fontSize: 12.5, fontWeight: 600, background: 'none', border: 'none', padding: 0,
      }}
    >
      <span style={{ display: 'inline-flex', transform: open ? 'rotate(90deg)' : 'rotate(0deg)', transition: 'transform .15s' }}>
        <ChevronRightIcon size={10} />
      </span>
      {label}
    </button>
  );
}

function Segmented<T extends string | number | boolean>({
  label, options, value, onChange, minCellWidth, fontSize, disabled,
}: {
  label: string;
  options: Array<{ value: T; label: string }>;
  value: T;
  onChange?: (next: T) => void;
  minCellWidth: number;
  fontSize: number;
  disabled?: boolean;
}) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <span style={{ fontSize: 11.5, color: '#57606a', fontWeight: 600 }}>{label}</span>
      <div style={segContainerStyle}>
        {options.map((opt, i) => {
          const selected = opt.value === value;
          return (
            <button
              key={String(opt.value)} type="button"
              className={`ppc-seg-cell${selected ? ' is-selected' : ''}`}
              aria-pressed={selected}
              disabled={disabled === true}
              onClick={() => onChange?.(opt.value)}
              style={{
                minWidth: minCellWidth, textAlign: 'center', padding: '4px 0', fontSize, fontWeight: 600,
                cursor: disabled === true ? 'default' : 'pointer', border: 'none',
                borderRight: i < options.length - 1 ? '1px solid #eceef0' : 'none',
                color: selected ? '#fff' : '#57606a', background: selected ? '#24292f' : 'transparent',
              }}
            >
              {opt.label}
            </button>
          );
        })}
      </div>
    </div>
  );
}

export function PipelinePlanCard({ plan, approvedAt, onPolicyChange, onApprove, onRequestRevision }: PipelinePlanCardProps) {
  const [whyOpen, setWhyOpen] = useState(false);
  const [valueOpen, setValueOpen] = useState(false);
  const [revOpen, setRevOpen] = useState(false);
  const [revText, setRevText] = useState('');

  const setPolicy = (patch: Partial<PlanPolicy>) => onPolicyChange?.({ ...plan.policy, ...patch });
  const policyEditable = onPolicyChange !== undefined;

  const sendRevision = () => {
    const text = revText.trim();
    if (text === '') return;
    onRequestRevision?.(text);
    setRevText('');
    setRevOpen(false);
  };

  const hasWhy = plan.why !== undefined && plan.why.length > 0;
  const hasValidation = plan.validation !== undefined && plan.validation !== '';
  const hasOutOfScope = plan.outOfScope !== undefined && plan.outOfScope.length > 0;
  const hasValue = plan.value !== undefined && plan.value !== '';
  const approved = plan.status === 'approved';

  return (
    <div
      className="plan-pipeline-card"
      style={{
        width: 860, maxWidth: '100%', background: '#fff', border: '1px solid #d5dbe1', borderRadius: 14,
        boxShadow: '0 12px 36px rgba(0,0,0,0.07), 0 1px 2px rgba(0,0,0,0.05)', overflow: 'hidden',
      }}
    >
      {/* goal band */}
      <div style={{ padding: '15px 20px', borderBottom: '1px solid #eef0f2' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9, marginBottom: 10 }}>
          <span style={{
            width: 9, height: 9, borderRadius: '50%', background: STATUS_DOT_COLOR[plan.status],
            boxShadow: `0 0 0 3px ${plan.status === 'draft' ? 'rgba(212,167,44,0.2)' : 'rgba(45,164,78,0.15)'}`,
          }} />
          <span style={{ fontSize: 13.5, fontWeight: 700, color: '#17191c' }}>{STATUS_LABEL[plan.status]}</span>
          <span style={{ fontSize: 10, fontWeight: 700, letterSpacing: '0.04em', color: '#57606a', background: '#f0f2f4', borderRadius: 5, padding: '2px 7px' }}>
            REV {plan.rev}
          </span>
          <span style={{ flex: 1 }} />
          <span style={pillStyle}>{cap(plan.risk)} risk</span>
          <span style={pillStyle}>{cap(plan.effort)} effort</span>
          <span style={pillStyle}>{cap(plan.confidence)} confidence</span>
        </div>
        <div style={{ fontSize: 13, fontWeight: 500, color: '#17191c', lineHeight: 1.5, textWrap: 'pretty' } as CSSProperties}>
          {renderGoal(plan.goal)}
        </div>
      </div>

      {/* authored plan */}
      <div style={{ padding: '16px 20px 6px' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 9 }}>
          <span style={{ fontSize: 12.5, fontWeight: 700, color: '#17191c' }}>Steps</span>
          <span style={{ fontSize: 10.5, color: '#8b949e' }}>{plan.steps.length} {plan.steps.length === 1 ? 'step' : 'steps'}</span>
        </div>
        <ol style={{ listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 8, padding: 0, margin: 0 }}>
          {plan.steps.map((step, index) => <StepCard key={`${step.n}-${index}`} step={step} />)}
        </ol>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 9, marginTop: 14 }}>
          {hasValidation && <SummarySection label="Validation">{plan.validation}</SummarySection>}
          {hasOutOfScope && (
            <SummarySection label="Out of scope">
              <ul style={{ paddingLeft: 17, margin: 0 }}>
                {plan.outOfScope?.map((item, index) => <li key={`${item}-${index}`}>{item}</li>)}
              </ul>
            </SummarySection>
          )}
          <SummarySection label="Push scope">
            {plan.pushStrategy === 'autonomous' ? 'Push autonomously after validation.' : 'Wait for approval before pushing.'}
          </SummarySection>
        </div>

        {(hasWhy || hasValue) && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginTop: 14, paddingTop: 12, borderTop: '1px solid #f0f2f4' }}>
            {hasWhy && <FoldToggle label="Why this plan" open={whyOpen} onToggle={() => setWhyOpen(v => !v)} />}
            {hasValue && <FoldToggle label="Value" open={valueOpen} onToggle={() => setValueOpen(v => !v)} />}
          </div>
        )}
        {hasWhy && whyOpen && (
          <div style={{ ...foldPanelStyle, display: 'flex', flexDirection: 'column', gap: 9 }}>
            {plan.why?.map((para, i) => <div key={i}>{para}</div>)}
          </div>
        )}
        {hasValue && valueOpen && <div style={foldPanelStyle}>{plan.value}</div>}
      </div>

      {/* policy toolbar */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 18, flexWrap: 'wrap', padding: '12px 20px',
        borderTop: '1px solid #eef0f2', background: '#fafbfc', marginTop: 10,
      }}>
        <span style={{ fontSize: 9.5, fontWeight: 700, letterSpacing: '0.07em', color: '#a5abb2' }}>POLICY</span>
        <Segmented<0 | 1 | 2>
          label="Min approvals" minCellWidth={32} fontSize={12} disabled={!policyEditable} value={plan.policy.minApprovals}
          options={[{ value: 0, label: '0' }, { value: 1, label: '1' }, { value: 2, label: '2' }]}
          onChange={v => setPolicy({ minApprovals: v })}
        />
        <Segmented<boolean>
          label="Auto-approve" minCellWidth={40} fontSize={11.5} disabled={!policyEditable} value={plan.policy.autoApprove}
          options={[{ value: false, label: 'Off' }, { value: true, label: 'On' }]}
          onChange={v => setPolicy({ autoApprove: v })}
        />
        <Segmented<boolean>
          label="Auto-merge" minCellWidth={40} fontSize={11.5} disabled={!policyEditable} value={plan.policy.autoMerge}
          options={[{ value: false, label: 'Off' }, { value: true, label: 'On' }]}
          onChange={v => setPolicy({ autoMerge: v })}
        />
      </div>

      {/* footer */}
      <div style={{ padding: '12px 20px 15px', borderTop: '1px solid #eef0f2' }}>
        {approved ? (
          <div style={{ fontSize: 13, fontWeight: 500, color: '#57606a' }}>
            Plan approved{approvedAt !== undefined ? ` at ${fmtApprovedAt(approvedAt)}` : ' — development under way.'}
          </div>
        ) : !revOpen ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
            {plan.status === 'draft' && onApprove === undefined && (
              <div style={{
                fontSize: 12, color: '#9a6700', background: '#fff8e0', border: '1px solid rgba(212,167,44,.4)',
                borderRadius: 7, padding: '7px 10px',
              }}>
                Still drafting — Approve is disabled until this plan is finalized. Reply above or use Request
                revision to move it forward.
              </div>
            )}
            {plan.status === 'running' && onApprove === undefined && (
              <div style={{
                fontSize: 12, color: '#57606a', background: '#f6f8fa', border: '1px solid #d5dbe1',
                borderRadius: 7, padding: '7px 10px',
              }}>
                Plan finalized — Brain self-review is in progress. Approval unlocks when self-review finishes.
              </div>
            )}
            <div style={{ display: 'flex', gap: 9 }}>
              <button
                type="button" className="ppc-primary" onClick={onApprove} disabled={onApprove === undefined}
                style={{
                  flex: 1, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 7, padding: 10,
                  border: '1px solid #1f2328', background: '#24292f', borderRadius: 9, fontSize: 13, fontWeight: 600, color: '#fff',
                  cursor: onApprove === undefined ? 'not-allowed' : 'pointer', opacity: onApprove === undefined ? 0.5 : 1,
                }}
              >
                <CheckIcon /> Approve &amp; start dev
              </button>
              {onRequestRevision !== undefined && (
                <button
                  type="button" className="ppc-secondary" onClick={() => setRevOpen(true)}
                  style={{ padding: '10px 16px', border: '1px solid #d5dbe1', background: '#fff', borderRadius: 9, fontSize: 13, fontWeight: 600, color: '#57606a', cursor: 'pointer' }}
                >
                  Request revision
                </button>
              )}
            </div>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
            <span style={{ fontSize: 12.5, fontWeight: 600, color: '#17191c' }}>Request revision</span>
            <textarea
              className="ppc-rev-textarea"
              value={revText}
              onChange={e => setRevText(e.target.value)}
              placeholder="What's wrong, what's actually fine to keep, and anything you're unsure about…"
              style={{
                width: '100%', minHeight: 82, resize: 'vertical', border: '1px solid #d5dbe1', borderRadius: 9,
                padding: '9px 11px', fontSize: 12.5, color: '#1f2328', lineHeight: 1.55, outline: 'none', background: '#fff',
              }}
            />
            <div style={{ display: 'flex', gap: 9, justifyContent: 'flex-end' }}>
              <button
                type="button" className="ppc-secondary" onClick={() => { setRevOpen(false); setRevText(''); }}
                style={{ padding: '8px 14px', border: '1px solid #d5dbe1', background: '#fff', borderRadius: 9, fontSize: 12.5, fontWeight: 600, color: '#57606a', cursor: 'pointer' }}
              >
                Cancel
              </button>
              <button
                type="button" className="ppc-primary" onClick={sendRevision} disabled={revText.trim() === ''}
                style={{
                  display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 15px', border: '1px solid #1f2328',
                  background: '#24292f', borderRadius: 9, fontSize: 12.5, fontWeight: 600, color: '#fff',
                  cursor: revText.trim() === '' ? 'not-allowed' : 'pointer', opacity: revText.trim() === '' ? 0.5 : 1,
                }}
              >
                <SendIcon /> Send revision request
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
