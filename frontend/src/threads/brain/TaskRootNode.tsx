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
import type { PlanCardDto, PlanStepDto } from '../../types/brainView';
import { MarkdownProse } from '../MarkdownProse';
import { extractSeedChips } from './seedChips';

/** {@code estimatedComplexity} values that count as "small effort" for
 *  auto-merge eligibility. See the same constant's doc in the backend's
 *  TaskService for why this is a synonym allow-list, not the strict
 *  small/medium/large the type declares. */
const SMALL_EFFORT = new Set(['trivial', 'small', 'low']);

/**
 * The task root node (M10 Part A): the planning seed + the typed plan card +
 * the review bar. The seed renders collapsed as scannable chips (raw markdown
 * one click away — render-only, no backend change); the plan card is driven
 * by the structured `PlanCardDto`; the lone Approve button becomes a review
 * bar with a scope guard + what-approval-triggers note. Per-step `risk`,
 * `files`, and `out_of_scope` arrive with the typed plan schema (P7) — until
 * then steps degrade to a title + detail derived from the action prose.
 */
export function TaskRootNode({
  plan, seed, autoApprove, autoMerge, autoConfidenceHigh, onApprove, onEdit, onRequestRevision, onCommentStep,
  onHoldAuto, onToggleAutoApprove, onToggleAutoMerge,
}: {
  plan: PlanCardDto;
  /** The trunk-handoff prose, when available. Omit to hide the seed block. */
  seed?: string;
  autoApprove?: boolean;
  autoMerge?: boolean;
  /** True when the plan's confidence is high — gates auto-approve (A4.2). */
  autoConfidenceHigh?: boolean;
  onApprove?: () => void;
  onEdit?: () => void;
  onRequestRevision?: () => void;
  onCommentStep?: (ordinal: number) => void;
  onHoldAuto?: () => void;
  onToggleAutoApprove?: () => void;
  onToggleAutoMerge?: () => void;
}) {
  return (
    <div className="root-node">
      {seed !== undefined && seed.trim().length > 0 && <PlanningSeed seed={seed} />}
      <PlanCard
        plan={plan}
        autoApprove={autoApprove}
        autoMerge={autoMerge}
        autoConfidenceHigh={autoConfidenceHigh}
        onApprove={onApprove}
        onEdit={onEdit}
        onRequestRevision={onRequestRevision}
        onCommentStep={onCommentStep}
        onHoldAuto={onHoldAuto}
        onToggleAutoApprove={onToggleAutoApprove}
        onToggleAutoMerge={onToggleAutoMerge}
      />
    </div>
  );
}

/** The collapsed planning seed: chips above the fold, full rendered markdown
 *  on expand. Exported so the brain view can anchor it at the top of the
 *  conversation while the plan card floats to the bottom. */
export function PlanningSeed({ seed }: { seed: string }) {
  const [open, setOpen] = useState(false);
  const chips = extractSeedChips(seed);
  const facts = [chips.type, chips.validate, chips.push, chips.outOfScope].filter(Boolean).length;
  return (
    <div className={`seed${open ? ' open' : ''}`}>
      <button type="button" className="seed__hd" onClick={() => setOpen(o => !o)} aria-expanded={open}>
        <span className="seed__ic" aria-hidden>◆</span>
        <span className="seed__lbl">Planning seed</span>
        <span className="seed__meta">parsed from trunk{facts > 0 ? ` · ${facts} facts` : ''}</span>
        <span className="seed__chev" aria-hidden>›</span>
      </button>
      <div className="seed__chips">
        {chips.type !== undefined && <Chip k="Type" v={chips.type} />}
        {chips.validate !== undefined && <Chip k="Validate" v={chips.validate} mono />}
        {chips.push !== undefined && <Chip k="Push" v={chips.push} />}
        {chips.outOfScope !== undefined && <Chip k="Out of scope" v={chips.outOfScope} warn />}
      </div>
      {open && <div className="seed__full"><MarkdownProse text={seed} /></div>}
    </div>
  );
}

