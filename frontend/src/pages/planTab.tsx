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
import type { PlanConfidence } from '../ui/pane';
import { MarkdownProse } from '../threads/MarkdownProse';
import type { PlanCardDto } from '../types/brainView';

/**
 * Renders the right-pane Plan tab from the brain's plan card — distilled to
 * the goal, the developing steps, and a single confidence badge. Shared by
 * the task brain page and the Plan-stage page so the plan (and its Approve
 * action when awaiting) shows identically in both. `onApprove` is supplied
 * only when the plan is finalized and awaiting the user.
 */
export function planTab(plan: PlanCardDto, onApprove?: () => void): ReactNode {
  // Backend sends both; fall back for plans recorded before they existed.
  const goalText = plan.goal !== undefined && plan.goal.trim() !== ''
    ? plan.goal
    : plan.understandingSummary;
  const confidence: PlanConfidence = plan.signals.confidence ?? confidenceFromRisk(plan.signals.riskLevel);
  return (
    <PlanTabContent
      source={{
        revised: plan.source.includes('revision'),
        label: plan.source,
        revPill: plan.revisionCount > 0 ? `rev ${plan.revisionCount}` : undefined,
      }}
      // Agents write markdown (code spans, bold) — render it as prose so the
      // goal reads cleanly. Kept to one concise line by the plan schema.
      goal={<MarkdownProse text={goalText} variant="card" />}
      steps={plan.steps.map(s => ({ text: <MarkdownProse text={s.action} variant="card" /> }))}
      confidence={confidence}
      approved={plan.state === 'locked'}
      onApprove={onApprove}
    />
  );
}

/** Invert risk → confidence for plans recorded before the brain emitted it. */
function confidenceFromRisk(risk: 'low' | 'medium' | 'high'): PlanConfidence {
  return risk === 'low' ? 'high' : risk === 'high' ? 'low' : 'medium';
}
