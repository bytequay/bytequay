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
import type { AgentTaskState } from './model';
import type { DevelopmentAutomationDto } from '../types';

/** Keep development commits while leaving optional publication and follow-up off. */
export const LOCAL_COMMITS_ONLY: DevelopmentAutomationDto = {
  autoDraftPr: false, autoCiFix: false, ciLaneId: null, reviewSummary: false, reviewReply: false,
};

export const AUTOMATION_OPTIONS = [
  { key: 'autoDraftPr', title: 'Auto draft PR', description: 'Push commits and create or update this task’s PR.' },
  { key: 'autoCiFix', title: 'Auto CI fix', description: 'Repair code or PR-description checks, rerun CI, and mark the draft ready when checks pass.' },
  { key: 'reviewSummary', title: 'Auto review summary',
    description: 'Add a private agent note to each new review comment on this task’s PR: what it asks, whether it holds, and a plan. Nothing is posted to GitHub.' },
  { key: 'reviewReply', title: 'Auto draft review replies',
    description: 'Analyze new review comments and prepare private reply drafts. You edit and post each reply yourself.' },
] as const;

/** Keeping commits local changes only delivery; private review follow-up stays selected. */
export function automationForSubmission(settings: DevelopmentAutomationDto, localOnly: boolean): DevelopmentAutomationDto {
  return localOnly ? { ...LOCAL_COMMITS_ONLY, reviewSummary: settings.reviewSummary, reviewReply: settings.reviewReply } : settings;
}

/** Private review follow-up must not hide that commits stay local. */
export function automationSummary(settings: DevelopmentAutomationDto): string {
  const local = settings.autoDraftPr || settings.autoCiFix ? [] : ['Local commits'];
  return [...local, ...AUTOMATION_OPTIONS.filter(option => settings[option.key]).map(option => option.title.replace('Auto ', ''))].join(' · ');
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