function Chip({ k, v, warn, mono }: { k: string; v: string; warn?: boolean; mono?: boolean }) {
  return (
    <span className={`seed-chip${warn === true ? ' warn' : ''}`}>
      <span className="seed-chip__k">{k}</span>
      <span className={`seed-chip__v${mono === true ? ' mono' : ''}`}>{v}</span>
    </span>
  );
}

/** The typed plan card + review bar. Exported so the brain view can render it
 *  at the bottom of the planning conversation (where the eye lands) rather
 *  than pinned above the feed. */
export function PlanCard({
  plan, autoApprove, autoMerge, autoConfidenceHigh, approvedAt, onApprove, onEdit, onRequestRevision, onCommentStep,
  onHoldAuto, onToggleAutoApprove, onToggleAutoMerge, minApprovals, onSetMinApprovals,
}: {
  plan: PlanCardDto;
  autoApprove?: boolean;
  autoMerge?: boolean;
  autoConfidenceHigh?: boolean;
  /** ISO 8601 approval time — shown on the locked plan. */
  approvedAt?: string;
  onApprove?: () => void;
  onEdit?: () => void;
  onRequestRevision?: () => void;
  onCommentStep?: (ordinal: number) => void;
  onHoldAuto?: () => void;
  /** Toggles auto-approve. When provided, an Auto-approve switch shows in the
   *  card header — a high-confidence plan then starts development without a
   *  click. Omit to hide the switch. */
  onToggleAutoApprove?: () => void;
  /** Toggles auto-merge. When provided, an Auto-merge switch shows beside
   *  Auto-approve — enabled only while the plan reads risk=low/effort=small;
   *  turning it on skips every remaining gate through the final merge. The
   *  plan itself always still waits for your explicit approval. Omit to hide
   *  the switch. */
  onToggleAutoMerge?: () => void;
  /** Minimum write-permission approvals a shipped PR needs before it's treated
   *  as merge-ready (0/1/2). When onSetMinApprovals is provided, a selector
   *  shows in the card header. */
  minApprovals?: number;
  onSetMinApprovals?: (n: number) => void;
}) {
  const goal = plan.goal !== undefined && plan.goal.trim() !== '' ? plan.goal : plan.understandingSummary;
  const confidence = plan.signals.confidence ?? confidenceFromRisk(plan.signals.riskLevel);
  const locked = plan.state === 'locked';
  const awaiting = plan.state === 'awaiting';
  // estimatedComplexity is free text the brain writes, not a strict enum — it
  // drifts onto risk's low/medium/high vocabulary as often as small/medium/
  // large. Mirrors the backend's TaskService.SMALL_EFFORT allow-list.
  const autoMergeEligible = plan.signals.riskLevel === 'low'
    && SMALL_EFFORT.has(String(plan.signals.estimatedComplexity).toLowerCase());

  return (
    <div className={awaiting ? 'plan-card plan-card--awaiting' : 'plan-card'}>
      <div className="plan-card__hd">
        <span className="plan-card__ic" aria-hidden>✦</span>
        <span className="plan-card__t">Execution plan</span>
        {plan.revisionCount > 0 && <span className="plan-card__rev">rev {plan.revisionCount}</span>}
        {onSetMinApprovals !== undefined && (
          <div
            className="plan-approvals"
            title="Minimum approvals from reviewers with write permission before the PR is treated as merge-ready. 0 = no approval required."
          >
            <span className="plan-approvals__lbl">Min approvals</span>
            <div className="plan-approvals__opts" role="group" aria-label="Minimum approvals">
              {[0, 1, 2].map(n => (
                <button
                  key={n}
                  type="button"
                  className={(minApprovals ?? 0) === n ? 'plan-approvals__opt active' : 'plan-approvals__opt'}
                  aria-pressed={(minApprovals ?? 0) === n}
                  onClick={() => onSetMinApprovals(n)}
                >{n}</button>
              ))}
            </div>
          </div>
        )}
        {onToggleAutoApprove !== undefined && (
          <label className="plan-auto" title="When on, downstream push / PR gates approve automatically. The plan itself always waits for your explicit approval.">
            <span className="plan-auto__lbl">Auto-approve</span>
            <span className="plan-auto__sw">
              <input type="checkbox" checked={autoApprove === true} onChange={onToggleAutoApprove} />
              <span className="plan-auto__track"><span className="plan-auto__knob" /></span>
            </span>
          </label>
        )}
        {onToggleAutoMerge !== undefined && (
          <label
            className={autoMergeEligible ? 'plan-auto' : 'plan-auto plan-auto--disabled'}
            title={autoMergeEligible
              ? 'When on, every remaining gate — including the final merge — approves automatically. The plan itself always waits for your explicit approval.'
              : 'Only available for a low-risk, small-effort plan.'}
          >
            <span className="plan-auto__lbl">Auto-merge</span>
            <span className="plan-auto__sw">
              <input
                type="checkbox"
                checked={autoMerge === true}
                disabled={!autoMergeEligible}
                onChange={autoMergeEligible ? onToggleAutoMerge : undefined}
              />
              <span className="plan-auto__track"><span className="plan-auto__knob" /></span>
            </span>
          </label>
        )}
      </div>
      <div className="plan-card__body">
        <div className="plan-goal">
          <span className="plan-goal__i" aria-hidden>🎯</span>
          <span className="plan-goal__t">
            <MarkdownProse text={goal} variant="card" />
            {plan.intentSummary.trim().length > 0 && (
              <span className="plan-goal__approach"><MarkdownProse text={plan.intentSummary} variant="card" /></span>
            )}
          </span>
        </div>

        {plan.steps.length > 0 && (
          <>
            <div className="plan-seclbl">Steps <span className="cnt">· {plan.steps.length}</span></div>
            <div className="plan-steps">
              {plan.steps.map(s => (
                <PlanStep key={s.ordinal} step={s} overallRisk={plan.signals.riskLevel} onComment={onCommentStep} />
              ))}
            </div>
          </>
        )}

        <div className="plan-mini-grid">
          {plan.validationStrategy.trim().length > 0 && (
            <div className="plan-mini">
              <div className="plan-mini__h">Validation</div>
              <div className="plan-mini__tx"><MarkdownProse text={plan.validationStrategy} variant="card" /></div>
            </div>
          )}
          {plan.outOfScope !== undefined && plan.outOfScope.length > 0 && (
            <div className="plan-mini">
              <div className="plan-mini__h">Out of scope</div>
              <ul className="plan-mini__oos">
                {plan.outOfScope.map((o, i) => <li key={i}>{o}</li>)}
              </ul>
            </div>
          )}
        </div>

        <div className="plan-signals">
          <Signal label="Risk" value={cap(plan.signals.riskLevel)} tone={plan.signals.riskLevel === 'low' ? 'g' : plan.signals.riskLevel === 'high' ? 'r' : 'a'} />
          <Signal label="Effort" value={cap(plan.signals.estimatedComplexity)} tone="a" />
          <Signal label="Value" value={plan.signals.expectedGain} tone="g" />
        </div>
        <div className={`plan-conf plan-conf--${confidence}`}>
          <span className="plan-conf__b">{cap(confidence)} confidence</span>
        </div>

        {locked
          ? <div className="review-locked">Plan approved{approvedAt !== undefined ? ` at ${fmtApprovedAt(approvedAt)}` : ' — development under way.'}</div>
          : <ReviewBar
              plan={plan}
              awaiting={awaiting}
              onApprove={onApprove}
              onEdit={onEdit}
              onRequestRevision={onRequestRevision}
            />}
      </div>
    </div>
  );
}

