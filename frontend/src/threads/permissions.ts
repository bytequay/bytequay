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
import type { ThreadMessageDto } from '../types';
import type { PendingPermission } from './ConversationPane';

/**
 * Walk the message log backwards to find the latest unresolved
 * permission prompt. Both explicit decisions and auto-allowed budget
 * events resolve a request with the same callId.
 */
export function findPendingPermission(messages: ThreadMessageDto[]): PendingPermission | null {
  const resolved = new Set<string>();
  for (const m of messages) {
    if (m.type === 'permission_decision' || m.type === 'permission_auto_allowed') {
      const callId = parseCallId(m.contentJson);
      if (callId !== null) {
        resolved.add(callId);
      }
    }
  }
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.type === 'permission_request') {
      const prompt = parsePermissionRequest(m.contentJson);
      if (prompt !== null && !resolved.has(prompt.callId)) {
        return prompt;
      }
    }
  }
  return null;
}

function parseCallId(contentJson: string): string | null {
  try {
    const parsed = JSON.parse(contentJson) as { callId?: unknown };
    return typeof parsed.callId === 'string' && parsed.callId !== '' ? parsed.callId : null;
  }
  catch {
    return null;
  }
}

function parsePermissionRequest(contentJson: string): PendingPermission | null {
  try {
    const parsed = JSON.parse(contentJson) as {
      callId?: unknown;
      toolName?: unknown;
      summary?: unknown;
    };
    if (typeof parsed.callId !== 'string' || parsed.callId === '') {
      return null;
    }
    return {
      callId: parsed.callId,
      toolName: typeof parsed.toolName === 'string' && parsed.toolName !== '' ? parsed.toolName : 'tool',
      summary: typeof parsed.summary === 'string' ? parsed.summary : '',
    };
  }
  catch {
    return null;
  }
}
