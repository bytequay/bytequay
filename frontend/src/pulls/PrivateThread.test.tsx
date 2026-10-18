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
import { AgentAvailabilityProvider } from '../agents/availability';
import { describe, expect, it } from 'vitest';
import { ANSWERING_ELSEWHERE, isSummaryRequest, parseCommentSummary, SUMMARY_REQUEST, type DiscussionMessageDto, type TaskDiscussionDto } from '../agents/discussions/contracts';
import type { TaskDiscussions } from '../agents/discussions/useTaskDiscussions';
import {
  commentExcerpt, hiddenThreadCount, PrivateThreadControls, PrivateThreadForward, privateThreadKey, privateThreadSource,
  PrivateThreadTab, withMention, type PrivateThreads,
} from './PrivateThread';

const KEY = 'review_comment:7';
const AT = '2026-09-27T00:00:00Z';
const SUMMARY = JSON.stringify({ ask: 'Use **bold** [links](https://evil.example)', valid: 'partly', why: 'The `cache` is keyed by path.',
  do: 'yes', plan: 'Key it by id.', size: 'small' });

function message(id: string, author: 'USER' | 'AGENT', body: string, error: string | null = null): DiscussionMessageDto {
  return { id, author, body, createdAt: AT, error };
}

function thread(over: Partial<TaskDiscussionDto> = {}): TaskDiscussionDto {
  return {
    id: 't1', taskId: 'task', turnId: 'review-comment', quote: KEY, occurrence: 1, resolved: false, pending: false,
    providerLane: 'codex', version: 1, createdAt: AT, updatedAt: AT, comment: { repo: 'o/r', number: 12, key: KEY }, replyDraft: null, replyDraftError: null,
    messages: [message('m1', 'USER', 'Is this right?'), message('m2', 'AGENT', 'Yes.'), message('m3', 'USER', 'Sure?'), message('m4', 'AGENT', 'Sure.')],
    ...over,
  };
}

function threads(over: Partial<PrivateThreads> = {}, value: TaskDiscussionDto | null = thread()): PrivateThreads {
  return {
    threads: new Map(value === null ? [] : [[KEY, value]]), busy: false, busyReason: ANSWERING_ELSEWHERE, disabledReason: null, forward: null, show: true,
    ask: async () => {}, resolve: async () => {}, retry: async () => {}, draftReply: async () => '', drafts: new Map(), setForward: () => {}, setShow: () => {}, setDraft: () => {},
    ...over,
  };
}

const controls = (value: PrivateThreads, own = false): string => renderToStaticMarkup(<PrivateThreadControls threads={value} threadKey={KEY} own={own} />);
const forward = (value: PrivateThreads, draft = false): string => renderToStaticMarkup(
  <PrivateThreadForward threads={value} threadKey={KEY} author="bob" avatarUrl={null} timestamp={AT} body="Please **rename** this."
    repository="o/r" viewerLogin="alice" onDraftReply={draft ? () => {} : undefined} />);

