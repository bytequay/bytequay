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

/** The latest unanswered AskUserQuestion call — its callId (for keys)
 *  and raw tool input (the questions schema the card renders). */
export type PendingQuestion = { callId: string; input: unknown };

/**
 * Find the AskUserQuestion the agent is still waiting on. The answer is
 * simply the next user turn, so a question is "pending" only until a
 * user/text message lands after it. Walk backwards: the first user reply
 * means nothing is pending; the first AskUserQuestion call before any
 * reply is the live one. Other rows (assistant text, the call's own
 * deny tool_result) are skipped.
 */
export function findPendingAskQuestion(messages: ThreadMessageDto[]): PendingQuestion | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.role === 'user' && m.type === 'text') {
      return null;
    }
    if (m.type === 'tool_call') {
      try {
        const c = JSON.parse(m.contentJson) as {
          callId?: unknown; toolName?: unknown; input?: unknown;
        };
        if (c.toolName === 'AskUserQuestion') {
          return { callId: typeof c.callId === 'string' ? c.callId : '', input: c.input };
        }
      }
      catch { /* not JSON — skip */ }
    }
  }
  return null;
}
