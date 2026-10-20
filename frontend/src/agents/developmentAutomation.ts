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
import { AUTOMATION_LEVELS } from './contracts';
import type { AgentTaskState } from './model';
import type { AutomationLevel, DevelopmentAutomationDto } from '../types';

/** Keep development commits while leaving publication and follow-up off. */
export const LOCAL_COMMITS_ONLY: DevelopmentAutomationDto = { level: 'COMMIT', ciLaneId: null };

/** The scale's stops in order, with the words each icon stands for. */
export const AUTOMATION_STEPS: readonly { level: AutomationLevel; title: string; description: string }[] = [
  { level: 'COMMIT', title: 'Auto-Commit', description: 'Commit finished work on this task’s branch. Nothing leaves your Mac.' },
  { level: 'DRAFT_PR', title: 'Auto-Draft PR', description: 'Push commits and create or update this task’s draft PR.' },
  { level: 'CI_FIX', title: 'CI fix', description: 'Repair failing checks, rerun CI, and mark the draft ready when checks pass.' },
  { level: 'REVIEW_FIX', title: 'Auto-Review fix', description: 'Fix review comments, reply on each thread, and resolve the threads that were fixed.' },
  { level: 'MERGE', title: 'Auto-merge', description: 'Rebase-merge or join the merge queue once the PR can merge, after the delay set in Settings.' },
];

export function levelIndex(level: AutomationLevel): number {
  return AUTOMATION_LEVELS.indexOf(level);
}

/** Whether a task at `level` runs `step`; the scale is cumulative. */
export function includesLevel(level: AutomationLevel, step: AutomationLevel): boolean {
  return levelIndex(level) >= levelIndex(step);
}

export function levelTitle(level: AutomationLevel): string {
  return AUTOMATION_STEPS[levelIndex(level)].title;
}

export function developmentQuestion(state: AgentTaskState): string | null {
  if (state.task?.state !== 'OPEN' || state.task.developmentContinuationUnfinished || state.task.mutationClaim !== null || state.turn?.state !== 'COMPLETED'
    || (state.latestTurnId !== null && state.latestTurnId !== state.turn.id)) return null;
  const report = (state.turn.developmentOrigin?.kind === 'REFINE_SUMMARY' ? null : state.turn.developmentResult) ?? Object.values(state.turns)
    .filter(turn => turn.developmentOrigin?.kind !== 'REFINE_SUMMARY' && turn.developmentResult != null)
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt))[0]?.developmentResult;
  return report != null && !report.readyForDelivery
    ? report.message ?? 'The agent is waiting for your answer.' : null;
}

/** Stop follows the active turn's accepted policy even before its continuation marker is set. */
export function stoppingEndsDevelopment(state: AgentTaskState, activeTurnId: string | null): boolean {
  if (state.task?.developmentContinuationUnfinished) return true;
  if (state.task?.interactive !== true || activeTurnId === null || state.turn?.id !== activeTurnId) return false;
  return state.turn.settings?.developmentAutomation != null;
}
