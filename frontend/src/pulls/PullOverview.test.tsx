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
import { describe, expect, it, vi } from 'vitest';
import type { PullRequestDetailDto, PullRequestDto, PullTimelinePayloadDto } from '../types';
import MarkdownBody, { markdownBlocks } from './MarkdownBody';
import PullOverview, { activeMention, appendQuotedReply, displayedMetadataPickerOptions, groupTimelineActivities, MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS, mentionCandidates, MetadataPicker, peoplePickerCandidates, relativeTime, remoteErrorMessage, replaceMention, reviewCommentLine, submitPullReaction, submitPullRequestComment } from './PullOverview';
import { canSubmitReview, hasMergeConflicts, isReviewRequestedForViewer, MergeDialog, mergeActionLabel, mergeStatus, PullDetailHeader, remotePullRequest, ReviewDialog, unresolvedReviewCount } from './PullsScreen';

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
  it('shows the backend message without Electron IPC noise', () => {
    expect(remoteErrorMessage(new Error(
      "Error invoking remote method 'prs:metadata:update': Error: Grant it access to this repository",
    ))).toBe('Grant it access to this repository');
  });

  it('toggles a reaction before refreshing the full pull request detail', async () => {
    const order: string[] = [];
    const togglePrReaction = vi.fn(async () => { order.push('write'); });
    const onRefresh = vi.fn(async () => { order.push('refresh'); });

    await submitPullReaction({ togglePrReaction }, {
      repo: 'owner/repo',
      number: 42,
      target: 'issue_comment',
      commentId: 71,
      onRefresh,
    }, 'eyes');

    expect(togglePrReaction).toHaveBeenCalledWith('owner/repo', 42, 'issue_comment', 71, 'eyes');
    expect(onRefresh).toHaveBeenCalledOnce();
    expect(order).toEqual(['write', 'refresh']);
  });

  it('applies the successful comment response without requesting a full refresh', async () => {
    const updated = detail();
    const commentPr = vi.fn(async () => updated);
    const onDetailChange = vi.fn();

    await submitPullRequestComment(
      { commentPr }, 'owner/repo', 42, 'Looks good to me', onDetailChange);

    expect(commentPr).toHaveBeenCalledWith('owner/repo', 42, 'Looks good to me');
    expect(onDetailChange).toHaveBeenCalledWith(updated);
  });

  it('wires reactions only to supported pull request targets', () => {
    const value = detail();
    value.recentActivity = [
      {
        actor: 'bob', eventType: 'commented', timestamp: '2026-08-20T09:10:00Z', body: 'Issue comment', githubId: 71,
        payload: { ...emptyPayload, reactions: [{ content: '+1', count: 1 }] },
      },
      {
        actor: 'carol', eventType: 'reviewed', timestamp: '2026-08-20T09:20:00Z', body: 'Review body', githubId: 72,
        payload: { ...emptyPayload, reactions: [{ content: 'heart', count: 1 }] },
      },
      {
        actor: 'dave', eventType: 'review_thread', timestamp: '2026-08-20T09:30:00Z', body: null, githubId: 74,
        payload: {
          ...emptyPayload,
          comments: [{
            githubId: 73,
            actor: 'dave',
            actorAvatarUrl: null,
            actorBot: false,
            timestamp: '2026-08-20T09:30:00Z',
            body: 'Inline review comment',
            htmlUrl: null,
            reviewId: 9,
            inReplyToId: null,
            reactions: [{ content: 'eyes', count: 1 }],
          }],
        },
      },
    ];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html.match(/data-reaction-target="pull_request"/g)).toHaveLength(2);
    expect(html.match(/data-reaction-target="issue_comment" data-reaction-comment-id="71"/g)).toHaveLength(2);
    expect(html.match(/data-reaction-target="review_comment" data-reaction-comment-id="73"/g)).toHaveLength(2);
    expect(html).not.toContain('data-reaction-comment-id="72"');
    expect(html).toContain('<button type="button" class="pr-reaction" aria-label="Add or remove thumbs up reaction"');
    expect(html).toContain('title="Add or remove eyes reaction"');
    expect(html).toContain('<span>❤️ 1</span>');
    expect(html).not.toContain('class="is-active" role="menuitem"');
  });

  it('renders review submission as an overlay without replacing the pull request', () => {
    const html = renderToStaticMarkup(<ReviewDialog
      pr={pullRequest()}
      detail={detail()}
      onClose={() => {}}
      onRefresh={async () => {}} />);

    expect(html).toContain('class="pr-sheet-overlay"');
    expect(html).toContain('class="pr-review-sheet" role="dialog"');
  });

  it('stops showing the progress ring after every merge check passes', () => {
    const value = detail();
    value.checkRuns = value.checkRuns.map(check => ({
      ...check,
      conclusion: 'success',
      category: 'successful',
    }));
    const html = renderToStaticMarkup(<MergeDialog
      pr={pullRequest()}
      detail={value}
      mergeQueue
      onClose={() => {}}
      onRefresh={async () => {}} />);

    expect(html).toContain('All checks have passed');
    expect(html).toContain('class="pr-merge-success-icon"');
    expect(html).not.toContain('class="pr-merge-ready-icon"');
  });

  it('blocks merging without a progress ring after checks fail', () => {
    const value = detail();
    const html = renderToStaticMarkup(<MergeDialog
      pr={pullRequest()}
      detail={value}
      mergeQueue={false}
      onClose={() => {}}
      onRefresh={async () => {}} />);

    expect(mergeStatus(value).state).toBe('failed');
    expect(html).toContain('Merge blocked');
    expect(html).toContain('Some checks were not successful');
    expect(html).not.toContain('class="pr-merge-ready-icon"');
    expect(html).toContain('<button class="is-primary" type="button" disabled="">');
  });

  it('renders the open header, chronological variants, resolved thread and grouped checks', () => {
    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={detail()} onRefresh={async () => {}} />);

    expect(html).toContain('<div class="pr-overview__title"><h1>Example pull request</h1><div class="pr-overview__number"><span>#42</span>');
    expect(html).toContain(' Open</span>');
    expect(html).toContain('main');
    expect(html).toContain('feature/overview');
    expect(html).toContain('aria-label="Open on GitHub" title="Open on GitHub"');
    expect(html).toContain('src="https://github.com/github.png?size=36"');
    expect(html.indexOf('feature/overview')).toBeLessThan(html.indexOf('aria-label="Open on GitHub"'));
    expect(html.indexOf('added <span')).toBeLessThan(html.indexOf('changed the title from'));
    expect(html).toContain('src/Example.java:L12');
    expect(html).toContain('Resolved');
    expect(html).toContain('Failing (1)');
    expect(html).toContain('Successful (1)');
    expect(html).toContain('class="pr-reaction-picker"');
    expect(html).toContain('class="pr-reaction-picker is-compact"');
    expect(html).toContain('aria-expanded="false"');
    expect(html).toContain('aria-label="Add or remove thumbs up reaction"');
    expect(html).toContain('aria-label="More actions"');
    expect(html).toContain('>Close pull request</button>');
    expect(html).not.toContain('pr-composer__tools');
    expect(html).toContain('>Copy link</button>');
    expect(html).toContain('>Copy Markdown</button>');
    expect(html).toContain('>Quote reply</button>');
    expect(html).toContain('M10.5 7.75a2.75 2.75');
  });

  it('shows running checks in yellow and completed check times', () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-08-20T10:00:00Z'));
    const value = detail();
    value.ciStatus = 'PENDING';
    value.checkRuns = [
      { ...value.checkRuns[0], status: 'in_progress', conclusion: null, completedAt: null, category: 'pending' },
      { ...value.checkRuns[1], completedAt: '2026-08-20T09:58:00Z' },
    ];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);
    now.mockRestore();

    expect(html).toContain('<header><span class="is-pending"></span><strong>Checks are in progress</strong>');
    expect(html).not.toContain('<header><span class="is-failure">');
    expect(html).toContain('<time dateTime="2026-08-20T09:58:00Z">Finished 2m ago</time>');
  });

  it('offers failed GitHub Actions reruns to viewers with write access', () => {
    const value = detail();
    value.viewerCanWrite = true;
    value.checkRuns[0].detailsUrl = 'https://github.com/owner/repo/actions/runs/101/job/301';

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).toContain('Rerun failed tests');
  });

  it('groups adjacent label events from the same actor into one GitHub timeline row', () => {
    const value = detail();
    value.recentActivity = [
      {
        actor: 'github-actions[bot]', eventType: 'labeled', timestamp: '2026-08-20T09:10:00Z', body: null, githubId: 1,
        payload: { ...emptyPayload, actorBot: true, label: 'snowflake', labelColor: 'd4c5f9' },
      },
      {
        actor: 'github-actions[bot]', eventType: 'labeled', timestamp: '2026-08-20T09:11:00Z', body: null, githubId: 2,
        payload: { ...emptyPayload, actorBot: true, label: 'clickhouse', labelColor: 'd4c5f9' },
      },
      {
        actor: 'alice', eventType: 'renamed', timestamp: '2026-08-20T09:12:00Z', body: null, githubId: 3,
        payload: { ...emptyPayload, renameFrom: 'Old title', renameTo: 'Example pull request' },
      },
    ];

    const groups = groupTimelineActivities(value.recentActivity);
    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(groups).toHaveLength(2);
    expect(groups[0].map(activity => activity.payload.label)).toEqual(['snowflake', 'clickhouse']);
    expect(html.match(/class="pr-event pr-label-event"/g)).toHaveLength(1);
    expect(html).toContain('class="pr-label-event__marker"');
    expect(html).toContain('snowflake');
    expect(html).toContain('clickhouse');
    expect(html).toContain('<span>labels</span>');
  });

  it('renders a regular reply action without a duplicate footer reaction menu', () => {
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
    expect(html).toContain('>Reply</button><button class="pr-thread-resolve"');
    expect(html).toContain('>Resolve</button>');
    expect(html).not.toContain('<strong>Reply</strong>');
  });

  it('shows only the commented diff line with its number and state', () => {
    const value = detail();
    value.recentActivity = [{
      actor: 'bob', eventType: 'review_thread', timestamp: '2026-08-20T09:35:00Z', body: null, githubId: 4,
      payload: {
        ...emptyPayload,
        path: 'src/PageSourceProvider.java',
        diffHunk: '@@ -41,4 +41,18 @@ default long getMemoryUsage()\n {\n+    MemoryUsageTracker createTracker();\n+\n+    interface MemoryUsageTracker',
        originalLine: 44,
        side: 'RIGHT',
        outdated: true,
      },
    }];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(reviewCommentLine('@@ -7,2 +7 @@\n keep\n-old', 8, 'LEFT')).toEqual({ lineNumber: 8, prefix: '-', text: 'old' });
    expect(html).toContain('<code>src/PageSourceProvider.java</code><span class="pr-thread-outdated">Outdated</span>');
    expect(html).toContain('class="pr-commented-code-line is-add"');
    expect(html).toContain('pr-commented-code-line__number">44</span>');
    expect(html).toContain('<code>    interface MemoryUsageTracker</code>');
    expect(html).not.toContain('createTracker');
  });

  it('offers reply and unresolve actions inside a resolved review thread', () => {
    const value = detail();
    value.recentActivity = [{
      actor: 'bob', eventType: 'review_thread', timestamp: '2026-08-20T09:35:00Z', body: null, githubId: 4,
      payload: {
        ...emptyPayload,
        resolved: true,
        path: 'src/Example.java',
        line: 18,
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

    expect(html).toContain('class="pr-thread-row__comments"');
    expect(html).toContain('class="pr-thread-reply__open"');
    expect(html).toContain('>Unresolve</button>');
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

  it('renders GitHub Markdown tables instead of exposing their pipe syntax', () => {
    const body = '| Area | Existing bundled job | New longest job | Improvement |\n'
      + '|---|---:|---:|---:|\n'
      + '| Overall critical path (Iceberg) | 38:05 | 24:35 | 13:30 (35%) |\n'
      + '| Authentication and clients | 32:18 | 12:57 | 19:21 (60%) |';

    expect(markdownBlocks(body)[0]).toEqual({
      kind: 'table',
      headers: ['Area', 'Existing bundled job', 'New longest job', 'Improvement'],
      aligns: ['left', 'right', 'right', 'right'],
      rows: [
        ['Overall critical path (Iceberg)', '38:05', '24:35', '13:30 (35%)'],
        ['Authentication and clients', '32:18', '12:57', '19:21 (60%)'],
      ],
    });

    const html = renderToStaticMarkup(<MarkdownBody body={body} />);
    expect(html).toContain('<div class="md-table-scroll"><table><thead>');
    expect(html).toContain('<th style="text-align:right">Improvement</th>');
    expect(html).toContain('<td style="text-align:right">13:30 (35%)</td>');
    expect(html).not.toContain('|---|');
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

  it('attaches review threads to their matching standalone review event', () => {
    const value = detail();
    value.recentActivity = [
      {
        actor: 'chenjian2664', eventType: 'review_thread', timestamp: '2026-08-13T08:36:16Z', body: null, githubId: 3773729721,
        payload: {
          ...emptyPayload,
          path: 'plugin/trino-oracle/src/main/java/io/trino/plugin/oracle/RewriteCast.java',
          line: 67,
          resolved: true,
          outdated: true,
          comments: [
            {
              githubId: 3773729721,
              actor: 'chenjian2664',
              actorAvatarUrl: 'https://example.com/chenjian2664.png',
              actorBot: false,
              timestamp: '2026-08-13T08:36:16Z',
              body: 'The legacy behavior needs another condition.',
              htmlUrl: null,
              reviewId: 4925022844,
              inReplyToId: null,
              reactions: [],
            },
            {
              githubId: 3773740000,
              actor: 'finn-ep',
              actorAvatarUrl: 'https://example.com/finn-ep.png',
              actorBot: false,
              timestamp: '2026-08-13T09:00:00Z',
              body: 'Can you add a test?',
              htmlUrl: null,
              reviewId: 4925100000,
              inReplyToId: 3773729721,
              reactions: [],
            },
          ],
        },
      },
      {
        actor: 'chenjian2664', eventType: 'reviewed', timestamp: '2026-08-13T08:49:10Z', body: '', githubId: 4925022844,
        payload: { ...emptyPayload, actorAvatarUrl: 'https://example.com/chenjian2664.png', state: 'commented' },
      },
    ];

    const groups = groupTimelineActivities(value.recentActivity);
    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(groups).toHaveLength(1);
    expect(groups[0].map(activity => activity.eventType)).toEqual(['reviewed', 'review_thread']);
    expect(html).toContain('class="pr-review-group"');
    expect(html).toContain('chenjian2664</strong><span>reviewed</span>');
    expect(html).toContain('src="https://example.com/chenjian2664.png"');
    expect(html).toContain('RewriteCast.java:L67');
    expect(html).toContain('Can you add a test?');
  });

  it('omits the Markdown fence label and highlights its syntax', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'```\nplain\n```\n\n```markdown\n## Section\n* Fix some things. ({issue}`issuenumber`)\n```'} />);

    expect(html).toContain('<span>Plain text</span>');
    expect(html).not.toContain('<span>markdown</span>');
    expect(html).toContain('<span class="md-code-markdown-heading">## Section</span>');
    expect(html).toContain('<span class="md-code-markdown-inline">`issuenumber`</span>');
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

  it('turns bare GitHub URLs into local references that can resolve a title', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'follows https://github.com/owner/repo/issues/42'} />);

    expect(html).toContain('class="md-reference is-issue"');
    expect(html).toContain('href="https://github.com/owner/repo/issues/42"');
    expect(html).toContain('owner/repo <small>#42</small>');
  });

  it('links cross-referenced timeline events to the source preview', () => {
    const value = detail();
    value.recentActivity = [{
      actor: 'bob', eventType: 'cross-referenced', timestamp: '2026-08-20T09:10:00Z', body: null, githubId: 21,
      payload: {
        ...emptyPayload,
        sourceTitle: 'Related issue',
        sourceUrl: 'https://github.com/owner/repo/issues/7',
        sourceNumber: 7,
      },
    }];

    const html = renderToStaticMarkup(<PullOverview pr={pullRequest()} detail={value} onRefresh={async () => {}} />);

    expect(html).toContain('mentioned this in <a class="md-reference is-issue"');
    expect(html).toContain('href="https://github.com/owner/repo/issues/7"');
    expect(html).toContain('Related issue</a>');
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
    value.reviewers[0] = { ...value.reviewers[0], avatarUrl: 'https://example.com/remote-bob.png' };
    value.assignees = ['carol'];
    const users = [
      { login: 'alice', avatarUrl: 'https://example.com/alice.png', bot: false },
      { login: 'bob', avatarUrl: '/api/avatars/bob', bot: true },
      { login: 'carol', avatarUrl: 'https://example.com/carol.png', bot: false },
    ];

    expect(peoplePickerCandidates(value, users, 'reviewers').map(user => `${user.login}:${user.selected}:${user.bot}`))
      .toEqual(['bob:true:true', 'alice:false:false', 'carol:false:false']);
    expect(peoplePickerCandidates(value, users, 'reviewers')[0]?.avatarUrl).toBe('/api/avatars/bob');
    expect(peoplePickerCandidates(value, users, 'assignees').map(user => `${user.login}:${user.selected}`))
      .toEqual(['carol:true', 'alice:false', 'bob:false']);
  });

  it('does not fetch a remote fallback when a cached picker avatar is missing', () => {
    const html = renderToStaticMarkup(<MetadataPicker ariaLabel="reviewers" placeholder="Search users..."
      query="" setQuery={() => {}} options={[{ key: 'alice', label: 'alice', login: 'alice', avatarUrl: null, selected: false }]}
      loading={false} updating={false} loadingText="Loading…" emptyText="No users" onToggle={() => {}} />);

    expect(html).toContain('avatar--fallback');
    expect(html).not.toContain('github.com');
  });

  it('caps unselected people while retaining every selected match', () => {
    const options = Array.from({ length: MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS + 5 }, (_, index) => ({
      key: `user-${index}`,
      label: `user-${index}`,
      login: `user-${index}`,
      selected: index >= MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS + 3,
    }));

    const displayed = displayedMetadataPickerOptions(options);

    expect(displayed.filter(option => !option.selected)).toHaveLength(MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS);
    expect(displayed.filter(option => option.selected).map(option => option.login))
      .toEqual([`user-${MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS + 3}`, `user-${MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS + 4}`]);
    expect(displayed.some(option => option.login === `user-${MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS + 2}`)).toBe(false);
  });

  it('does not cap label options', () => {
    const options = Array.from({ length: MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS + 5 }, (_, index) => ({
      key: `label-${index}`,
      label: `label-${index}`,
      selected: false,
    }));

    expect(displayedMetadataPickerOptions(options)).toHaveLength(options.length);
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

  it('appends a GitHub-style quote without replacing a draft', () => {
    expect(appendQuotedReply('', 'First line\n\nLast line')).toBe('> First line\n> \n> Last line\n\n');
    expect(appendQuotedReply('Already writing  \n', 'Quoted text')).toBe('Already writing\n\n> Quoted text\n\n');
  });

  it('selects the merge action and counts unresolved review threads', () => {
    const value = detail();
    value.recentActivity.push(
      { actor: 'bob', eventType: 'review_thread', timestamp: null, body: null, githubId: 20, payload: emptyPayload },
      { actor: 'carol', eventType: 'review_thread', timestamp: null, body: null, githubId: 21, payload: { ...emptyPayload, resolved: true } },
    );

    expect(mergeActionLabel(true)).toBe('Merge when ready');
    expect(mergeActionLabel(false)).toBe('Rebase and merge');
    expect(mergeActionLabel(undefined)).toBe('Loading merge status');
    expect(mergeActionLabel(null)).toBe('Merge status unavailable');
    expect(mergeActionLabel(true, true)).toBe('Conflicts');
    expect(unresolvedReviewCount(value)).toBe(1);
  });

  it('offers review actions only for another author\'s open pull request', () => {
    const pr = pullRequest();
    const value = detail();

    expect(canSubmitReview(pr, value)).toBe(false);
    expect(canSubmitReview({ ...pr, origin: 'REVIEW_REQUESTED' }, value)).toBe(true);
    expect(canSubmitReview({ ...pr, origin: 'OTHER', handledAction: 'COMMENTED' }, value)).toBe(true);
    expect(canSubmitReview({ ...pr, origin: 'REVIEW_REQUESTED' }, { ...value, state: 'merged' })).toBe(false);
    expect(hasMergeConflicts({ ...value, mergeable: false, mergeableState: 'dirty' })).toBe(true);
    expect(hasMergeConflicts({ ...value, mergeable: false, mergeableState: 'blocked' })).toBe(false);
    expect(remotePullRequest(value, 'bob').origin).toBe('REVIEW_REQUESTED');
    expect(remotePullRequest(value, 'carol').origin).toBe('OTHER');
  });

  it('keeps icon, review and merge actions in the compact detail header', () => {
    const value = detail();
    value.viewerCanWrite = true;
    const html = renderToStaticMarkup(<PullDetailHeader
      pr={{ ...pullRequest(), origin: 'REVIEW_REQUESTED' }}
      detail={value}
      tab="changes"
      onTab={() => {}}
      onRefresh={async () => {}}
      pendingComments={[]}
      zoomed={false}
      onZoom={() => {}}
      onPendingCommentsChange={() => {}} />);

    expect(html).toContain('class="pull-detail__review-action"');
    expect(html).toContain('>Submit review</button>');
    expect(html).toContain('class="pull-detail__merge-action is-conflicts"');
    expect(html).toContain('class="pull-detail__pr-tab is-open"');
    expect(html).toContain('PR #42');
    expect(html).toContain('aria-pressed="false"');
    expect(html).toContain('aria-pressed="true"');
    expect(html).toContain('class="pull-detail__change-count is-addition">+10</span>');
    expect(html).toContain('class="pull-detail__change-count is-deletion">−2</span>');
    expect(html).toContain('aria-label="Zoom pull request details" aria-pressed="false"');
    expect(html).toContain('aria-label="Refresh"');
    expect(html).not.toContain('requested your review');
    expect(html).not.toContain('Open on GitHub');
  });

  it('keeps merge available independently of review eligibility', () => {
    const value = detail();
    value.viewerCanWrite = true;
    const html = renderToStaticMarkup(<PullDetailHeader
      pr={pullRequest()}
      detail={value}
      tab="overview"
      onTab={() => {}}
      onRefresh={async () => {}}
      pendingComments={[]}
      zoomed={false}
      onZoom={() => {}}
      onPendingCommentsChange={() => {}} />);

    expect(html).not.toContain('class="pull-detail__review-action"');
    expect(html).toContain('class="pull-detail__merge-action');
  });

  it('offers a restore control when pull request details are zoomed', () => {
    const html = renderToStaticMarkup(<PullDetailHeader
      pr={pullRequest()}
      detail={detail()}
      tab="overview"
      onTab={() => {}}
      onRefresh={async () => {}}
      pendingComments={[]}
      zoomed
      onZoom={() => {}}
      onPendingCommentsChange={() => {}} />);

    expect(html).toContain('aria-label="Restore pull request details" aria-pressed="true"');
  });

  it.each([
    ['open', false, 'open'],
    ['open', true, 'draft'],
    ['closed', false, 'closed'],
    ['merged', false, 'merged'],
  ] as const)('uses the %s pull request icon state', (state, draft, iconState) => {
    const value = { ...detail(), state, draft };
    const html = renderToStaticMarkup(<PullDetailHeader
      pr={pullRequest()}
      detail={value}
      tab="overview"
      onTab={() => {}}
      onRefresh={async () => {}}
      pendingComments={[]}
      zoomed={false}
      onZoom={() => {}}
      onPendingCommentsChange={() => {}} />);

    expect(html).toContain(`pull-detail__pr-tab is-${iconState} is-active`);
  });

  it('identifies when the signed-in user is requested for review', () => {
    const value = detail();

    expect(isReviewRequestedForViewer(value, 'bob')).toBe(true);
    expect(isReviewRequestedForViewer(value, 'carol')).toBe(false);
    expect(isReviewRequestedForViewer({
      ...value,
      reviewers: [{ login: 'bob', avatarUrl: null, state: 'APPROVED' }],
    }, 'bob')).toBe(false);
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
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: ['bob'],
    ciStatus: 'FAILING',
    additions: 10,
    deletions: 2,
    commentCount: 2,
    attentionReason: null,
    state: 'open',
    mergeable: true,
    mergeableState: 'clean',
    headPushedAt: null,
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
    mergeable: true,
    mergeableState: 'clean',
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
      { githubId: 10, name: 'build', status: 'completed', conclusion: 'failure', detailsUrl: null, source: 'check_run', completedAt: null, category: 'failing' },
      { githubId: 11, name: 'license', status: 'completed', conclusion: 'success', detailsUrl: null, source: 'status', completedAt: null, category: 'successful' },
    ],
    unavailableSections: [],
    viewerCanWrite: false,
  };
}
