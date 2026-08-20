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
import type { PullRequestDetailDto, PullRequestDto, PullTimelinePayloadDto } from '../types';
import MarkdownBody, { markdownBlocks } from './MarkdownBody';
import PullOverview, { activeMention, mentionCandidates, peoplePickerCandidates, relativeTime, replaceMention } from './PullOverview';
import { mergeActionLabel, unresolvedReviewCount } from './PullsScreen';

const emptyPayload: PullTimelinePayloadDto = {
  actorAvatarUrl: null,
  actorBot: false,
  state: null,
  htmlUrl: null,
  label: null,
  labelColor: null,
  renameFrom: null,
  renameTo: null,
  commitSha: null,
  commitMessage: null,
  sourceTitle: null,
  sourceUrl: null,
  sourceNumber: null,
  milestoneTitle: null,
  path: null,
  diffHunk: null,
  line: null,
  originalLine: null,
  startLine: null,
  originalStartLine: null,
  side: null,
  resolved: false,
  outdated: false,
  reactions: [],
  comments: [],
};

describe('PullOverview', () => {
  it('renders the open header, chronological variants, resolved thread and grouped checks', () => {
    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={detail()} onRefresh={async () => {}} />);

    expect(html).toContain('Example pull request <span>#42</span>');
    expect(html).toContain(' Open</span>');
    expect(html).toContain('main');
    expect(html).toContain('feature/overview');
    expect(html.indexOf('added <span')).toBeLessThan(html.indexOf('changed the title from'));
    expect(html).toContain('src/Example.java:L12');
    expect(html).toContain('Resolved');
    expect(html).toContain('Failing (1)');
    expect(html).toContain('Successful (1)');
    expect(html).toContain('class="pr-reaction-picker"');
    expect(html).toContain('class="pr-reaction-picker is-compact"');
    expect(html).toContain('aria-expanded="false"');
    expect(html).toContain('aria-label="Add thumbs up reaction"');
    expect(html).toContain('M10.5 7.75a2.75 2.75');
  });

  it('renders a regular reply action and reaction menu for an unresolved review thread', () => {
    const value = detail();
    value.recentActivity = [{
      actor: 'bob', eventType: 'review_thread', timestamp: '2026-08-20T09:35:00Z', body: null, githubId: 4,
      payload: {
        ...emptyPayload,
        path: 'src/Example.java',
        line: 18,
        htmlUrl: 'https://github.com/owner/repo/pull/42#discussion_r99',
        comments: [{
          githubId: 99,
          actor: 'bob',
          actorAvatarUrl: null,
          actorBot: false,
          timestamp: '2026-08-20T09:35:00Z',
          body: 'Please update this.',
          htmlUrl: null,
          reviewId: 9,
          inReplyToId: null,
          reactions: [],
        }],
      },
    }];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).toContain('class="pr-thread-reply__open"');
    expect(html).toContain('class="pr-thread-comment__menu"');
    expect(html).not.toContain('<strong>Reply</strong>');
  });

  it('parses the markdown blocks used by GitHub descriptions', () => {
    expect(markdownBlocks('# Heading\n\nText with `code`.\n\n- one\n- two\n\n```java\nrun();\n```'))
      .toEqual([
        { kind: 'heading', level: 1, text: 'Heading' },
        { kind: 'paragraph', text: 'Text with `code`.' },
        { kind: 'list', ordered: false, items: ['one', 'two'] },
        { kind: 'code', language: 'java', text: 'run();' },
      ]);
  });

  it('renders GitHub details and anchor HTML without exposing raw tags', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'> [!NOTE]\n> Review coverage is partial.\n\n<details><summary>Show files</summary>hidden table</details>\n<a href="/owner/repo">Learn more</a>'} />);

    expect(html).toContain('<aside class="md-alert is-note"><strong>ⓘ Note</strong>');
    expect(html).toContain('<details class="md-details"><summary>Show files</summary>');
    expect(html).toContain('href="https://github.com/owner/repo"');
    expect(html).not.toContain('&lt;details');
    expect(html).not.toContain('&lt;a href');
  });

  it('renders nested details, linked image badges, and underscore emphasis', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'[![Review Change Stack](https://example.com/badge.svg)](https://example.com/review)\n\n<details><summary>Recent review info</summary><details><summary>Commits</summary>hidden commit</details>hidden availability</details>\n\n**Merge Risk:** _⚪ Minimal_'} />);

    expect(html).toContain('<a class="md-image-link" href="https://example.com/review"><img src="https://example.com/badge.svg" alt="Review Change Stack"/></a>');
    expect(html).toContain('<summary>Recent review info</summary><div class="markdown-body"><details class="md-details"><summary>Commits</summary>');
    expect(html).toContain('<strong>Merge Risk:</strong> <em>⚪ Minimal</em>');
  });

  it('suppresses empty comment-review wrappers and keeps verdict-only reviews compact', () => {
    const value = detail();
    value.recentActivity = [
      {
        actor: 'fion-path', eventType: 'reviewed', timestamp: '2026-08-20T09:10:00Z', body: '', githubId: 10,
        payload: { ...emptyPayload, state: 'commented' },
      },
      {
        actor: 'ezra-b', eventType: 'reviewed', timestamp: '2026-08-20T09:20:00Z', body: '', githubId: 11,
        payload: { ...emptyPayload, state: 'approved' },
      },
    ];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).not.toContain('Reviewed these changes.');
    expect(html).not.toContain('fion-path');
    expect(html).toContain('ezra-b');
    expect(html).toContain('Approved');
    expect(html).toContain('pr-review-event');
  });

  it('shows the code-fence language header used by the reference', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'```\nplain\n```\n\n```markdown\n# title\n```'} />);

    expect(html).toContain('<span>Plain text</span>');
    expect(html).toContain('<span>markdown</span>');
  });

  it('renders queued state, enriched GitHub references, and description reactions', () => {
    const value = detail();
    value.body = '- [Linked issue #7](https://github.com/owner/repo/issues/7)\n- [Linked pull #8](https://github.com/owner/repo/pull/8)';
    value.recentActivity = [
      { actor: 'alice', eventType: 'added_to_merge_queue', timestamp: '2026-08-20T09:00:00Z', body: null, githubId: 4, payload: emptyPayload },
      { actor: '', eventType: 'description_reactions', timestamp: null, body: null, githubId: null, payload: { ...emptyPayload, reactions: [{ content: '+1', count: 2 }, { content: 'eyes', count: 1 }] } },
    ];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).toContain('is-queued');
    expect(html).toContain('Queued');
    expect(html).toContain('md-reference is-issue');
    expect(html).toContain('Linked issue <small>#7</small>');
    expect(html).toContain('md-reference is-pull');
    expect(html).toContain('href="https://github.com/owner/repo/pull/8"');
    expect(html).toContain('Linked pull <small>#8</small>');
    expect(html).toContain('👍 2');
    expect(html).toContain('👀 1');
    expect(html).not.toContain('description reactions');
  });

  it('shows reviewer identities below three people and a count from three people', () => {
    const value = detail();
    value.reviewers = [
      { login: 'bob', avatarUrl: 'https://example.com/bob.png', state: 'APPROVED' },
      { login: 'carol', avatarUrl: 'https://example.com/carol.png', state: 'PENDING' },
    ];

    const twoReviewers = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);
    expect(twoReviewers).toContain('alt="bob"');
    expect(twoReviewers).toContain('alt="carol"');
    expect(twoReviewers).not.toContain('2 people');

    value.reviewers.push({ login: 'dave', avatarUrl: 'https://example.com/dave.png', state: 'PENDING' });
    const threeReviewers = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);
    expect(threeReviewers).toContain('<small>Reviewers</small>3 people');
    expect(threeReviewers).not.toContain('alt="bob"');
  });

  it('pins selected reviewers and assignees before repository users', () => {
    const value = detail();
    value.assignees = ['carol'];
    const users = [
      { login: 'alice', avatarUrl: 'https://example.com/alice.png', bot: false },
      { login: 'bob', avatarUrl: 'https://example.com/bob.png', bot: true },
      { login: 'carol', avatarUrl: 'https://example.com/carol.png', bot: false },
    ];

    expect(peoplePickerCandidates(value, users, 'reviewers').map(user => `${user.login}:${user.selected}:${user.bot}`))
      .toEqual(['bob:true:true', 'alice:false:false', 'carol:false:false']);
    expect(peoplePickerCandidates(value, users, 'assignees').map(user => `${user.login}:${user.selected}`))
      .toEqual(['carol:true', 'alice:false', 'bob:false']);
  });

  it('formats compact relative timestamps', () => {
    expect(relativeTime('2026-08-20T09:58:00Z', Date.parse('2026-08-20T10:00:00Z'))).toBe('2m ago');
  });

  it('suggests unique pull-request participants and replaces the active mention', () => {
    const value = detail();
    value.recentActivity.push({
      actor: 'carol', eventType: 'commented', timestamp: null, body: 'hello', githubId: 12,
      payload: { ...emptyPayload, actorAvatarUrl: 'https://example.com/carol.png' },
    });
    value.recentActivity.push({
      actor: 'alice', eventType: 'commented', timestamp: null, body: 'again', githubId: 13,
      payload: emptyPayload,
    });
    value.recentActivity.push({
      actor: 'Pia Fenwick', eventType: 'commented', timestamp: null, body: 'display name', githubId: 14,
      payload: emptyPayload,
    });

    expect(mentionCandidates(value).map(candidate => candidate.login)).toEqual(['alice', 'bob', 'github-actions', 'carol']);
    const mention = activeMention('Thanks @ca', 10);
    expect(mention).toEqual({ start: 7, query: 'ca' });
    if (!mention) throw new Error('Expected an active mention');
    expect(replaceMention('Thanks @ca!', mention, 'carol')).toEqual({ value: 'Thanks @carol !', caret: 14 });
    expect(activeMention('mail@example.com', 16)).toBeNull();
  });

  it('selects the merge action and counts unresolved review threads', () => {
    const value = detail();
    value.recentActivity.push(
      { actor: 'bob', eventType: 'review_thread', timestamp: null, body: null, githubId: 20, payload: emptyPayload },
      { actor: 'carol', eventType: 'review_thread', timestamp: null, body: null, githubId: 21, payload: { ...emptyPayload, resolved: true } },
    );

    expect(mergeActionLabel(true)).toBe('Merge when ready');
    expect(mergeActionLabel(false)).toBe('Rebase and merge');
    expect(unresolvedReviewCount(value)).toBe(1);
  });
});