describe('private thread helpers', () => {
  it('keys a comment by its GitHub resource type and id', () => {
    expect(privateThreadKey('review_comment', 7)).toBe(KEY);
    expect(privateThreadKey('review', 7)).toBe('review:7');
    expect(privateThreadKey('issue_comment', 7)).toBe('issue_comment:7');
  });

  it('counts the threads Hide put away', () => {
    expect(hiddenThreadCount(threads())).toBe(0);
    expect(hiddenThreadCount(threads({ show: false }))).toBe(1);
    expect(hiddenThreadCount(threads({ show: false }, thread({ messages: [] })))).toBe(0);
  });

  it('folds a comment to one plain line', () => {
    expect(commentExcerpt('## Heads up\n\nPlease use `Optional` — see [the guide](https://x).\n\n```java\nfoo();\n```'))
      .toBe('Heads up Please use Optional — see the guide. foo();');
    expect(commentExcerpt('x'.repeat(400))).toHaveLength(160);
  });

  it('mentions the commenter once and appends after what the user wrote', () => {
    expect(withMention('', 'Good catch, fixed.', 'bob')).toBe('@bob Good catch, fixed.');
    expect(withMention('', 'Thanks @Bob, fixed.', 'bob')).toBe('Thanks @Bob, fixed.');
    expect(withMention('I agree.\n', 'Fixed.', 'bob')).toBe('I agree.\n\n@bob Fixed.');
    // A longer login that merely starts with the commenter's is someone else.
    expect(withMention('', 'cc @bobby', 'bob')).toBe('@bob cc @bobby');
  });

  it('scopes the task’s comment threads to the pull request the tab shows', async () => {
    const asked: unknown[] = [];
    const on = (id: string, repo: string, number: number, key: string): TaskDiscussionDto => thread({ id, comment: { repo, number, key } });
    const discussions: TaskDiscussions = {
      discussions: [], busy: true, error: null, disabledReason: 'Files released',
      commentThreads: [on('a', 'Owner/Repo', 12, 'review:1'), on('b', 'owner/repo', 13, 'review:2'), on('c', 'other/repo', 12, 'review:3')],
      refresh: async () => {}, open: async () => thread(), ask: async () => {}, resolve: async () => {}, retry: async () => {},
      askComment: async request => { asked.push(request); return thread(); }, draftReply: async () => '',
    };
    const source = privateThreadSource(discussions, { repo: 'owner/repo', number: 12 });
    expect([...source.threads.entries()].map(([key, value]) => [key, value.id])).toEqual([['review:1', 'a']]);
    expect(source.busy).toBe(true);
    expect(source.disabledReason).toBe('Files released');
    await source.ask('review:1', '');
    expect(asked).toEqual([{ repo: 'owner/repo', number: 12, key: 'review:1', question: '' }]);
  });

  it('recognizes the hidden summary request and only the exact verdict shape', () => {
    expect(isSummaryRequest(message('m', 'USER', SUMMARY_REQUEST))).toBe(true);
    expect(isSummaryRequest(message('m', 'AGENT', SUMMARY_REQUEST))).toBe(false);
    expect(isSummaryRequest(message('m', 'USER', `${SUMMARY_REQUEST} `))).toBe(false);
    expect(parseCommentSummary(SUMMARY)?.valid).toBe('partly');
    expect(parseCommentSummary(JSON.stringify({ ...JSON.parse(SUMMARY), ref: 'c1' }))).toBeNull();
    expect(parseCommentSummary(JSON.stringify({ ...JSON.parse(SUMMARY), valid: 'maybe' }))).toBeNull();
    expect(parseCommentSummary(JSON.stringify({ ...JSON.parse(SUMMARY), size: 3 }))).toBeNull();
    expect(parseCommentSummary('Yes, it holds.')).toBeNull();
  });
});

