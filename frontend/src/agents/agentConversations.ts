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
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import type { Bridge } from '../types';

/**
 * Re-reads a review whenever its stream reports a change, one read at a time: a review is often over 100 KB and a running
 * reviewer reports several events a second, so the events during a read cost one more read, not one each.
 */
export function followAgentReview(
  bridge: Pick<Bridge, 'getAgentReview' | 'subscribeAgentReview'>,
  reviewId: string,
  onReview: (review: AgentReviewDto) => void,
): () => void {
  let live = true;
  let reading = false;
  let again = false;
  let settling: ReturnType<typeof setTimeout> | undefined;
  const read = async (followUp = false): Promise<void> => {
    if (reading) {
      again = true;
      return;
    }
    reading = true;
    try {
      const next = await bridge.getAgentReview(reviewId);
      if (!live) return;
      onReview(next);
      // The review observer settles after the task event. One follow-up covers a read that reached it first.
      if (!followUp && next.activeTurn === null && next.task.mutationClaim === null && next.runs.at(-1)?.state === 'RUNNING') {
        clearTimeout(settling);
        settling = setTimeout(() => { void read(true).catch(() => {}); }, 200);
      }
    }
    finally {
      reading = false;
      if (again && live) {
        again = false;
        void read().catch(() => {});
      }
    }
  };
  const stream = Promise.resolve().then(() => bridge.subscribeAgentReview(reviewId, 0, message => {
    if (message.kind === 'changed' || message.kind === 'events' || message.state === 'open') void read().catch(() => {});
  }, true));
  // A background follow stays silent; Refresh still reports.
  stream.catch(() => {});
  return () => {
    live = false;
    clearTimeout(settling);
    void stream.then(stop => stop(), () => {});
  };
}

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
