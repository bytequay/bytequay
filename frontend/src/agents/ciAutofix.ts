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

import type { AgentDevelopmentCiDto, AgentTurnDto } from '../types';
import { runtimeStatus, type AgentTaskState } from './model';

export function ciActivity(ci: Pick<AgentDevelopmentCiDto, 'status' | 'activity' | 'attemptsUsed' | 'maxAttempts' | 'finishReason' | 'canMessage'>): string {
  if (ci.status === 'FINISHED') {
    switch (ci.finishReason) {
      case 'GREEN': return 'CI passed';
      case 'CANCELLED': return 'CI Autofix stopped';
      case 'ERROR': return 'CI Autofix failed';
      case 'MAX_ATTEMPTS': return 'CI repair limit reached';
      case 'AUTHORIZATION_EXPIRED': return 'CI Autofix authorization expired';
      case 'STALE_HEAD': return 'CI Autofix stopped: PR branch changed';
      default: return 'CI Autofix ended';
    }
  }
  if (ci.status === 'PAUSED') return ci.canMessage ? 'CI needs your input' : 'CI Autofix paused';
  switch (ci.activity) {
    case 'FIXING': return `Fixing CI ${ci.attemptsUsed}/${ci.maxAttempts}`;
    case 'REBASING': return 'Rebasing CI changes';
    case 'PUSHING': return 'Pushing CI changes';
    case 'RERUNNING_CI': return 'Rerunning CI';
    case 'INSPECTING_GIT': return 'Checking CI changes';
    case 'PREPARING_PUSH': return 'Preparing CI push';
    case 'PREPARING_AGENT': return 'Preparing CI agent';
    default: return 'Waiting for CI';
  }
}

/** Only a standalone mention addresses CI; email addresses and longer handles stay ordinary text. */
export function ciMessage(text: string): string | null {
  const mention = /(^|\s)@ci(?:\s+|$)/i;
  return mention.test(text) ? text.replace(mention, '$1').trim() : null;
}

export function ciMessageUnavailable(state: AgentTaskState): string | null {
  const ci = state.task?.developmentCi;
  if (ci == null) return 'No CI Autofix run is available for this task.';
  if (ci.status === 'FINISHED' || !state.task?.developmentContinuationUnfinished) return 'This CI Autofix run has ended.';
  if (state.connection !== 'open' || state.gap) return 'Reconnect to send a message to CI Autofix.';
  if (runtimeStatus(state) !== 'idle' || !ci.canMessage
    || (ci.status === 'ACTIVE' && (ci.activity !== 'FIXING' || ci.currentTurnId === null))) {
    return 'CI Autofix can accept a message while a supported agent is fixing CI or when it pauses for your input.';
  }
  if (state.sending) return 'Sending…';
  if (state.attachments.length > 0) return 'CI Autofix accepts text only. Remove the attached images to send.';
  const message = ciMessage(state.composer);
  if (!message) return 'Add a message with @ci.';
  if (message.length > 16_384) return 'CI Autofix messages must be 16,384 characters or fewer.';
  return null;
}

/** Only the CI task's opening template is folded; user replies and steering remain ordinary messages. */
export function ciAutofixRequest(turn: AgentTurnDto | undefined, text: string) {
  if (turn?.settings?.permissionPolicy !== 'CI_AUTOFIX') return null;
  const repository = /^Verified current CI context:\n- Pull request: ([^\s#]+\/[^\s#]+#[1-9]\d*)\n/.exec(text)?.[1];
  if (repository === undefined) return null;
  return {
    action: 'ByteQuay · Fix CI',
    topic: repository,
    body: 'Investigate failed checks, repair their cause and run useful local checks.',
    detail: 'ByteQuay verifies repairs and handles authorized publication.',
  };
}
