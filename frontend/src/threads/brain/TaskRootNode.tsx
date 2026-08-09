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
import { ChevronRightIcon } from '../../ui/TaskBrainDesignIcons';
import {
  PipelinePlanCard, type Plan, type PlanPolicy, type PlanStep as PipelineStep,
} from '../../ui/PipelinePlanCard';

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
        <span className="seed__chev" aria-hidden><ChevronRightIcon /></span>
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

/**
 * The typed plan card. An adapter over {@link PipelinePlanCard} (the visual
 * source of truth): it maps the structured {@link PlanCardDto} onto the display
 * model and decomposes the card's single `onPolicyChange` back
 * into the task's individual min-approvals / auto-approve / auto-merge
 * handlers. Exported under the same name + prop shape so both call sites render
 * the new design unchanged.
 *
 */
export function PlanCard(props: {
  plan: PlanCardDto;
  autoApprove?: boolean;
  autoMerge?: boolean;
  /** ISO 8601 approval time — shown on the footer once the plan is approved. */
  approvedAt?: string;
  onApprove?: () => void;
  /** Typed revision feedback for the brain; the plan stays paused until sent. */
  onRequestRevision?: (text: string) => void;
  onToggleAutoApprove?: () => void;
  onToggleAutoMerge?: () => void;
  minApprovals?: number;
  onSetMinApprovals?: (n: number) => void;
}) {
  const {
    plan, autoApprove, autoMerge, approvedAt, onApprove, onRequestRevision,
    onToggleAutoApprove, onToggleAutoMerge, minApprovals, onSetMinApprovals,
  } = props;

  const policy: PlanPolicy = {
    minApprovals: clampApprovals(minApprovals),
    autoApprove: autoApprove === true,
    autoMerge: autoMerge === true,
  };
  const policyEditable = onSetMinApprovals !== undefined
    || onToggleAutoApprove !== undefined || onToggleAutoMerge !== undefined;
  const onPolicyChange = policyEditable
    ? (next: PlanPolicy) => {
      if (next.minApprovals !== policy.minApprovals) onSetMinApprovals?.(next.minApprovals);
      if (next.autoApprove !== policy.autoApprove) onToggleAutoApprove?.();
      if (next.autoMerge !== policy.autoMerge) onToggleAutoMerge?.();
    }
    : undefined;

  return (
    <PipelinePlanCard
      plan={toPipelinePlan(plan, policy)}
      approvedAt={approvedAt}
      onApprove={onApprove}
      onRequestRevision={onRequestRevision}
      onPolicyChange={onPolicyChange}
    />
  );
}

function toPipelinePlan(dto: PlanCardDto, policy: PlanPolicy): Plan {
  const goal = dto.goal !== undefined && dto.goal.trim() !== '' ? dto.goal.trim() : dto.understandingSummary.trim();
  const confidence = dto.signals.confidence ?? confidenceFromRisk(dto.signals.riskLevel);
  // "Why this plan" = the problem understanding + the intended approach, minus
  // whatever already reads as the goal headline.
  const why = [dto.understandingSummary, dto.intentSummary]
    .map(s => s.trim())
    .filter(s => s !== '' && s !== goal);
  return {
    rev: dto.revisionCount,
    status: dto.state === 'locked' ? 'approved'
      : dto.state === 'awaiting' ? 'ready'
        : dto.state === 'revision_required' ? 'revision_required'
        : isPlanSelfReviewing(dto) ? 'running' : 'draft',
    goal,
    risk: dto.signals.riskLevel,
    effort: dto.signals.estimatedComplexity,
    confidence,
    why: why.length > 0 ? why : undefined,
    validation: dto.validationStrategy.trim() !== '' ? dto.validationStrategy.trim() : undefined,
    outOfScope: dto.outOfScope?.map(item => item.trim()).filter(item => item !== ''),
    pushStrategy: dto.pushStrategy,
    value: dto.signals.expectedGain.trim() !== '' ? dto.signals.expectedGain : undefined,
    steps: dto.steps
      .map(step => toPipelineStep(step, dto.signals.riskLevel))
      .sort((left, right) => left.n - right.n),
    policy,
  };
}

/** A complete, finalized plan stays non-approvable until Brain's mandatory
 * self-review records its checkpoint. The backend preserves that distinction
 * as finalized status on a still-draft lifecycle state. */
export function isPlanSelfReviewing(plan: PlanCardDto): boolean {
  return plan.state === 'draft' && plan.status === 'finalized';
}

function toPipelineStep(step: PlanStepDto, planRisk: PlanCardDto['signals']['riskLevel']): PipelineStep {
  return {
    n: step.ordinal,
    short: step.action,
    detail: step.detail,
    files: step.files,
    risk: step.risk ?? (planRisk === 'medium' ? 'med' : planRisk),
  };
}

function clampApprovals(n?: number): 0 | 1 | 2 {
  return n === 1 ? 1 : n === 2 ? 2 : 0;
}

function confidenceFromRisk(risk: 'low' | 'medium' | 'high'): 'low' | 'medium' | 'high' {
  return risk === 'low' ? 'high' : risk === 'high' ? 'low' : 'medium';
}
