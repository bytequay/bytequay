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

import type { AgentTurnDto } from '../types';

export function developmentTurnRequest(turn: AgentTurnDto | null | undefined) {
  if (turn?.developmentOrigin?.kind === 'REFINE_SUMMARY') return {
    action: 'ByteQuay · Refine summary',
    topic: 'WorkBench',
    body: 'Refresh the change intention and commit explanations from the current code and commit history.',
    detail: 'Private summary · Reporting only',
  };
  if (turn?.developmentOrigin?.kind === 'AUTO_COMMIT') return {
    action: 'ByteQuay · Auto commit',
    topic: 'Working changes',
    body: 'Commit the completed changes in this task’s worktree.',
    detail: 'Authorized local commits · Publication is a separate step',
  };
  return null;
}

/**
 * ByteQuay's create-PR request is an instruction for the agent, not words the person wrote, so the
 * transcript shows the action instead of the prompt. Both the accepted automation and the panel
 * button now start the same backend-built turn, so its trusted origin is the only thing read here.
 * Publishing into a pull request that is already open opens nothing, so that turn says Update. The
 * head is the task's generated branch; the base is read back from the request, so history keeps the
 * branch that was asked for.
 */
export function draftPullRequestRequest(turn: AgentTurnDto | undefined, user: { text: string; attachments: readonly unknown[] }, generatedBranch: string | null): { action: string; head: string | null; base: string | null } | null {
  if (turn?.developmentOrigin?.kind !== 'CREATE_DRAFT_PR') return null;
  const { text } = user;
  // ponytail: parses the one prompt template, so rewording it drops only "→ base".
  // The backend shell-quotes the base, so a quote inside the branch name arrives as '"'"'.
  const base = /^Base repository\/branch: \S+ \/ '((?:[^']|'"'"')+)'\./m.exec(text)?.[1]?.replace(/'"'"'/g, "'") ?? null;
  const existing = /Pull request (\S+) already tracks this branch;/.exec(text)?.[1];
  const number = existing?.match(/\/(\d+)$/)?.[1];
  return {
    action: existing === undefined ? 'Open draft pull request' : `Update pull request${number === undefined ? '' : ` #${number}`}`,
    head: generatedBranch,
    base,
  };
}
