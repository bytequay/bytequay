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
import type { ReviewRequest } from './ReviewRequestCard';

/** Prompt text selects a preview only after saved workflow metadata identifies the turn. */
export function workflowRequest(turn: AgentTurnDto | undefined, prompt: string): ReviewRequest | null {
  switch (turn?.workflowRequest) {
    case 'CHERRY_PICK':
      if (prompt.startsWith('Build and test this cherry-picked range')) return {
        action: 'ByteQuay · Build and test',
        topic: /^Target branch: (.+)$/m.exec(prompt)?.[1] ?? 'Cherry-picked range',
        body: 'Build and test the touched modules, and repair breakage in the fixup right after the commit it belongs to.',
        detail: 'Local repair only · ByteQuay verifies Git before publishing',
      };
      // The rebase onto a moved target runs on the cherry-pick's own task.
      if (/^Work branch: /m.test(prompt)) return {
        action: 'ByteQuay · Resolve rebase',
        topic: /^Work branch: (.+)$/m.exec(prompt)?.[1] ?? 'Conflicting commit',
        body: 'Resolve and stage the conflicts the moved target caused. Leave the rebase open for ByteQuay to continue.',
        detail: 'Edit and stage only · ByteQuay force-pushes the verified result',
      };
      return {
        action: 'ByteQuay · Resolve cherry-pick',
        topic: /^Subject: (.+)$/m.exec(prompt)?.[1] ?? 'Conflicting commit',
        body: 'Resolve this commit’s conflicts, check the touched modules, and create or amend its single fixup commit.',
        detail: 'Local repair only · ByteQuay verifies Git and continues the operation',
      };
    case 'REBASE':
      return {
        action: 'ByteQuay · Resolve rebase',
        topic: /^Work branch: (.+)$/m.exec(prompt)?.[1] ?? 'Conflicting commit',
        body: 'Resolve and stage the current conflicts. Leave the rebase open for ByteQuay to continue.',
        detail: 'Edit and stage only · No commits or publication',
      };
    case 'CONTEXT_BUILD':
      if (prompt.startsWith('Check the code')) return {
        action: 'ByteQuay · Check specifications', topic: 'Candidate gaps',
        body: 'Read the code and linked specifications, check for duplicates, and save candidate gaps with evidence.',
        detail: 'Read only · No builds, file edits or publication',
      };
      if (prompt.startsWith('Reproduce one spec check candidate')) return {
        action: 'ByteQuay · Verify specification gap', topic: 'Reproduction test',
        body: 'Recheck the candidate, run a focused test, and save the verified result.',
        detail: 'Temporary test only · ByteQuay retains the report and removes the test',
      };
      if (prompt.startsWith('Learn repository memory')) return {
        action: 'ByteQuay · Learn repository memory', topic: 'Maintainer guidance',
        body: 'Read saved review threads and retain reusable maintainer guidance with its sources.',
        detail: 'Local memory only · User-owned memories stay unchanged',
      };
      return null;
    default:
      return null;
  }
}
