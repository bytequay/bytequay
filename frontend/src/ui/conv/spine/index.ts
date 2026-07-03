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

/**
 * The timeline-spine component library — the ONE set of spine primitives +
 * conversation units shared by the brain feed, the trunk feed, and the task
 * root node. Layers 1–2 (spine + conversation units) live here; the gate
 * nodes (Layer 3) and domain milestone nodes (Layer 4) compose these.
 */

// Layer 1 — spine primitives (no domain knowledge).
export { Spine, SpineNode, NodeCard } from './Spine';
export type { SpineColor } from './Spine';

// Layer 2 — conversation units.
export { Round, UserTurn, Headline, BrainDot } from './Round';
export { WorkFold } from './WorkFold';
export { TaskFold } from './TaskFold';
export { ActivityStrip } from './ActivityStrip';
export type { ToolRow, ToolGroup } from './ActivityStrip';

// Layer 3 — gates (approvals + questions), sharing a DecisionNode base.
export { DecisionNode, ApprovalNode, AskQuestionNode } from './gates';
export type { ApprovalDecision } from './gates';

// Layer 4 — domain milestone nodes + controls.
export { StageBoundaryNode } from './StageBoundaryNode';
export { MilestoneNode, TaskCutNode, OutlineStrip } from './milestones';
export type { OutlineChip } from './milestones';
export { DensityToggle } from './DensityToggle';
export type { Density } from './DensityToggle';
