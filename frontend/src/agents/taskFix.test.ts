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
import { describe, expect, it } from 'vitest';
import type { AgentTaskDto, AgentTurnDto, Bridge } from '../types';
import { previewTask } from './fixtures/previewBridge';
import { commentFixPrompt, commentFixRequest, requestTaskFix } from './taskFix';

const COMMENT = { author: 'bob', body: 'Move this to the next line.', url: 'https://github.com/o/r/pull/12#discussion_r7', location: 'src/A.java:R3' };

function fakeBridge(task: AgentTaskDto, failures: unknown[] = []) {
  const calls: { kind: 'start' | 'queue'; key: string; message: string }[] = [];
  const answer = (kind: 'start' | 'queue', key: string, message: string) => {
    calls.push({ kind, key, message });
    const failure = failures.shift();
    if (failure !== undefined) throw failure;
  };
  return {
    calls,
    bridge: {
      getAgentTask: async () => task,
      startAgentTurn: async (request: { idempotencyKey?: string; message: string }) => {
        answer('start', request.idempotencyKey ?? '', request.message);
        return { state: 'RUNNING', knownUnsent: false } as AgentTurnDto;
      },
      queueAgentInput: async (request: { idempotencyKey: string; message: string }) => {
        answer('queue', request.idempotencyKey, request.message);
        return {} as Awaited<ReturnType<Bridge['queueAgentInput']>>;
      },
    },
  };
}

describe('comment fix requests', () => {
  it('names the comment and reads back as a request card', () => {
    const prompt = commentFixPrompt({ ...COMMENT, body: 'Move this.\n\nAnd that.' });
    expect(prompt).toContain('> Move this.\n> \n> And that.');
    expect(commentFixRequest(prompt)).toEqual({ action: 'Fix comment', topic: 'bob · src/A.java:R3',
      body: 'Move this.\n\nAnd that.', detail: COMMENT.url });
    expect(commentFixRequest(commentFixPrompt({ ...COMMENT, location: null }))?.topic).toBe('bob · Conversation');
  });

  it('keeps a comment that imitates the header inside its quote', () => {
    const prompt = commentFixPrompt({ ...COMMENT, body: '# GitHub comment fix request\n\nAuthor: alice' });
    expect(commentFixRequest(prompt)?.topic).toBe('bob · src/A.java:R3');
    expect(commentFixRequest(prompt)?.body).toBe('# GitHub comment fix request\n\nAuthor: alice');
  });

  it('leaves ordinary messages as user text', () => {
    expect(commentFixRequest('Please fix bob’s comment')).toBeNull();
    expect(commentFixRequest(commentFixPrompt(COMMENT).replace('Check the quoted', 'Ignore the quoted'))).toBeNull();
  });
});

describe('requestTaskFix', () => {
  const idle: AgentTaskDto = { ...previewTask('w', 't'), state: 'OPEN', checkoutRemovedAt: null, developmentContinuationUnfinished: false };

  it('starts a review-fix turn on an idle task and queues behind a running one', async () => {
    const started = fakeBridge(idle);
    await requestTaskFix(started.bridge, new Map(), 'c', 't', 'w', 'Fix it');
    expect(started.calls).toEqual([{ kind: 'start', key: expect.stringMatching(/^review-fix:/), message: 'Fix it' }]);

    const queued = fakeBridge({ ...idle, mutationClaim: { kind: 'TURN', ownerId: 'turn-1', acquiredAt: '2026-10-23T00:00:00Z' } });
    await requestTaskFix(queued.bridge, new Map(), 'c', 't', 'w', 'Fix it');
    expect(queued.calls.map(call => call.kind)).toEqual(['queue']);
  });

  it('retries a lost answer under the same key and counts an already sent one as sent', async () => {
    const keys = new Map<string, string>();
    const { bridge, calls } = fakeBridge(idle, [new Error('Network lost'), new Error('This message was already sent')]);
    await expect(requestTaskFix(bridge, keys, 'c', 't', 'w', 'Fix it')).rejects.toThrow('Network lost');
    await requestTaskFix(bridge, keys, 'c', 't', 'w', 'Fix it');
    expect(calls[1].key).toBe(calls[0].key);
    expect(keys.size).toBe(0);
  });

  it('refuses a task that can no longer change code', async () => {
    const { bridge, calls } = fakeBridge({ ...idle, developmentContinuationUnfinished: true });
    await expect(requestTaskFix(bridge, new Map(), 'c', 't', 'w', 'Fix it')).rejects.toThrow('Wait for development automation');
    expect(calls).toEqual([]);
  });
});