function pullRequest(): PullRequestDto {
  return {
    id: 1,
    repo: 'owner/repo',
    number: 42,
    title: 'Example pull request',
    author: 'alice',
    htmlUrl: 'https://github.com/owner/repo/pull/42',
    createdAt: '2026-08-20T09:00:00Z',
    updatedAt: '2026-08-20T10:00:00Z',
    origin: 'AUTHORED',
    labels: ['docs'],
    labelColors: { docs: 'd4c5f9' },
    draft: false,
    requestedReviewers: ['bob'],
    ciStatus: 'FAILING',
    additions: 10,
    deletions: 2,
    commentCount: 2,
    attentionReason: null,
    state: 'open',
    mergeable: true,
    mergeableState: 'clean',
    headRef: 'feature/overview',
  };
}

function detail(): PullRequestDetailDto {
  return {
    repo: 'owner/repo',
    number: 42,
    title: 'Example pull request',
    htmlUrl: 'https://github.com/owner/repo/pull/42',
    state: 'open',
    draft: false,
    author: 'alice',
    authorAvatarUrl: null,
    createdAt: '2026-08-20T09:00:00Z',
    updatedAt: '2026-08-20T10:00:00Z',
    mergedAt: null,
    baseRef: 'main',
    headRef: 'feature/overview',
    body: '# Description\n\nAdds the overview.',
    labels: [{ name: 'docs', color: 'd4c5f9' }],
    assignees: [],
    milestone: null,
    reviewers: [{ login: 'bob', avatarUrl: null, state: 'PENDING' }],
    additions: 10,
    deletions: 2,
    changedFiles: 2,
    approvalCount: 0,
    ciStatus: 'FAILING',
    recentActivity: [
      {
        actor: 'github-actions[bot]', eventType: 'labeled', timestamp: '2026-08-20T09:10:00Z', body: null, githubId: 1,
        payload: { ...emptyPayload, actorBot: true, label: 'docs', labelColor: 'd4c5f9' },
      },
      {
        actor: 'alice', eventType: 'renamed', timestamp: '2026-08-20T09:20:00Z', body: null, githubId: 2,
        payload: { ...emptyPayload, renameFrom: 'Old title', renameTo: 'Example pull request' },
      },
      {
        actor: 'alice', eventType: 'committed', timestamp: '2026-08-20T09:25:00Z', body: null, githubId: 20,
        payload: { ...emptyPayload, commitSha: 'cd75ed1', commitMessage: 'Update pull request overview' },
      },
      {
        actor: 'bob', eventType: 'review_thread', timestamp: '2026-08-20T09:30:00Z', body: null, githubId: 3,
        payload: { ...emptyPayload, resolved: true, path: 'src/Example.java', line: 12 },
      },
    ],
    checkRuns: [
      { githubId: 10, name: 'build', status: 'completed', conclusion: 'failure', detailsUrl: null, source: 'check_run', category: 'failing' },
      { githubId: 11, name: 'license', status: 'completed', conclusion: 'success', detailsUrl: null, source: 'status', category: 'successful' },
    ],
    unavailableSections: [],
    viewerCanWrite: false,
  };
}
