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

import { zoomReference } from '../githubReference';
import type { GoalItemDto } from './contracts';

/**
 * Opens a Jira ticket's development composer over the page, with an instruction the user reviews and sends; `replaces` starts
 * another attempt after that ended task. With `send`, as from an Epic's Run, the composer sends the instruction itself.
 */
export function startTicket(reference: string, instruction: string, replaces?: string, send = false): void {
  const [connectionId, cloudId, issueId] = reference.split(':');
  zoomReference({ kind: 'jira', connectionId, cloudId, issueId, start: true, draft: instruction, replaces, send });
}

/** Opens a goal item where the app shows it: a pull request, issue or ticket over the page, a task as its screen. */
export function openGoalItem(item: GoalItemDto, workspaceId: string, onOpenTask: (taskId: string) => void): void {
  if (item.kind === 'TASK') {
    onOpenTask(item.key);
    return;
  }
  if (item.kind === 'JIRA') {
    const [connectionId, cloudId, issueId] = item.key.split(':');
    zoomReference({ kind: 'jira', connectionId, cloudId, issueId });
    return;
  }
  const hash = item.key.lastIndexOf('#');
  const repo = item.key.slice(0, hash);
  const number = Number(item.key.slice(hash + 1));
  zoomReference(item.kind === 'PULL_REQUEST' ? { kind: 'pull', repo, number, workspaceId } : { kind: 'issue', repo, number, workspaceId });
}
