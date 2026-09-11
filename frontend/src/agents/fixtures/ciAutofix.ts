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

import type { AgentDevelopmentCiDto, CiAutofixRunDto } from '../../types';

export const CI_PROJECTION: AgentDevelopmentCiDto = {
  runId: 'ci-run', agentTaskId: 'ci-task', status: 'PAUSED', activity: 'PAUSED', attemptsUsed: 1, maxAttempts: 3, rerunsUsed: 0,
  summary: 'Choose the compatibility fix.', finishReason: null, headRef: 'codex/change', expectedRemoteHead: '1234567890',
  pullRequestNumber: 17, pullRequestUrl: 'https://github.com/acme/demo/pull/17', canMessage: true,
};

export const CI_RUN: CiAutofixRunDto = {
  id: CI_PROJECTION.runId, workspaceId: 'workspace-1', repository: 'acme/demo', pullRequestNumber: 17, headRef: CI_PROJECTION.headRef,
  status: 'PAUSED', activity: 'PAUSED', version: 1, authorization: { maxAttempts: 3, expiresAt: '2099-01-01T12:00:00Z' },
  attemptsUsed: 1, rerunsUsed: 0, agentTaskId: 'ci-task', currentTurnId: null, parentTaskId: 'source-task', canMessage: true,
  summary: CI_PROJECTION.summary, pauseReason: 'INVALID_GIT_RESULT', finishReason: null,
};
