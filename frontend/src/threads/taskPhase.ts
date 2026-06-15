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
