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
import type { CSSProperties } from 'react';
import type { TaskPhaseDto, TaskPhaseGroupDto } from '../types';

/**
 * Frontend mirror of the backend {@code TaskPhaseGroup.of} — the coarse
 * trunk-card grouping over the 12 dev-lifecycle phases. Kept in lockstep
 * with the Java mapping; a phase added on one side must be added here.
 */
export function phaseGroupOf(phase: TaskPhaseDto): TaskPhaseGroupDto {
  switch (phase) {
    // Queued waits on the scheduler to free a slot — no human action,
    // grouped with the remote-review wait under IDLE.
    case 'QUEUED':
      return 'IDLE';
    case 'IMPLEMENTING':
    case 'VALIDATING':
    case 'INTERNAL_REVIEW':
    case 'PUSHED_AWAITING_CI':
    case 'CI_FIXING':
    case 'ADDRESSING_COMMENTS':
    case 'AGENT_RE_REVIEW':
      return 'IN_PROGRESS';
    case 'AWAITING_PUSH':
    case 'AWAITING_READY':
    case 'AWAITING_UPDATE_PUSH':
    case 'NEEDS_ATTENTION':
      return 'AWAITING_YOU';
    case 'AWAITING_REMOTE_REVIEW':
      return 'IDLE';
    case 'COMPLETED':
      return 'DONE';
  }
}

/** Trunk-card label for a group — "In progress" / "Awaiting you" / … */
export function phaseGroupLabel(group: TaskPhaseGroupDto): string {
  switch (group) {
    case 'IN_PROGRESS':  return 'In progress';
    case 'AWAITING_YOU': return 'Awaiting you';
    case 'IDLE':         return 'Idle';
    case 'DONE':         return 'Done';
  }
}

/** Pill background + text color for a group's GROUP LABEL chip. */
export function phaseGroupPillStyle(group: TaskPhaseGroupDto): CSSProperties {
  switch (group) {
    case 'IN_PROGRESS':
      return { background: 'rgba(245,158,11,0.14)', color: '#92400e' };
    case 'AWAITING_YOU':
      return { background: 'rgba(245,158,11,0.20)', color: '#7c2d12' };
    case 'IDLE':
      return { background: 'rgba(0,0,0,0.05)', color: 'var(--text-3)' };
    case 'DONE':
      return { background: 'rgba(16,185,129,0.14)', color: '#047857' };
  }
}

/** Status-dot glyph color for a group (the {@code ●} on a TaskChip). */
export function phaseGroupDotColor(group: TaskPhaseGroupDto): string {
  switch (group) {
    case 'IN_PROGRESS':  return '#d97706';
    case 'AWAITING_YOU': return '#b45309';
    case 'IDLE':         return '#9ca3af';
    case 'DONE':         return '#10b981';
  }
}

/** Humanised phase label for the task-detail phase chip, e.g.
 *  {@code AWAITING_PUSH} → "Awaiting push". */
export function phaseLabel(phase: TaskPhaseDto): string {
  return phase.charAt(0) + phase.slice(1).toLowerCase().replace(/_/g, ' ');
}

/** The 8 nodes of the linear happy-path FlowStepper, in order. */
export const FLOW_STEPPER_NODES = [
  'Implement', 'Validate', 'Review', 'Push',
  'CI', 'Ready', 'Remote review', 'Done',
] as const;

/**
 * Which stepper node a phase sits at (0–7). Loop phases do <em>not</em>
 * backtrack — they report the node of the stage they're looping within
 * (CI_FIXING stays at CI; the remote-review loops stay at Remote review),
 * so the stepper never visually rewinds. NEEDS_ATTENTION isn't a node;
 * callers render it as a parked overlay, so it maps to the implement node
 * as a harmless default.
 */
export function stepperNodeOf(phase: TaskPhaseDto): number {
  switch (phase) {
    // QUEUED sits before the first node (the "pre-stepper" ⏳); it maps
    // to Implement as a harmless default for any node-index reader.
    case 'QUEUED':                  return 0;
    case 'IMPLEMENTING':            return 0;
    case 'VALIDATING':              return 1;
    case 'INTERNAL_REVIEW':         return 2;
    case 'AWAITING_PUSH':           return 3;
    case 'PUSHED_AWAITING_CI':
    case 'CI_FIXING':               return 4;
    case 'AWAITING_READY':          return 5;
    case 'AWAITING_REMOTE_REVIEW':
    case 'ADDRESSING_COMMENTS':
    case 'AGENT_RE_REVIEW':
    case 'AWAITING_UPDATE_PUSH':    return 6;
    case 'COMPLETED':               return 7;
    case 'NEEDS_ATTENTION':         return 0;
  }
}

/**
 * Phases in which the agent loop is actively running the task and so
 * holds the thread's compute slot — mirror of the backend
 * TaskQueueScheduler's SLOT_OCCUPYING set. QUEUED, the AWAITING_* holds,
 * PUSHED_AWAITING_CI and AWAITING_REMOTE_REVIEW do not occupy a slot.
 */
export function isSlotOccupying(phase: TaskPhaseDto): boolean {
  switch (phase) {
    case 'IMPLEMENTING':
    case 'VALIDATING':
    case 'INTERNAL_REVIEW':
    case 'CI_FIXING':
    case 'ADDRESSING_COMMENTS':
    case 'AGENT_RE_REVIEW':
      return true;
    default:
      return false;
  }
}

/** Loop phases that render the inline {@code LoopIndicator}. */
export function isLoopPhase(phase: TaskPhaseDto): boolean {
  return phase === 'CI_FIXING'
      || phase === 'ADDRESSING_COMMENTS'
      || phase === 'AGENT_RE_REVIEW';
}

/**
 * Phases whose advancement is driven server-side by the lifecycle
 * reconciler watching the linked PR — CI finishing, the PR going ready,
 * remote review landing, or the PR merging. Mirror of the backend
 * TaskLifecycleDriver REMOTE_SPINE. A task can move through these with no
 * agent turn running, so a window parked here must poll the task row to
 * stay fresh; otherwise the phase chip / stepper show a stale phase until
 * a manual reload.
 */
export function isReconcilerDriven(phase: TaskPhaseDto): boolean {
  switch (phase) {
    case 'PUSHED_AWAITING_CI':
    case 'CI_FIXING':
    case 'AWAITING_READY':
    case 'AWAITING_REMOTE_REVIEW':
    case 'AWAITING_UPDATE_PUSH':
      return true;
    default:
      return false;
  }
}
