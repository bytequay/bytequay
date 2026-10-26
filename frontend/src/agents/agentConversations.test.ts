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
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import type { AgentStreamMessage, Bridge } from '../types';
import { reviewFixture } from '../pulls/agent-review/fixtures/review';
import { findConversationReview, followAgentReview } from './agentConversations';

const pr = { repo: 'owner/repo', number: 42 };
function bridge() {
  return {
    findTaskAgentReview: vi.fn<Parameters<Bridge['findTaskAgentReview']>, ReturnType<Bridge['findTaskAgentReview']>>().mockResolvedValue(null),
    findAgentReview: vi.fn<Parameters<Bridge['findAgentReview']>, ReturnType<Bridge['findAgentReview']>>().mockResolvedValue(null),
  };
}

describe('conversation reviewer lookup', () => {
  it('retains a reviewer started on the task before its PR was published', async () => {
    const ports = bridge();
    const review = reviewFixture({ number: null, sourceTaskId: 'task-1' });
    ports.findTaskAgentReview.mockResolvedValue(review);
    await expect(findConversationReview(ports, 'workspace-1', 'task-1', pr)).resolves.toBe(review);
    expect(ports.findAgentReview).not.toHaveBeenCalled();
  });

  it('finds the same PR reviewer after switching to its developer conversation', async () => {
    const ports = bridge();
    const review = reviewFixture({ sourceTaskId: null });
    ports.findAgentReview.mockResolvedValue(review);
    await expect(findConversationReview(ports, 'workspace-1', 'task-1', pr)).resolves.toBe(review);
    expect(ports.findTaskAgentReview).toHaveBeenCalledWith('workspace-1', 'task-1');
    expect(ports.findAgentReview).toHaveBeenCalledWith('workspace-1', pr.repo, pr.number);
  });

  it('does not invent a reviewer for a local task or swallow failed lookups', async () => {
    const ports = bridge();
    await expect(findConversationReview(ports, 'workspace-1', 'task-1')).resolves.toBeNull();
    expect(ports.findAgentReview).not.toHaveBeenCalled();
    ports.findTaskAgentReview.mockRejectedValue(new Error('Unavailable'));
    await expect(findConversationReview(ports, 'workspace-1', 'task-1', pr)).rejects.toThrow('Unavailable');
  });
});

describe('following a review', () => {
  afterEach(() => { vi.useRealTimers(); });

  function follow(review: AgentReviewDto) {
    const answers: Array<() => void> = [];
    let notify: (message: AgentStreamMessage) => void = () => {};
    const ports = {
      getAgentReview: vi.fn(() => new Promise<AgentReviewDto>(resolve => { answers.push(() => resolve(review)); })),
      subscribeAgentReview: vi.fn(async (_reviewId: string, _after: number, listener: (message: AgentStreamMessage) => void) => {
        notify = listener;
        return () => {};
      }),
    };
    const shown: AgentReviewDto[] = [];
    const stop = followAgentReview(ports, review.id, value => shown.push(value));
    const event = () => notify({ subscriptionId: 'stream', taskId: review.task.id, kind: 'events', events: [] });
    return { ports, shown, stop, event, answer: async () => { answers.shift()?.(); await vi.waitFor(() => Promise.resolve()); } };
  }

  it('reads one review at a time, and once more for every event that came during a read', async () => {
    const { ports, shown, stop, event, answer } = follow(reviewFixture());
    await vi.waitFor(() => expect(ports.subscribeAgentReview).toHaveBeenCalled());
    for (let count = 0; count < 5; count++) event();
    expect(ports.getAgentReview).toHaveBeenCalledTimes(1);
    await answer();
    await vi.waitFor(() => expect(ports.getAgentReview).toHaveBeenCalledTimes(2));
    await answer();
    expect(ports.getAgentReview).toHaveBeenCalledTimes(2);
    expect(shown).toHaveLength(2);
    stop();
  });

  it('follows a settling run up once instead of polling it', async () => {
    vi.useFakeTimers();
    const settling = reviewFixture();
    const { ports, stop, event, answer } = follow({ ...settling, runs: [{ ...settling.runs[0], state: 'RUNNING', endedAt: null }] });
    await vi.waitFor(() => expect(ports.subscribeAgentReview).toHaveBeenCalled());
    event();
    await answer();
    await vi.advanceTimersByTimeAsync(200);
    expect(ports.getAgentReview).toHaveBeenCalledTimes(2);
    await answer();
    await vi.advanceTimersByTimeAsync(2000);
    expect(ports.getAgentReview).toHaveBeenCalledTimes(2);
    stop();
  });
});