function PlanStep({ step, overallRisk, onComment }: {
  step: PlanStepDto;
  overallRisk: 'low' | 'medium' | 'high';
  onComment?: (ordinal: number) => void;
}) {
  const [open, setOpen] = useState(step.ordinal === 1);
  // The typed schema (P7) carries a title (action), a detail (rationale), and
  // file chips. Plans recorded before it have only `action` — derive a short
  // title from it and use the full action as the detail.
  const typed = step.detail !== undefined || (step.files !== undefined && step.files.length > 0);
  const title = typed ? step.action : firstClause(step.action);
  const detail = step.detail ?? (typed ? undefined : step.action);
  const hasDetail = detail !== undefined && detail.trim() !== title.trim();
  // Per-step risk, falling back to the overall level (the risk values
  // low/med/high/opt double as the CSS pill class).
  const riskRaw = step.risk ?? (overallRisk === 'medium' ? 'med' : overallRisk);
  return (
    <div className={`plan-step${open ? ' open' : ''}`}>
      <button type="button" className="plan-step__hd" onClick={() => setOpen(o => !o)} aria-expanded={open}>
        <span className="plan-step__ord">{step.ordinal}</span>
        {/* Plain text, not markdown: a step title is a one-line action; running
            it through MarkdownProse drops generic-type tokens like
            Consumer<ThreadTurn> (parsed as an unknown HTML tag). */}
        <span className="plan-step__title">{title}</span>
        <span className="plan-step__right">
          <span className={`risk ${riskRaw}`}>{riskRaw}</span>
          <span className="plan-step__chev" aria-hidden>›</span>
        </span>
      </button>
      {open && (
        <div className="plan-step__detail">
          {hasDetail && <MarkdownProse text={detail} variant="card" />}
          {step.files !== undefined && step.files.length > 0 && (
            <div className="plan-step__files">
              {step.files.map((f, i) => <span className="fref" key={i}>{f}</span>)}
            </div>
          )}
          {onComment !== undefined && (
            <button type="button" className="plan-step__cmt" onClick={() => onComment(step.ordinal)}>💬 Comment on this step</button>
          )}
        </div>
      )}
    </div>
  );
}

