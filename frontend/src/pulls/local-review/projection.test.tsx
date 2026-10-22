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

import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { TurnView } from '../../agents/AgentConversation';
import type { TranscriptTurn } from '../../agents/transcript';
import { parseLocalReviewThread } from './contracts';
import type { LocalReviewThreadDto, LocalReviewThreads } from './types';

const thread: LocalReviewThreadDto = {
  id: 'local-1', taskId: 'task-1', taskVersion: 1, workspaceId: 'workspace-1', repo: 'owner/repo', number: 10,
  path: 'src/Example.java', side: 'RIGHT', startLine: 12, line: 13, baseSha: null, headSha: 'a'.repeat(40),
  code: 'private Result result() {\n  return value;\n}', resolved: false, version: 1,
  createdAt: '2026-01-01T10:00:00Z', updatedAt: '2026-01-01T10:00:00Z',
  messages: [{ id: 'message-1', author: 'USER', body: 'Can this method be static?', createdAt: '2026-01-01T10:00:00Z',
    turnId: 'turn-1', queuedInputId: null, messageEventId: null, deliveryState: 'COMPLETED', deliveryError: null }],
};
const actions = { onReply: async () => {}, onResolve: async () => {}, onRetry: async () => {} };
const turn: TranscriptTurn = {
  turnId: 'turn-1', ordinal: 1,
  users: [{ id: 'event-1', text: 'PRIVATE INTERNAL CONTEXT PROMPT', attachments: [], delivery: 'TURN', at: thread.createdAt }],
  blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
};
function render(value: TranscriptTurn, review: LocalReviewThreads): string {
  return renderToStaticMarkup(<TurnView turn={value} first={false} open now={0} models={[]} durableTurns={{}} activityChoice={{}}
    previews={{}} agentLabels={{}} onToggle={() => {}} onToggleActivity={() => {}} localReview={review} />);
}

describe('Local review conversation projection', () => {
  it('allows learning replies in the conversation before a PR exists while preserving task restrictions', () => {
    const learning = parseLocalReviewThread({ ...thread, number: 0, path: '', startLine: 0, line: 0, code: '', headSha: null,
      learningAnchor: { turnId: 'summary-turn', cardId: 'purpose', title: 'What this change does', body: 'Makes the result reusable.' } });
    const review: LocalReviewThreads = { ...actions, threads: [learning], disabledReason: 'Code feedback requires an open pull request.', learningDisabledReason: null };
    const html = render(turn, review);
    expect(html).toContain('Makes the result reusable.');
    expect(html).not.toContain('agt-review-request__prompt');
    expect(html).not.toContain('PRIVATE INTERNAL CONTEXT PROMPT');
    expect(html).not.toContain('Code feedback requires an open pull request.');
    expect(html).not.toContain('disabled=""');
    expect(render(turn, { ...review, learningDisabledReason: 'Reconnect to the task.' })).toContain('Reconnect to the task.');
    expect(render(turn, { ...review, threads: [thread] })).toContain('Code feedback requires an open pull request.');
  });
  it('uses the saved user input identity to replace context with the private code discussion after reload', () => {
    const saved = parseLocalReviewThread(JSON.parse(JSON.stringify(thread)));
    const html = render(turn, { ...actions, threads: [saved] });
    expect(html).toContain('Can this method be static?');
    expect(html).toContain('private Result result()');
    expect(html).toContain('src/Example.java:R12–R13');
    expect(html).toContain('class="lrc is-local"');
    expect(html).not.toContain('agt-review-request__prompt');
    expect(html).not.toContain('PRIVATE INTERNAL CONTEXT PROMPT');
  });

  it('matches queued input by the durable message event even when the current turn association changed', () => {
    const queued = { ...thread, messages: [{ ...thread.messages[0], turnId: 'retry-turn', queuedInputId: 'queue-1', messageEventId: 'event-1' }] };
    const html = render({ ...turn, users: [{ ...turn.users[0], delivery: 'QUEUED' }] }, { ...actions, threads: [queued] });
    expect(html).toContain('Can this method be static?');
    expect(html).not.toContain('agt-review-request__prompt');
    expect(html).not.toContain('PRIVATE INTERNAL CONTEXT PROMPT');
  });

  it('does not replace an ordinary user request because an agent replied to a local review during that turn', () => {
    const other = { ...thread, messages: [{ ...thread.messages[0], author: 'AGENT' as const }] };
    const html = render(turn, { ...actions, threads: [other] });
    expect(html).toContain('PRIVATE INTERNAL CONTEXT PROMPT');
    expect(html).not.toContain('Can this method be static?');
  });

  it('keeps unrelated steering visible beside the local review input', () => {
    const html = render({ ...turn, users: [...turn.users, { ...turn.users[0], id: 'steer-1', delivery: 'STEER', text: 'Also run the focused tests' }] },
      { ...actions, threads: [thread] });
    expect(html).toContain('Can this method be static?');
    expect(html).toContain('Also run the focused tests');
    expect(html).not.toContain('agt-review-request__prompt');
    expect(html).not.toContain('PRIVATE INTERNAL CONTEXT PROMPT');
  });

  it('rejects malformed ranges and narrows internal storage fields at the desktop boundary', () => {
    expect(() => parseLocalReviewThread({ ...thread, startLine: 14 })).toThrow('Invalid local review range');
    expect(() => parseLocalReviewThread({ ...thread, side: 'BOTH' })).toThrow('Invalid local review side');
    expect(parseLocalReviewThread({ ...thread, operations: { secret: 'opaque' }, localPath: '/private/worktree' })).toEqual(thread);
  });
});


it('folds local review replies around the specific saved user message', () => {
  const reply = { ...thread.messages[0], id: 'reply', turnId: 'turn-2', body: 'Why does it need instance state?' };
  const html = render({ ...turn, turnId: reply.turnId }, { ...actions, threads: [{ ...thread, messages: [...thread.messages, reply] }] });
  const summary = html.match(/<div class="agt-review-request__bar">[^]*?<\/button>/)?.[0];
  expect(summary).toContain('Reply to review');
  expect(html.match(/<div class="agt-review-request__preview">[^]*?<\/div>/)?.[0]).toContain(reply.body);
  expect(summary).not.toContain(thread.messages[0].body);
  expect(html).toContain('<article class="agt-user__request agt-review-request">');
  expect(html).toContain('>Reply</button>');
  expect(html).toContain('>Resolve</button>');
  expect(html).not.toContain('agt-review-request__prompt');
  expect(html).not.toContain('PRIVATE INTERNAL CONTEXT PROMPT');
});
