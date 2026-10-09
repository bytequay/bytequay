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
import { ANSWERING_ELSEWHERE, isSummaryRequest, parseCommentSummary, SUMMARY_REQUEST, type DiscussionMessageDto, type TaskDiscussionDto } from '../agents/discussions/contracts';
import type { TaskDiscussions } from '../agents/discussions/useTaskDiscussions';
import {
  commentExcerpt, hiddenThreadCount, PrivateThreadControls, PrivateThreadForward, privateThreadKey, privateThreadSource, PrivateThreadsToggle,
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
    providerLane: 'codex', version: 1, createdAt: AT, updatedAt: AT, comment: { repo: 'o/r', number: 12, key: KEY },
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
  it('labels the icon toggle and exposes visibility and the hidden count', () => {
    const html = renderToStaticMarkup(<PrivateThreadsToggle threads={threads({ show: false })} />);
    expect(html).toContain('aria-label="Private threads" aria-pressed="false"');
    expect(html).toContain('title="Show private threads (1 hidden)"');
    const shown = renderToStaticMarkup(<PrivateThreadsToggle threads={threads()} />);
    expect(shown).toContain('aria-pressed="true"');
    expect(shown).toContain('title="Hide private threads"');
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

  it('replaces the chip and tab with a hidden marker under Hide', () => {
    const hidden = threads({ show: false });
    expect(controls(hidden)).toContain('Private thread hidden');
    expect(controls(hidden)).not.toContain('replies');
    expect(renderToStaticMarkup(<PrivateThreadTab threads={hidden} threadKey={KEY} />)).toBe('');
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
