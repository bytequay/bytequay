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
import type { ThreadMessageDto } from '../types';
import { findPendingPermission } from './permissions';

describe('findPendingPermission', () => {
  it('returns the latest unresolved permission request', () => {
    expect(findPendingPermission([
      message('permission_request', { callId: 'call-1', toolName: 'Edit', summary: 'old' }),
      message('permission_decision', { callId: 'call-1', decision: 'ALLOW' }),
      message('permission_request', { callId: 'call-2', toolName: 'Bash', summary: 'new' }),
    ])).toEqual({
      callId: 'call-2',
      toolName: 'Bash',
      summary: 'new',
    });
  });

  it('treats auto-allowed events as resolved prompts', () => {
    expect(findPendingPermission([
      message('permission_request', { callId: 'call-1', toolName: 'Edit', summary: 'patch' }),
      message('permission_auto_allowed', { callId: 'call-1', toolName: 'Edit', remaining: 4 }),
    ])).toBeNull();
  });

  it('treats auto-allowed events as resolved even if stream ordering is inverted', () => {
    expect(findPendingPermission([
      message('permission_auto_allowed', { callId: 'call-1', toolName: 'Edit', remaining: 4 }),
      message('permission_request', { callId: 'call-1', toolName: 'Edit', summary: 'patch' }),
    ])).toBeNull();
  });

  it('does not let another call decision resolve the current prompt', () => {
    expect(findPendingPermission([
      message('permission_request', { callId: 'call-1', toolName: 'Edit', summary: 'patch' }),
      message('permission_decision', { callId: 'other-call', decision: 'ALLOW' }),
    ])).toEqual({
      callId: 'call-1',
      toolName: 'Edit',
      summary: 'patch',
    });
  });
});

function message(type: string, content: Record<string, unknown>): ThreadMessageDto {
  return {
    id: `${type}-${String(content.callId ?? 'none')}`,
    threadId: 'thread-1',
    seq: 1,
    role: 'system',
    type,
    contentJson: JSON.stringify(content),
    durationMs: null,
    tokensIn: null,
    tokensOut: null,
    costUsdMilli: null,
    ts: '2026-05-20T00:00:00Z',
  };
}
