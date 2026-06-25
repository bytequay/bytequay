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
import type { ReactNode } from 'react';
import { PlanTabContent } from '../ui/pane';
import type { PlanSignal } from '../ui/pane';
import { MarkdownProse } from '../threads/MarkdownProse';
import type { PlanCardDto } from '../types/brainView';

/**
 * Renders the right-pane Plan tab from the brain's plan card. Shared by the
 * task brain page and the Plan-stage page so the plan (and its Approve
 * action when awaiting) shows identically in both. `onApprove` is supplied
 * only when the plan is finalized and awaiting the user.
 */
export function planTab(plan: PlanCardDto, onApprove?: () => void): ReactNode {
  const signals: PlanSignal[] = [
    { kind: 'risk-low', label: `${plan.signals.riskLevel} risk` },
    { kind: 'cmplx', label: plan.signals.estimatedComplexity },
    { kind: 'push', label: plan.pushStrategy === 'await_approval' ? 'awaits approval' : 'autonomous' },
  ];
  return (
    <PlanTabContent
      source={{
        revised: plan.source.includes('revision'),
        label: plan.source,
        revPill: plan.revisionCount > 0 ? `rev ${plan.revisionCount}` : undefined,
      }}
      // Agents write markdown (code spans, bold, lists) — render it as
      // prose rather than raw text so the plan reads cleanly.
      summary={<MarkdownProse text={plan.understandingSummary} variant="card" />}
      steps={plan.steps.map(s => ({ text: <MarkdownProse text={s.action} variant="card" /> }))}
      signals={signals}
      approved={plan.state === 'locked'}
      onApprove={onApprove}
    />
  );
}
