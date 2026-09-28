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
import { afterEach, describe, expect, it, vi } from 'vitest';
import AttentionList from './AttentionList';
import { taskAttentionItem } from '../agents/attention';
import { previewTask } from '../agents/fixtures/previewBridge';
import { jiraAttentionItems } from '../jira/attention';
import { jiraIssue } from '../jira/fixtures/jira';
import { attentionCards, handledEntry, visibleAttention, type AttentionItem, type Handled } from './attention';
import type { PullRequestDto } from '../types';
import * as teamHooks from '../teams/useTeams';

function pullRequest(number: number, latestActivity: PullRequestDto['latestActivity'] = null): PullRequestDto {
  return {
    id: number, repo: 'trinodb/trino', number, title: `Change ${number}`, author: 'alice',
    htmlUrl: `https://github.com/trinodb/trino/pull/${number}`, createdAt: null, updatedAt: '2026-08-28T00:00:00Z',
    origin: 'REVIEW_REQUESTED', labels: [], labelColors: null, draft: false, viewedAt: null, reviewedAt: null,
    handledAction: null, requestedReviewers: ['viewer'], ciStatus: null, additions: 0, deletions: 0, commentCount: 1,
    state: 'open', mergeable: null, mergeableState: null, headPushedAt: null, headRef: 'branch', latestActivity,
  };
}

function prItem(number: number, kind: AttentionItem['kind'] = 'review', latestActivity: PullRequestDto['latestActivity'] = null): AttentionItem {
  const pr = pullRequest(number, latestActivity);
  return {
    key: `trinodb/trino#${number}`, type: 'pr', kind, repo: 'trinodb/trino', number, title: pr.title, author: 'alice',
    authorAvatarUrl: null, createdAt: null, updatedAt: pr.updatedAt, labels: [], pr, body: '', commentCount: 1, workspaceId: null,
  };
}

function issueItem(number: number): AttentionItem {
  return {
    key: `trinodb/trino#${number}`, type: 'issue', kind: 'assigned', repo: 'trinodb/trino', number, title: `Issue ${number}`,
    author: 'finn-ep', authorAvatarUrl: null, createdAt: null, updatedAt: '2026-08-28T00:00:00Z', labels: ['bug', 'iceberg'],
    pr: null, body: '', commentCount: 0, workspaceId: 'workspace-1',
  };
}

function list(candidates: AttentionItem[], handled: Handled = {}, variant: 'home' | 'workspace' = 'home', jiraConnected = true): string {
  return renderToStaticMarkup(
    <AttentionList variant={variant} candidates={candidates} handled={handled} viewerLogin="viewer" jiraConnected={jiraConnected}
      onHandledChange={() => {}} onOpen={() => {}} onStartIssue={() => {}} />);
}

