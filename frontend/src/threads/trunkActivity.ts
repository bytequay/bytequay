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

/** What the in-flight pulse card shows: a short meta tag in the header
 *  ("· thinking") and the body line ("Thinking…"). */
export type TrunkActivity = { meta: string; text: string };

const DEFAULT_ACTIVITY: TrunkActivity = { meta: 'working', text: 'Working…' };

/** Shown for the window between sending a planning turn and the model's
 *  first output — which is exactly when the trunk session fetches and
 *  resets its read-only planning worktree to the latest base
 *  (upstream/master for a fork, origin/main for a direct clone). Surfaces
 *  the sync instead of a generic "Working…/Thinking…". */
const SYNCING_ACTIVITY: TrunkActivity = { meta: 'syncing', text: 'Syncing the latest base…' };

/**
 * Derive what the trunk agent is doing right now from the tail of its
 * message stream, so the in-flight card can say "Thinking" / "Calling
 * read_file" / "Waiting for your approval" instead of a flat "working…".
 *
 * A blocked-on-the-user wait wins over any model activity: if a tool is
 * awaiting approval or an AskUserQuestion is unanswered, the turn isn't
 * working, it's waiting. Otherwise the newest activity-bearing row decides
 * — a trailing tool_call means a tool is running now, a tool_result means
 * the model is between tools, thinking/text map to think/write.
 */
export function deriveTrunkActivity(
  messages: ThreadMessageDto[],
  pendingPermission: PendingPermission | null,
  awaitingQuestion: boolean,
): TrunkActivity {
  if (pendingPermission) {
    return {
      meta: 'needs approval',
      text: `Waiting for your approval to run ${pendingPermission.toolName}…`,
    };
  }
  if (awaitingQuestion) {
    return { meta: 'needs answer', text: 'Waiting for your answer…' };
  }
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    // Reached the user's prompt without hitting any model output — the
    // turn has been queued/spawned but nothing has streamed back yet.
    // For the trunk that window is the planning-worktree base sync.
    if (m.role === 'user') return SYNCING_ACTIVITY;
    if (m.type === 'tool_call') {
      return { meta: 'running a tool', text: `Calling ${toolNameOf(m.contentJson)}…` };
    }
    if (m.type === 'tool_result') {
      return { meta: 'thinking', text: 'Working…' };
    }
    if (m.role === 'assistant' && m.type === 'thinking') {
      return { meta: 'thinking', text: 'Thinking…' };
    }
    if (m.role === 'assistant' && m.type === 'text') {
      return { meta: 'writing', text: 'Writing the reply…' };
    }
  }
  return DEFAULT_ACTIVITY;
}

function toolNameOf(contentJson: string): string {
  try {
    const parsed = JSON.parse(contentJson) as { toolName?: unknown };
    return typeof parsed.toolName === 'string' && parsed.toolName ? parsed.toolName : 'a tool';
  } catch {
    return 'a tool';
  }
}
