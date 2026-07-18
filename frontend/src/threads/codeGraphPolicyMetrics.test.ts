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
import type { ThreadTurnEventDto } from '../types';
import { formatCodeGraphPolicy, summarizeCodeGraphPolicy } from './codeGraphPolicyMetrics';

describe('CodeGraph policy metrics', () => {
  it('sums durable per-turn policy events and ignores malformed rows', () => {
    const events = [
      event('{"redirected":2,"attempted":1,"succeeded":1,"failed":0,"fallback":1,"ignored":0}'),
      event('{"redirected":1,"attempted":1,"succeeded":0,"failed":1,"fallback":0,"ignored":1}'),
      event('not-json'),
    ];

    const metrics = summarizeCodeGraphPolicy(events);

    expect(metrics).toEqual({
      redirected: 3, attempted: 2, succeeded: 1, failed: 1, fallback: 1, ignored: 1,
    });
    expect(formatCodeGraphPolicy(metrics)).toBe(
      '1/2 graph · 3 redirected · 1 failed · 1 fallback · 1 ignored',
    );
  });
});

function event(message: string): ThreadTurnEventDto {
  return {
    id: message,
    turnId: 'turn-1',
    threadId: 'thread-1',
    event: 'CODEGRAPH_POLICY',
    createdAt: '2026-07-19T00:00:00Z',
    message,
  };
}
