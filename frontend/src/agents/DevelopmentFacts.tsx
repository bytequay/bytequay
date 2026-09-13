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

export function developmentTurnLabel(turn: AgentTurnDto | null | undefined): string | null {
  if (turn?.developmentOrigin?.kind === 'AUTO_COMMIT') return 'Auto commit';
  return null;
}

/** The first line of the panel's Create draft PR request, which is how its ordinary turn is recognised. */
const DRAFT_PULL_REQUEST_OPENING = 'Open a draft pull request for this task.';

/**
 * ByteQuay's create-PR requests are instructions for the agent, not words the person wrote, so the
 * transcript shows the action instead of the prompt: the generated publication turn by its trusted
 * origin, the panel button's ordinary turn by its opening line. The head is the task's generated
 * branch; the base is read back from the request, so history keeps the branch that was asked for.
 */
export function draftPullRequestRequest(turn: AgentTurnDto | undefined, text: string, generatedBranch: string | null): { head: string | null; base: string | null } | null {
  if (turn === undefined) return null;
  // ponytail: parses the two prompt templates; rewording either one drops only the "→ base" half.
  const base = turn.developmentOrigin?.kind === 'CREATE_DRAFT_PR'
    ? /^Base repository\/branch: \S+ \/ '([^']+)'\./m.exec(text)
    : text.startsWith(DRAFT_PULL_REQUEST_OPENING) ? / --base (\S+) /.exec(text) : undefined;
  return base === undefined ? null : { head: generatedBranch, base: base?.[1] ?? null };
}