function ReviewBar({ plan, awaiting, onApprove, onEdit, onRequestRevision }: {
  plan: PlanCardDto;
  awaiting: boolean;
  onApprove?: () => void;
  onEdit?: () => void;
  onRequestRevision?: () => void;
}) {
  const pushLabel = plan.pushStrategy === 'autonomous' ? 'autonomous' : 'await approval';
  // The plan is never auto-approved — it always waits for the user's explicit
  // click before development starts, regardless of the auto-approve toggle
  // (which only governs the downstream push / PR gates).
  return (
    <div className="review-bar">
      <div className="scope-guard">
        <span className="sg"><span className="dot g" />{plan.steps.length} steps in scope</span>
        <span className="sg"><span className="dot b" />push: {pushLabel}</span>
      </div>
      <div className="trigger-note">Approving freezes this plan and activates <span className="flow">Development → Review → Push</span>.</div>
      <div className="actions-row">
        <button type="button" className="rb-btn rb-btn--primary" onClick={onApprove} disabled={!awaiting || onApprove === undefined}>✓ Approve &amp; start dev</button>
        {onEdit !== undefined && <button type="button" className="rb-btn rb-btn--ghost" onClick={onEdit}>Edit plan</button>}
        {onRequestRevision !== undefined && <button type="button" className="rb-btn rb-btn--ghost rb-btn--amber" onClick={onRequestRevision}>Request revision</button>}
      </div>
    </div>
  );
}

function Signal({ label, value, tone }: { label: string; value: string; tone: 'g' | 'a' | 'r' }) {
  return (
    <div className="plan-sig">
      <div className="plan-sig__l">{label}</div>
      <div className={`plan-sig__v plan-sig__v--${tone}`}>{value}</div>
    </div>
  );
}

function confidenceFromRisk(risk: 'low' | 'medium' | 'high'): 'low' | 'medium' | 'high' {
  return risk === 'low' ? 'high' : risk === 'high' ? 'low' : 'medium';
}

function cap(s: string): string {
  return s.length === 0 ? s : s[0].toUpperCase() + s.slice(1);
}

/** ISO 8601 → local "YYYY-MM-DD HH:mm:ss". */
function fmtApprovedAt(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/** The first sentence / clause of a step action, as a short title. */
function firstClause(action: string): string {
  const stripped = action.trim();
  const stop = stripped.search(/[:.](\s|$)/);
  const head = stop > 0 ? stripped.slice(0, stop) : stripped;
  const words = head.split(/\s+/);
  return words.length > 10 ? `${words.slice(0, 10).join(' ')}…` : head;
}