describe('private thread surfaces', () => {
  it('keeps a second conversation on the same comment readable', () => {
    const earlier = thread({ id: 'earlier', messages: [message('old-question', 'USER', 'Could another connector be affected?'),
      message('old-answer', 'AGENT', 'Check the shared connector test.')], replyDraft: 'I will add the shared test.' });
    const html = forward(threads({ earlierThreads: new Map([[KEY, [earlier]]]) }));
    expect(html).toContain('Other saved conversation · 1 reply');
    expect(html).toContain('Could another connector be affected?');
    expect(html).toContain('Check the shared connector test.');
    expect(html).toContain('I will add the shared test.');
    expect(html).toContain('Sure?');
  });

  it('uses the saved conversation’s lifecycle while the development task remains open', () => {
    const saved = threads({ statusFor: () => ({ busy: false, busyReason: ANSWERING_ELSEWHERE,
      disabledReason: 'This review has ended, so its threads are read-only', sendDisabledReason: null }) });
    expect(controls(saved)).toContain('Local · 2 replies');
    const html = forward(saved);
    expect(html).toContain('Sure?');
    expect(html).toContain('This review has ended');
    expect(html).not.toContain('Private question to the agent');
  });

  it('keeps history and saved drafts usable while agent readiness is unavailable', () => {
    const saved = threads({}, thread({ replyDraft: 'I will rename it.' }));
    const html = renderToStaticMarkup(<AgentAvailabilityProvider>
      <PrivateThreadControls threads={saved} threadKey={KEY} own={false} />
      <PrivateThreadForward threads={saved} threadKey={KEY} author="bob" avatarUrl={null} timestamp={AT} body="Rename it."
        repository="o/r" onDraftReply={() => {}} />
    </AgentAvailabilityProvider>);
    expect(html).not.toContain('class="pr-ask-agent"');
    expect(html).not.toContain('Private question to the agent');
    expect(html).toContain('Local · 2 replies');
    expect(html).toContain('I will rename it.');
    expect(html).toContain('Use draft');
    expect(html).toContain('Connect an agent');
  });

  it('shows a prepared reply as a private draft that can be used after the task stops', () => {
    const html = forward(threads({ disabledReason: 'Task completed' }, thread({ replyDraft: 'Thanks, I will rename it.' })), true);
    expect(html).toContain('Thanks, I will rename it.');
    expect(html).toContain('Private draft · review and edit before posting.');
    expect(html).toContain('Use draft');
    expect(html).not.toMatch(/class="pr-private-draft" disabled/);
  });

  it('explains a failed automatic draft and keeps manual drafting available', () => {
    const html = forward(threads({}, thread({ replyDraftError: 'Account unavailable' })), true);
    expect(html).toContain('Reply draft could not be prepared: Account unavailable');
    expect(html).toContain('Choose Draft with agent to retry.');
  });

  it('disables generation while the automatic reply is still pending', () => {
    const html = forward(threads({ busy: true }, thread({ pending: true })), true);
    expect(html).toMatch(/class="pr-private-draft" disabled/);
    expect(html).toContain('Draft with agent');
    const saved = forward(threads({ busy: true }, thread({ pending: true, replyDraft: 'Already prepared.' })), true);
    expect(saved).not.toMatch(/class="pr-private-draft" disabled/);
    expect(saved).toContain('Use draft');
  });

  it('shows a thread behind its comment as a chip and a tab, before Ask agent', () => {
    const html = controls(threads());
    expect(html).toContain('Local · 2 replies ↑');
    expect(html.indexOf('Local · 2 replies')).toBeLessThan(html.indexOf('Ask agent'));
    expect(html).toContain('title="Ask the agent about this comment. Private."');
    expect(renderToStaticMarkup(<PrivateThreadTab threads={threads()} threadKey={KEY} />)).toContain('pr-private-tab');
    expect(controls(threads({}, thread({ resolved: true })))).toContain('Resolved · 2 replies ↑');
    expect(controls(threads({}, thread({ pending: true, messages: [message('h', 'USER', SUMMARY_REQUEST)] })))).toContain('Local · answering…');
    expect(controls(threads({}, thread({ messages: [message('h', 'USER', SUMMARY_REQUEST, 'The agent timed out')] }))))
      .toContain('Local · couldn’t answer ↑');
  });

  it('offers only Ask agent for a comment with no thread, and none of it for a thread brought forward', () => {
    const html = controls(threads({}, null));
    expect(html).not.toContain('pr-private-chip');
    expect(html).toContain('Ask agent');
    expect(renderToStaticMarkup(<PrivateThreadTab threads={threads({}, null)} threadKey={KEY} />)).toBe('');
    expect(controls(threads({ forward: KEY }))).not.toContain('pr-private-chip');
  });

  it('offers Ask agent only on other people’s comments, and keeps the chip of a thread behind the user’s own', () => {
    expect(controls(threads({}, null), true)).toBe('');
    const own = controls(threads(), true);
    expect(own).toContain('Local · 2 replies ↑');
    expect(own).not.toContain('Ask agent');
  });

  it('disables Ask agent once the task released its files', () => {
    expect(controls(threads({ disabledReason: 'Files released' }))).toMatch(/<button[^>]*disabled=""[^>]*title="Files released"[^>]*>Ask agent/);
  });

  it('keeps Ask agent and an editable draft available while setup blocks sending', () => {
    const source = threads({ forward: KEY, sendDisabledReason: 'Watch o/r to ask about its code.',
      setup: <button type="button">Watch repository</button>, drafts: new Map([[KEY, 'Does this address my concern?']]) }, null);
    expect(controls(source)).not.toContain('disabled=""');
    const html = forward(source);
    expect(html).toContain('Does this address my concern?');
    expect(html).toContain('Watch o/r to ask about its code.');
    expect(html).toContain('>Watch repository</button>');
    expect(html).toMatch(/<button type="button" class="is-primary" disabled=""/);
    expect(html).toContain('Please <strong>rename</strong> this.');
  });

  it('asks a first question with Ask agent, allowed empty, beside Cancel', () => {
    const html = forward(threads({ forward: KEY }, null));
    expect(html).toContain('Private thread');
    expect(html).toContain('on bob’s comment · only the agent sees this');
    expect(html).toContain('Back to comment');
    expect(html).toContain('placeholder="Ask about bob’s comment…"');
    expect(html).toMatch(/<button type="button" class="is-primary"(?![^>]*disabled)[^>]*>Ask agent<\/button>/);
    expect(html).toContain('>Cancel</button>');
    expect(html).not.toContain('Resolve locally');
  });

  it('replies once the thread has messages, with Resolve locally in Cancel’s place', () => {
    const html = forward(threads({ forward: KEY }));
    expect(html).toContain('placeholder="Reply to the agent…"');
    expect(html).toMatch(/<button type="button" class="is-primary" disabled=""[^>]*>Reply<\/button>/);
    expect(html).toContain('>Resolve locally</button>');
    expect(html).not.toContain('>Cancel</button>');
    expect(html).not.toContain('Save lesson');
    expect(html).not.toContain('Draft with agent');
  });

  it('holds the send while another question on the task is answering', () => {
    expect(forward(threads({ forward: KEY, busy: true }))).toContain('The agent is answering another question on this task');
    expect(forward(threads({ forward: KEY, busy: true, busyReason: 'The agent is answering another question on this review' })))
      .toContain('The agent is answering another question on this review');
  });

  it('shows the viewer’s GitHub avatar while keeping the agent avatar local', () => {
    const html = forward(threads({ forward: KEY }));
    expect(html).toContain('src="https://github.com/alice.png?size=44"');
    expect(html).not.toContain('https://github.com/Agent.png');
    const anonymous = renderToStaticMarkup(<PrivateThreadForward threads={threads({ forward: KEY })} threadKey={KEY}
      author="bob" avatarUrl={null} timestamp={AT} body="Please rename this." repository="o/r" />);
    expect(anonymous).toContain('aria-label="You" role="img"');
    expect(anonymous).not.toContain('https://github.com/You.png');
  });

  it('keeps an unsent question in the view state, so a remounted card shows it again', () => {
    expect(forward(threads({ forward: KEY, drafts: new Map([[KEY, 'Half a question']]) }))).toContain('Half a question');
  });

  it('shows why a released task cannot ask, with Retry and Draft with agent disabled', () => {
    const html = forward(threads({ forward: KEY, disabledReason: 'Files released' }, thread({ messages: [
      message('m1', 'USER', 'Is this right?'), message('m2', 'AGENT', 'Yes.'), message('m3', 'USER', 'Sure?', 'The agent timed out')] })), true);
    expect(html).toContain('Files released');
    expect(html).not.toContain('Private question to the agent');
    expect(html).toMatch(/<button type="button" disabled=""[^>]*>Retry<\/button>/);
    expect(html.match(/<button type="button" class="pr-private-draft" disabled=""[^>]*>Draft with agent<\/button>/g)).toHaveLength(3);
  });

  it('shows a resolved thread with Reopen instead of the composer', () => {
    const html = forward(threads({ forward: KEY }, thread({ resolved: true })));
    expect(html).toContain('The GitHub thread is unchanged.');
    expect(html).toContain('>Reopen</button>');
    expect(html).not.toContain('Reply to the agent');
  });

  it('hides the summary request and renders the verdict as plain text', () => {
    const html = forward(threads({ forward: KEY }, thread({ messages: [message('h', 'USER', SUMMARY_REQUEST), message('s', 'AGENT', SUMMARY)] })));
    expect(html).not.toContain(SUMMARY_REQUEST);
    expect(html).not.toContain('>You<');
    expect(html).toContain('Use **bold** [links](https://evil.example)');
    expect(html).not.toContain('<a ');
    expect(html).toContain('The `cache` is keyed by path.');
    expect(html).toContain('<span class="agr-pill is-warn">Partly valid</span><span class="agr-pill is-ready">Do</span><span class="agr-pill is-neutral">Small</span>');
    expect(html).toContain('<strong>Plan:</strong> Key it by id.');
  });

  it('says a failed or interrupted summary request can be retried', () => {
    const failed = forward(threads({ forward: KEY }, thread({ messages: [message('h', 'USER', SUMMARY_REQUEST, 'The agent timed out')] })));
    expect(failed).toContain('The agent timed out');
    expect(failed).toContain('>Retry</button>');
    const orphaned = forward(threads({ forward: KEY }, thread({ messages: [message('h', 'USER', SUMMARY_REQUEST)] })));
    expect(orphaned).toContain('The summary was interrupted.');
    const answering = forward(threads({ forward: KEY }, thread({ pending: true, messages: [message('h', 'USER', SUMMARY_REQUEST)] })));
    expect(answering).toContain('Agent is answering…');
    expect(answering).not.toContain('>Retry</button>');
  });

  it('offers Draft with agent only where a GitHub reply editor can take it', () => {
    const html = forward(threads({ forward: KEY }), true);
    expect(html.match(/>Draft with agent</g)).toHaveLength(4);
  });
});
