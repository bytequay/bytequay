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

export const DEFAULT_DEVELOPMENT_AUTOMATION: DevelopmentAutomationDto = {
  autoCommit: true, autoDraftPr: true, autoCiFix: true, ciLaneId: null,
};

export const AUTOMATION_OPTIONS = [
  { key: 'autoCommit', title: 'Auto commit', description: 'Commit finished development work.' },
  { key: 'autoDraftPr', title: 'Auto draft PR', description: 'Push commits and create or update this task’s PR.' },
  { key: 'autoCiFix', title: 'Auto CI fix', description: 'Repair failing CI and mark the draft ready when checks pass.' },
] as const;

export function automationForSubmission(settings: DevelopmentAutomationDto, localOnly: boolean): DevelopmentAutomationDto {
  return localOnly ? { ...settings, autoCommit: false, autoDraftPr: false, autoCiFix: false } : settings;
}

export function automationSummary(settings: DevelopmentAutomationDto): string {
  return AUTOMATION_OPTIONS.filter(option => settings[option.key]).map(option => option.title.replace('Auto ', '')).join(' · ') || 'Local only';
}

export function developmentQuestion(state: AgentTaskState): string | null {
  if (state.task?.state !== 'OPEN' || state.task.mutationClaim !== null || state.turn?.state !== 'COMPLETED'
    || (state.latestTurnId !== null && state.latestTurnId !== state.turn.id)) return null;
  const report = state.turn.developmentResult;
  return report != null && !report.readyForDelivery
    ? report.message ?? 'The agent is waiting for your answer.' : null;
}
