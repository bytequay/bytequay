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
import { ciActivity } from './ciAutofix';
import { AUTOMATION_LEVELS } from './contracts';
import type { AgentTaskState } from './model';
import type { AgentDevelopmentCiDto, AutoMergeSummaryDto, AutomationLevel, DevelopmentAutomationDto, ReviewFixSummaryDto } from '../types';

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

export type StepState = 'done' | 'running' | 'pending' | 'attention';
/** How one step of a started task stands, with the words for its tile's hover text. */
export type StepStatus = { state: StepState; note: string };

/**
 * Each step's status from what the task already reports. An earlier step counts as done once a later
 * one has started, so the tiles never show a gap; a stopped run marks its first unfinished step.
 */
export function automationSteps({ level, generated, delivered, failure, pullRequest, ci, reviewFix, autoMerge, now }: {
  level: AutomationLevel;
  /** The generated delivery turn running now, if any. */
  generated: 'AUTO_COMMIT' | 'CREATE_DRAFT_PR' | 'REFINE_SUMMARY' | null;
  /** The latest development report was ready and its automation settled without stopping. */
  delivered: boolean;
  failure: string | null;
  pullRequest: { draft: boolean } | null;
  ci: AgentDevelopmentCiDto | null;
  reviewFix: ReviewFixSummaryDto | null;
  autoMerge: AutoMergeSummaryDto | null;
  now: number;
}): Partial<Record<AutomationLevel, StepStatus>> {
  const steps: Partial<Record<AutomationLevel, StepStatus>> = {};
  const set = (step: AutomationLevel, state: StepState, note: string): void => {
    if (includesLevel(level, step)) steps[step] = { state, note };
  };
  const published = pullRequest !== null || ci !== null;
  const committed = published || delivered || generated === 'CREATE_DRAFT_PR' || generated === 'REFINE_SUMMARY';
  set('COMMIT', generated === 'AUTO_COMMIT' ? 'running' : committed ? 'done' : 'pending',
    generated === 'AUTO_COMMIT' ? 'committing' : committed ? 'committed' : 'waiting for finished work');
  set('DRAFT_PR', generated === 'CREATE_DRAFT_PR' ? 'running' : published ? 'done' : 'pending',
    generated === 'CREATE_DRAFT_PR' ? 'creating the draft PR' : published ? 'PR opened' : 'not started');
  if (ci === null) set('CI_FIX', 'pending', 'not started');
  else if (ci.status === 'ACTIVE') set('CI_FIX', 'running', ciActivity(ci));
  else if (ci.status === 'PAUSED') set('CI_FIX', 'attention', ciActivity(ci));
  else set('CI_FIX', ci.finishReason === 'GREEN' ? 'done' : ci.finishReason === 'MAX_ATTEMPTS' || ci.finishReason === 'ERROR' ? 'attention' : 'pending', ciActivity(ci));
  if (reviewFix === null) set('REVIEW_FIX', 'pending', 'no review comments yet');
  else if (reviewFix.needsYou > 0) set('REVIEW_FIX', 'attention', `${reviewFix.needsYou} ${reviewFix.needsYou === 1 ? 'comment needs' : 'comments need'} you`);
  else if (reviewFix.waiting > 0) set('REVIEW_FIX', 'running', `fixing ${reviewFix.waiting} ${reviewFix.waiting === 1 ? 'comment' : 'comments'}`);
  else set('REVIEW_FIX', 'done', `${reviewFix.fixed} fixed · ${reviewFix.declined} declined`);
  switch (autoMerge?.state) {
    case undefined: set('MERGE', 'pending', 'waiting for the earlier steps'); break;
    case 'WAITING': set('MERGE', 'pending', autoMerge.message ?? 'waiting'); break;
    case 'READY': set('MERGE', 'running', mergeLabel(autoMerge, now).toLowerCase()); break;
    case 'MERGING': set('MERGE', 'running', 'merging'); break;
    case 'QUEUED': set('MERGE', 'running', 'in the merge queue'); break;
    case 'MERGED': set('MERGE', 'done', 'merged'); break;
    case 'FAILED': set('MERGE', 'attention', autoMerge.message ?? 'the merge failed'); break;
    case 'CANCELLED': set('MERGE', 'pending', 'stopped'); break;
  }
  if (failure !== null) {
    // The failure names the delivery action that stopped; any other stop lands on the first unfinished step.
    const stopped = STOPPED_AT.find(([action]) => failure.startsWith(`Automation stopped. ${action}:`))?.[1]
      ?? AUTOMATION_STEPS.find(step => steps[step.level] !== undefined && steps[step.level]?.state !== 'done')?.level;
    if (stopped !== undefined && steps[stopped] !== undefined) {
      AUTOMATION_STEPS.slice(0, levelIndex(stopped)).forEach(step => { steps[step.level] = { state: 'done', note: 'done' }; });
      steps[stopped] = { state: 'attention', note: failure };
    }
  }
  return steps;
}

/** "Merging in 42m" for a pull request waiting out its delay; "Merging" once the time has come. */
export function mergeLabel(autoMerge: AutoMergeSummaryDto, now: number): string {
  const minutes = autoMerge.mergeAt === null ? 0 : Math.ceil((Date.parse(autoMerge.mergeAt) - now) / 60_000);
  if (minutes <= 0) return 'Merging';
  return minutes < 60 ? `Merging in ${minutes}m` : `Merging in ${Math.floor(minutes / 60)}h${minutes % 60 === 0 ? '' : ` ${minutes % 60}m`}`;
}

const STOPPED_AT: readonly [string, AutomationLevel][] = [
  ['Check development result', 'COMMIT'], ['Auto commit', 'COMMIT'],
  ['Check publication', 'DRAFT_PR'], ['Create draft PR', 'DRAFT_PR'], ['CI Autofix', 'CI_FIX'],
];

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
