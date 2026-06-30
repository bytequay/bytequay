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
import type { PlanCardDto, PlanStepDto } from '../../types/brainView';
import { MarkdownProse } from '../MarkdownProse';
import { extractSeedChips } from './seedChips';

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
  plan, seed, autoApprove, autoConfidenceHigh, onApprove, onEdit, onRequestRevision, onCommentStep, onHoldAuto,
}: {
  plan: PlanCardDto;
  /** The trunk-handoff prose, when available. Omit to hide the seed block. */
  seed?: string;
  autoApprove?: boolean;
  /** True when the plan's confidence is high — gates auto-approve (A4.2). */
  autoConfidenceHigh?: boolean;
  onApprove?: () => void;
  onEdit?: () => void;
  onRequestRevision?: () => void;
  onCommentStep?: (ordinal: number) => void;
  onHoldAuto?: () => void;
}) {
  return (
    <div className="root-node">
      {seed !== undefined && seed.trim().length > 0 && <SeedBlock seed={seed} />}
      <PlanCard
        plan={plan}
        autoApprove={autoApprove}
        autoConfidenceHigh={autoConfidenceHigh}
        onApprove={onApprove}
        onEdit={onEdit}
        onRequestRevision={onRequestRevision}
        onCommentStep={onCommentStep}
        onHoldAuto={onHoldAuto}
      />
    </div>
  );
}

/** The collapsed seed block: chips above the fold, full rendered markdown on
 *  expand. */
function SeedBlock({ seed }: { seed: string }) {
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

/** The typed plan card. */
function PlanCard({
  plan, autoApprove, autoConfidenceHigh, onApprove, onEdit, onRequestRevision, onCommentStep, onHoldAuto,
}: {
  plan: PlanCardDto;
  autoApprove?: boolean;
  autoConfidenceHigh?: boolean;
  onApprove?: () => void;
  onEdit?: () => void;
  onRequestRevision?: () => void;
  onCommentStep?: (ordinal: number) => void;
  onHoldAuto?: () => void;
}) {
  const goal = plan.goal !== undefined && plan.goal.trim() !== '' ? plan.goal : plan.understandingSummary;
  const confidence = plan.signals.confidence ?? confidenceFromRisk(plan.signals.riskLevel);
  const locked = plan.state === 'locked';
  const awaiting = plan.state === 'awaiting';
  // Auto-approve fires only on a high-confidence plan awaiting review (A4.2).
  const auto = awaiting && autoApprove === true && autoConfidenceHigh === true;

  return (
    <div className="plan-card">
      <div className="plan-card__hd">
        <span className="plan-card__ic" aria-hidden>✦</span>
        <span className="plan-card__t">Execution plan</span>
        {plan.revisionCount > 0 && <span className="plan-card__rev">rev {plan.revisionCount}</span>}
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
          ? <div className="review-locked">Plan approved — development under way.</div>
          : <ReviewBar
              plan={plan}
              auto={auto}
              awaiting={awaiting}
              onApprove={onApprove}
              onEdit={onEdit}
              onRequestRevision={onRequestRevision}
              onHoldAuto={onHoldAuto}
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
        <span className="plan-step__title"><MarkdownProse text={title} variant="card" /></span>
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

function ReviewBar({ plan, auto, awaiting, onApprove, onEdit, onRequestRevision, onHoldAuto }: {
  plan: PlanCardDto;
  auto: boolean;
  awaiting: boolean;
  onApprove?: () => void;
  onEdit?: () => void;
  onRequestRevision?: () => void;
  onHoldAuto?: () => void;
}) {
  const pushLabel = plan.pushStrategy === 'autonomous' ? 'autonomous' : 'await approval';
  return (
    <div className="review-bar">
      <div className="scope-guard">
        <span className="sg"><span className="dot g" />{plan.steps.length} steps in scope</span>
        <span className="sg"><span className="dot b" />push: {pushLabel}</span>
      </div>
      {auto
        ? <AutoBanner onApprove={onApprove} onHoldAuto={onHoldAuto} />
        : (
          <>
            <div className="trigger-note">Approving freezes this plan and activates <span className="flow">Development → Review → Push (user-gated)</span>.</div>
            <div className="actions-row">
              <button type="button" className="rb-btn rb-btn--primary" onClick={onApprove} disabled={!awaiting || onApprove === undefined}>✓ Approve &amp; start dev</button>
              {onEdit !== undefined && <button type="button" className="rb-btn rb-btn--ghost" onClick={onEdit}>Edit plan</button>}
              {onRequestRevision !== undefined && <button type="button" className="rb-btn rb-btn--ghost rb-btn--amber" onClick={onRequestRevision}>Request revision</button>}
            </div>
          </>
        )}
    </div>
  );
}

/** The confidence-gated auto-approve banner: a short countdown that calls
 *  `onApprove` automatically when it elapses, with a Hold escape. Only ever
 *  mounted on a high-confidence plan (A4.2), so reaching zero is safe. */
function AutoBanner({ onApprove, onHoldAuto }: { onApprove?: () => void; onHoldAuto?: () => void }) {
  const [secs, setSecs] = useState(5);
  const fired = useRef(false);
  useEffect(() => {
    const id = setInterval(() => setSecs(s => (s > 0 ? s - 1 : 0)), 1000);
    return () => clearInterval(id);
  }, []);
  useEffect(() => {
    if (secs === 0 && !fired.current) {
      fired.current = true;
      onApprove?.();
    }
  }, [secs, onApprove]);
  return (
    <div className="auto-banner">
      <span className="auto-banner__ic" aria-hidden>⚡</span>
      <span className="auto-banner__tx">
        <b>Auto-approve on.</b> High confidence — starting development in{' '}
        <span className="auto-banner__cd">{secs > 0 ? `${secs}s` : 'now'}</span>. No action needed.
      </span>
      {onHoldAuto !== undefined && <button type="button" className="auto-banner__hold" onClick={onHoldAuto}>Hold &amp; review</button>}
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

/** The first sentence / clause of a step action, as a short title. */
function firstClause(action: string): string {
  const stripped = action.trim();
  const stop = stripped.search(/[:.](\s|$)/);
  const head = stop > 0 ? stripped.slice(0, stop) : stripped;
  const words = head.split(/\s+/);
  return words.length > 10 ? `${words.slice(0, 10).join(' ')}…` : head;
}
