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
import type { Bridge } from '../types';

/** A reviewer can belong to the development task before publication, or to its published PR. */
export async function findConversationReview(
  bridge: Pick<Bridge, 'findTaskAgentReview' | 'findAgentReview'>,
  workspaceId: string,
  taskId: string | undefined,
  pullRequest?: { repo: string; number: number },
) {
  if (taskId !== undefined) {
    const review = await bridge.findTaskAgentReview(workspaceId, taskId);
    if (review !== null) return review;
  }
  return pullRequest === undefined ? null : bridge.findAgentReview(workspaceId, pullRequest.repo, pullRequest.number);
}