describe('AttentionList', () => {
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('shows a private-team reason and keeps Team-only independent from the source tabs', () => {
    vi.spyOn(teamHooks, 'useTeams').mockReturnValue({ directory: { accountLogin: 'viewer', circles: [{
      id: 'trusted', name: 'Maintainers', description: '', repositories: ['trinodb/trino'], members: [{ githubId: 1, login: 'alice', name: 'Alice', avatarUrl: '' }],
    }] }, loading: false, error: null });
    const items = [issueItem(7), prItem(1)];
    const html = list(items);
    expect(html).toContain('aria-pressed="false">Team only</button>');
    expect(html).toContain('title="Your private circle: Maintainers">Maintainers</span>');
    expect(html).toContain('>PRs <');
    expect(html).toContain('>Issues <');
    const scoped = renderToStaticMarkup(<AttentionList candidates={items} handled={{}} viewerLogin="viewer" teamScope={{ circleId: 'trusted', memberId: 1 }}
      onHandledChange={() => {}} onOpen={() => {}} />);
    expect(scoped).toContain('Change 1');
    expect(scoped).not.toContain('Issue 7');
    expect(scoped).not.toContain('Team only</button>');
    expect(scoped).toContain('>Issues <span class="atn-filter__count">0</');
  });

  it('never brings pinned or snoozed team items back through preference', () => {
    vi.spyOn(teamHooks, 'useTeams').mockReturnValue({ directory: { accountLogin: 'viewer', circles: [{
      id: 'trusted', name: 'Maintainers', description: '', repositories: [], members: [{ githubId: 1, login: 'alice', name: 'Alice', avatarUrl: '' }],
    }] }, loading: false, error: null });
    vi.stubGlobal('window', { localStorage: { getItem: () => JSON.stringify([{ key: 'trinodb/trino#1', title: 'Pinned', target: { kind: 'pull', repo: 'trinodb/trino', number: 1 } }]) } });
    const items = [1, 2, 3].map(number => prItem(number));
    const html = list(items, { [items[1].key]: handledEntry(items[1], 'snooze', Date.now() + 3_600_000, 'soon') });
    expect([...html.matchAll(/class="atn-card__key">#(\d+)</g)].map(match => Number(match[1]))).toEqual([3]);
  });

  it('names the actual member author without labeling the latest commenter as a teammate', () => {
    vi.spyOn(teamHooks, 'useTeams').mockReturnValue({ directory: { accountLogin: 'viewer', circles: [{
      id: 'trusted', name: 'Maintainers', description: '', repositories: [], members: [{ githubId: 1, login: 'alice', name: 'Alice', avatarUrl: '' }],
    }] }, loading: false, error: null });
    const html = list([prItem(1, 'review', {
      event: 'commented', actor: 'bob', timestamp: '2026-08-28T00:00:00Z', state: null, label: null, requestedReviewer: null,
    })]);
    expect(html).toContain('PR author: <strong>@alice</strong>');
    expect(html).toContain('title="Your private circle: Maintainers">Maintainers</span>');
    expect(html).toContain('class="atn-card__actor">bob</span> commented');
    expect(html).not.toContain('Your team');

    const unrelated = { ...prItem(2), author: 'outsider', pr: { ...pullRequest(2), author: 'outsider' } };
    expect(list([unrelated])).not.toContain('atn-card__team');
  });

  it('explains a member review request independently of the PR author', () => {
    vi.spyOn(teamHooks, 'useTeams').mockReturnValue({ directory: { accountLogin: 'viewer', circles: [{
      id: 'trusted', name: 'Maintainers', description: '', repositories: [], members: [{ githubId: 2, login: 'bob', name: 'Bob', avatarUrl: '' }],
    }] }, loading: false, error: null });
    const html = list([prItem(1, 'review', {
      event: 'review_requested', actor: 'bob', timestamp: '2026-08-28T00:00:00Z', state: null, label: null, requestedReviewer: 'viewer',
    })]);
    expect(html).toContain('Review requested by <strong>@bob</strong>');
    expect(html).not.toContain('PR author: <strong>@bob');
  });

  it('lets the Teams page show the complete matching backlog while Home stays capped', () => {
    vi.spyOn(teamHooks, 'useTeams').mockReturnValue({ directory: { accountLogin: 'viewer', circles: [{
      id: 'trusted', name: 'Maintainers', description: '', repositories: [], members: [{ githubId: 1, login: 'alice', name: 'Alice', avatarUrl: '' }],
    }] }, loading: false, error: null });
    const items = [1, 2, 3, 4, 5, 6].map(number => prItem(number));
    const html = renderToStaticMarkup(<AttentionList candidates={items} handled={{}} viewerLogin="viewer" teamScope={{}}
      cardLimit={Number.POSITIVE_INFINITY} onHandledChange={() => {}} onOpen={() => {}} />);
    expect(html.match(/class="card atn-card /g)).toHaveLength(6);
    expect(list(items).match(/class="card atn-card /g)).toHaveLength(4);
  });

  it('keeps resolved reminders readable without offering to start development again', () => {
    const closed = { ...issueItem(99), issueState: 'closed' };
    const ticket = jiraAttentionItems([jiraIssue])[0];
    const done = { ...ticket, jira: { ...jiraIssue, status: { id: 'done', name: 'Done', category: 'done' } } };
    for (const item of [closed, done]) {
      const html = list([item]);
      expect(html).toContain(item.title);
      expect(html).not.toContain('aria-label="Start development');
    }
    expect(list([issueItem(99)])).toContain('aria-label="Start development for #99"');
  });

  it('draws a card tinted by its source, with the reason, the actor and what happened', () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-08-30T00:00:00Z'));
    const html = list([prItem(30920, 'review', {
      event: 'review_requested', actor: 'bob', timestamp: '2026-08-28T00:00:00Z', state: null, label: null, requestedReviewer: 'viewer',
    })]);

    // The box, its keyboard behaviour and its hover actions come from the shared card base.
    expect(html).toContain('class="card atn-card is-pr" role="button" tabindex="0"');
    expect(html).toContain('lucide-git-pull-request');
    // The strip names no type, and a pull request's reason sits in it.
    expect(html).not.toContain('>Pull request<');
    expect(html).toMatch(/class="atn-card__head">.*class="pill is-outline">Review requested<.*class="atn-card__body"/);
    expect(html).toContain('class="atn-card__key">#30920<');
    // Global Home names the repository on each card; the age is the actual event's.
    expect(html).toContain('trinodb/trino</span>');
    expect(html).toContain('dateTime="2026-08-28T00:00:00Z"');
    expect(html).toContain('>2d</time>');
    expect(html).toContain('<span class="atn-card__actor">bob</span> requested your review');
    expect(html).toContain('aria-label="Acknowledge #30920"');
    expect(html).toContain('aria-label="Snooze #30920"');
    // The "All issues" and "All pull requests" links are gone.
    expect(html).not.toContain('All pull requests');
    expect(html).not.toContain('All issues');
  });

  it('lists four new commits, or three and how many more', () => {
    const commits = (count: number) => Array.from({ length: count }, (_, index) => ({
      sha: `${index}aaaaaaaaa`, message: `Commit ${index}`, actor: 'bob', committedAt: '2026-08-28T00:00:00Z',
    }));
    const card = (count: number) => list([{ ...prItem(30920), pr: { ...pullRequest(30920), commitsSinceReview: commits(count) } }]);

    const four = card(4);
    expect(four).toContain('4 commits since your last review');
    expect(four.match(/class="atn-detail__commit"/g)).toHaveLength(4);
    expect(four).not.toContain('more</div>');
    const six = card(6);
    expect(six).toContain('6 commits since your last review');
    expect(six.match(/class="atn-detail__commit"/g)).toHaveLength(3);
    expect(six).toContain('<div class="atn-detail__lead">and 3 more</div>');
  });

  it('draws a review comment as the Markdown it was written in, under the name of the file it was left on', () => {
    const html = list([prItem(30985, 'review', {
      event: 'review_thread', actor: 'ravi-moreau', timestamp: '2026-08-28T00:00:00Z', state: null, label: null, requestedReviewer: null,
      excerpt: '`TransactionLogAccess.java:562` declares the same record.', path: 'plugin/trino-delta-lake/src/main/java/CheckpointWriter.java', line: 101,
    })]);

    // What happened starts folded under the actor line, which unfolds it.
    expect(html).toContain('<button class="card-fold" type="button" aria-expanded="false" title="Show details">');
    expect(html).toContain('<div hidden=""><div class="card-box atn-detail">');
    expect(html).toContain('<p><code>TransactionLogAccess.java:562</code> declares the same record.</p>');
    expect(html).toContain('<div class="atn-detail__file" title="plugin/trino-delta-lake/src/main/java/CheckpointWriter.java:101">on CheckpointWriter.java:101</div>');
  });

  it('draws a ticket\'s status move as the old status struck through, then the new one, then the note', () => {
    const [ticket] = jiraAttentionItems([{ ...jiraIssue, latestActivity: {
      kind: 'STATUS_CHANGED', at: jiraIssue.updatedAt, actor: null, from: { id: '3', name: 'In Progress', category: 'indeterminate' },
      to: { id: '6', name: 'Blocked', category: 'indeterminate' }, note: 'Staging is mid-upgrade.', fields: [],
    } }]);
    const html = list([ticket]);

    expect(html).toContain('class="card atn-card is-jira"');
    expect(html).toMatch(/class="atn-card__head">.*class="pill is-outline">Assigned to you<.*class="atn-card__body"/);
    expect(html).toContain('<button class="card-fold" type="button" aria-expanded="false" title="Show details">');
    expect(html).toMatch(/<span class="pill is-blue is-square is-struck">In Progress<\/span><svg[^>]*aria-label="to"[^>]*>.*?<\/svg><span class="pill is-red is-square">Blocked<\/span>/);
    expect(html).toContain('<div class="markdown-body"><p>Staging is mid-upgrade.</p></div>');
  });

  it('shows at most four cards, counts the rest, and filters by source', () => {
    const items = [30920, 30921, 30922, 30923, 30924].map(number => prItem(number)).concat(issueItem(7));
    const html = list(items, {}, 'workspace');

    expect(html.match(/class="card atn-card /g)).toHaveLength(4);
    expect(html).toContain('class="atn-count">4 of 6<');
    expect(html).toMatch(/>PRs <span class="atn-filter__count">5</);
    expect(html).toMatch(/>Issues <span class="atn-filter__count">1</);
    expect(html).toMatch(/>Jira <span class="atn-filter__count">0</);
    // Without Jira connected there is no Jira tab.
    expect(list(items, {}, 'workspace', false)).not.toContain('>Jira <');
    // A workspace's own cards do not repeat its repository.
    expect(html).not.toContain('trinodb/trino</span>');

    const issues = attentionCards(items, 'issue');
    expect(issues.cards.map(item => item.number)).toEqual([7]);
    expect(issues.counts).toEqual({ all: 6, pr: 5, issue: 1, jira: 0, task: 0 });
  });

  it('selects the newest cards across sources and within a filter before applying the cap', () => {
    const approval = { ...taskAttentionItem({ ...previewTask('workspace-1', 'task-1'), updatedAt: '2026-08-28T00:00:00Z' }, null,
      { id: 'workspace-1', name: 'Workspace', repository: 'trinodb/trino' }), kind: 'approval' as const };
    const newest = { ...prItem(3, 'updated'), updatedAt: '2026-09-28T13:00:00Z' };
    const issue = { ...issueItem(7), updatedAt: '2026-09-28T11:00:00Z' };
    const [ticket] = jiraAttentionItems([{ ...jiraIssue, updatedAt: '2026-09-28T12:00:00Z' }]);
    const items = [approval, prItem(1), { ...prItem(2), updatedAt: '2026-09-28T09:00:00Z' }, newest, issue, ticket];
    expect(attentionCards(items, 'all').cards.map(item => item.key)).toEqual([newest.key, ticket.key, issue.key, items[2].key]);
    expect(attentionCards(items, 'pr').cards.map(item => item.number)).toEqual([3, 2, 1]);
    expect(items[0]).toBe(approval);
  });

  it('puts the newest update first in both Homes even when four older review requests precede it', () => {
    const items = [1, 2, 3, 4].map(number => prItem(number));
    items.push({ ...prItem(5, 'updated'), updatedAt: '2026-09-28T13:00:00Z' });
    for (const variant of ['home', 'workspace'] as const) {
      const html = list(items, {}, variant);
      expect([...html.matchAll(/class="atn-card__key">#(\d+)</g)].map(match => Number(match[1]))).toEqual([5, 1, 2, 3]);
      expect(html).toContain('class="atn-count">4 of 5<');
    }
  });

  it('sorts an expired retained snooze with the other cards before applying the cap', () => {
    const now = Date.parse('2026-09-30T00:00:00Z');
    vi.spyOn(Date, 'now').mockReturnValue(now);
    const items = [1, 2, 3, 4].map(number => prItem(number));
    const reminder = { ...prItem(5, 'updated'), updatedAt: '2026-09-28T13:00:00Z' };
    const handled = { [reminder.key]: handledEntry(reminder, 'snooze', now, '1 hour') };
    const html = list(items, handled);
    expect([...html.matchAll(/class="atn-card__key">#(\d+)</g)].map(match => Number(match[1]))).toEqual([5, 1, 2, 3]);
    expect(html).toContain('class="atn-count">4 of 5<');
  });

  it('brings in the next item when a card is acknowledged or snoozed, still capped at four', () => {
    const now = Date.parse('2026-08-30T00:00:00Z');
    vi.spyOn(Date, 'now').mockReturnValue(now);
    const items = [30920, 30921, 30922, 30923, 30924, 30925].map(number => prItem(number));
    const shown = (handled: Handled) => attentionCards(visibleAttention(items, handled, now), 'all').cards.map(item => item.number);
    expect(shown({})).toEqual([30920, 30921, 30922, 30923]);

    const acknowledged = { [items[1].key]: handledEntry(items[1], 'ack', null, 'acknowledged') };
    expect(shown(acknowledged)).toEqual([30920, 30922, 30923, 30924]);
    const snoozed = { ...acknowledged, [items[0].key]: handledEntry(items[0], 'snooze', now + 3_600_000, '1 hour') };
    expect(shown(snoozed)).toEqual([30922, 30923, 30924, 30925]);
    expect(list(items, snoozed)).toContain('class="atn-count">4<');
  });

  it('offers development for an issue and lists its labels, and says so when nothing needs you', () => {
    const html = list([issueItem(7)]);
    expect(html).toContain('class="card atn-card is-issue"');
    expect(html).toContain('class="pill is-outline">Assigned to you<');
    expect(html).toContain('<span class="atn-card__actor">finn-ep</span> opened this issue');
    expect(html).toContain('aria-label="Start development for #7"');
    expect(html).toContain('>bug</span>');
    expect(html).toContain('>iceberg</span>');

    expect(list([], {}, 'workspace')).toContain('Nothing needs you right now.');
  });

  it('starts linked items folded to one row of keys', () => {
    const item: AttentionItem = {
      ...prItem(31264),
      links: [
        { source: 'jira', key: 'TRINO-412', title: 'Validate the fix', status: 'Blocked', tone: 'red' },
        { source: 'issue', key: '#31190', title: 'Checkpoint drops deletion vectors', status: 'Open', tone: 'green' },
      ],
    };
    const html = list([item]);
    expect(html).toContain('aria-expanded="false"');
    expect(html).toContain('>TRINO-412</span>');
    expect(html).toContain('>#31190</span>');
    expect(html).not.toContain('LINKED');
    // Titles and statuses wait, hidden, until the row unfolds.
    expect(html).toMatch(/<button class="card-fold" type="button" aria-expanded="false" title="Show linked items">.*?<\/button><div hidden="">.*Validate the fix/);
  });
});
