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
import AttentionList, { type GoalToggle } from './AttentionList';
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

function by(item: AttentionItem, author: string): AttentionItem {
  return { ...item, author, pr: item.pr === null ? null : { ...item.pr, author } };
}

function issueItem(number: number): AttentionItem {
  return {
    key: `trinodb/trino#${number}`, type: 'issue', kind: 'assigned', repo: 'trinodb/trino', number, title: `Issue ${number}`,
    author: 'finn-ep', authorAvatarUrl: null, createdAt: null, updatedAt: '2026-08-28T00:00:00Z', labels: ['bug', 'iceberg'],
    pr: null, body: '', commentCount: 0, workspaceId: 'workspace-1',
  };
}

function list(candidates: AttentionItem[], handled: Handled = {}, variant: 'home' | 'workspace' = 'home', jiraConnected = true,
  goal?: (item: AttentionItem) => GoalToggle | null): string {
  return renderToStaticMarkup(
    <AttentionList variant={variant} candidates={candidates} handled={handled} viewerLogin="viewer" jiraConnected={jiraConnected}
      onHandledChange={() => {}} onOpen={() => {}} onStartIssue={() => {}} goal={goal} />);
}

describe('AttentionList', () => {
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('folds a source into the Goal card that offers it as practice, unless it needs the user for more', () => {
    // The Goal's card offers every source here as practice; only the pull request still asks for a review.
    const picked = (): GoalToggle => ({ inGoal: false, toggle: async () => {}, picked: true });
    const markup = list([issueItem(31252), prItem(31260, 'review')], {}, 'workspace', true, picked);
    expect(markup).not.toContain('Issue 31252');
    expect(markup).toContain(prItem(31260).title);
    expect(list([issueItem(31252)], {}, 'workspace', true, () => ({ inGoal: false, toggle: async () => {} }))).toContain('Issue 31252');
  });

  it('shows a private-circle reason and keeps the Circles filter independent from the source tabs', () => {
    vi.spyOn(teamHooks, 'useTeams').mockReturnValue({ directory: { accountLogin: 'viewer', circles: [{
      id: 'trusted', name: 'Maintainers', description: '', avatar: { color: 'gray', shape: 'square' }, repositories: ['trinodb/trino'], members: [{ githubId: 1, login: 'alice', name: 'Alice', avatarUrl: '' }],
    }] }, loading: false, error: null });
    const items = [issueItem(7), prItem(1)];
    const html = list(items);
    expect(html).toContain('aria-pressed="false">Circles</button>');
    expect(html).toContain('title="Your private circle: Maintainers">Maintainers</span>');
    expect(html).toContain('>PRs</button>');
    expect(html).toContain('>Issues</button>');
  });

  it('never brings pinned or snoozed team items back through preference', () => {
    vi.spyOn(teamHooks, 'useTeams').mockReturnValue({ directory: { accountLogin: 'viewer', circles: [{
      id: 'trusted', name: 'Maintainers', description: '', avatar: { color: 'gray', shape: 'square' }, repositories: [], members: [{ githubId: 1, login: 'alice', name: 'Alice', avatarUrl: '' }],
    }] }, loading: false, error: null });
    vi.stubGlobal('window', { localStorage: { getItem: () => JSON.stringify([{ key: 'trinodb/trino#1', title: 'Pinned', target: { kind: 'pull', repo: 'trinodb/trino', number: 1 } }]) } });
    const items = [1, 2, 3].map(number => prItem(number));
    const html = list(items, { [items[1].key]: handledEntry(items[1], 'snooze', Date.now() + 3_600_000, 'soon') });
    expect([...html.matchAll(/class="card-key">#(\d+)</g)].map(match => Number(match[1]))).toEqual([3]);
  });

  it('names the actual member author without labeling the latest commenter as a teammate', () => {
    vi.spyOn(teamHooks, 'useTeams').mockReturnValue({ directory: { accountLogin: 'viewer', circles: [{
      id: 'trusted', name: 'Maintainers', description: '', avatar: { color: 'gray', shape: 'square' }, repositories: [], members: [{ githubId: 1, login: 'alice', name: 'Alice', avatarUrl: '' }],
    }] }, loading: false, error: null });
    const html = list([prItem(1, 'review', {
      event: 'commented', actor: 'bob', timestamp: '2026-08-28T00:00:00Z', state: null, label: null, requestedReviewer: null,
    })]);
    expect(html).toMatch(/<span class="item-person">.*?<span>alice<\/span><\/span><span>authored<\/span><span class="pill is-blue" title="Your private circle: Maintainers">Maintainers<\/span>/);
    expect(html).toContain('<span>Latest: bob commented · ');
    expect(html).not.toContain('Your team');

    const unrelated = { ...prItem(2), author: 'outsider', pr: { ...pullRequest(2), author: 'outsider' } };
    const plain = list([unrelated]);
    expect(plain).toMatch(/<span>outsider<\/span><\/span><span>authored<\/span>/);
    expect(plain).not.toContain('Your private circle');
  });

  it('explains a member review request independently of the PR author', () => {
    vi.spyOn(teamHooks, 'useTeams').mockReturnValue({ directory: { accountLogin: 'viewer', circles: [{
      id: 'trusted', name: 'Maintainers', description: '', avatar: { color: 'gray', shape: 'square' }, repositories: [], members: [{ githubId: 2, login: 'bob', name: 'Bob', avatarUrl: '' }],
    }] }, loading: false, error: null });
    const html = list([prItem(1, 'review', {
      event: 'review_requested', actor: 'bob', timestamp: '2026-08-28T00:00:00Z', state: null, label: null, requestedReviewer: 'viewer',
    })]);
    expect(html).toMatch(/<span>bob<\/span><\/span><span>requested your review<\/span>/);
    expect(html).not.toMatch(/<span>bob<\/span><\/span><span>authored/);
  });

  it('keeps resolved reminders readable without offering to start development again', () => {
    const closed = { ...issueItem(99), issueState: 'closed' };
    const ticket = jiraAttentionItems([jiraIssue])[0];
    const done = { ...ticket, jira: { ...jiraIssue, status: { id: 'done', name: 'Done', category: 'done' } } };
    for (const item of [closed, done, { ...issueItem(99), canStartDevelopment: false },
      ...jiraAttentionItems([{ ...jiraIssue, isEpic: true }, { ...jiraIssue, hasChildren: true }, { ...jiraIssue, task: { id: 'task', workspaceId: 'ws' } }])]) {
      const html = list([item]);
      expect(html).toContain(item.title);
      expect(html).not.toContain('aria-label="Start development');
    }
    expect(list([issueItem(99)])).toContain('aria-label="Start development for #99"');
  });

  it('hides Jira Done categories in both Homes only while Open only is enabled', () => {
    const tickets = jiraAttentionItems([
      { ...jiraIssue, id: '1', key: 'ENG-1', status: { id: 'done', name: 'Done', category: 'done' } },
      { ...jiraIssue, id: '2', key: 'ENG-2', status: { id: 'resolved', name: 'Resolved', category: 'done' } },
      { ...jiraIssue, id: '3', key: 'ENG-3' },
      { ...jiraIssue, id: '4', key: 'ENG-4', status: { id: 'reopened', name: 'Reopened', category: 'indeterminate' }, completedAt: jiraIssue.updatedAt },
    ]);
    for (const openOnly of [true, false]) {
      vi.stubGlobal('window', { localStorage: { getItem: (key: string) => key === 'bytequay.attention.openOnly' ? String(openOnly) : null } });
      for (const variant of ['home', 'workspace'] as const) {
        const html = list(tickets, {}, variant);
        expect([...html.matchAll(/class="card-key">(ENG-\d+)</g)].map(match => match[1]))
          .toEqual(openOnly ? ['ENG-3', 'ENG-4'] : ['ENG-1', 'ENG-2', 'ENG-3', 'ENG-4']);
      }
    }
  });

  it('draws a row with its source, where it stands, who it is from and the latest event', () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-08-30T00:00:00Z'));
    const html = list([prItem(30920, 'review', {
      event: 'review_requested', actor: 'bob', timestamp: '2026-08-28T00:00:00Z', state: null, label: null, requestedReviewer: 'viewer',
    })]);

    // The row, its keyboard behaviour and its hover actions come from the shared card base, one row in one list box.
    expect(html).toContain('<div class="item-list" role="list"><div role="listitem"><div class="card-row item-row" role="button" tabindex="0"');
    expect(html).toContain('<span class="item-row__icon"><span class="card-src is-pr">');
    expect(html).toContain('lucide-git-pull-request');
    // The row names no type, and where a pull request stands sits beside its title and key.
    expect(html).not.toContain('>Pull request<');
    expect(html).toContain('<span title="Change 30920">Change 30920</span><span class="card-key">#30920</span><span class="pill is-outline">Review requested</span>');
    // Global Home names the repository beside the author; the age is the actual event's.
    expect(html).toMatch(/<span>alice<\/span><\/span><span>authored<\/span><span class="item-row__sep">·<\/span><span class="item-row__muted">trinodb\/trino<\/span>/);
    expect(html).toContain('dateTime="2026-08-28T00:00:00Z"');
    expect(html).toContain('>2d</time>');
    expect(html).toContain('<span>Latest: bob requested your review · 2d</span>');
    expect(html).toContain('aria-label="Acknowledge #30920"');
    expect(html).toContain('aria-label="Snooze #30920"');
    expect(html).toContain('aria-label="Open #30920"');
    // The hover card waits for the pointer to rest on the row.
    expect(html).not.toContain('role="dialog"');
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

  it('unfolds a review comment as the Markdown it was written in, under the name of the file it was left on', () => {
    const html = list([prItem(30985, 'review', {
      event: 'review_thread', actor: 'ravi-moreau', timestamp: '2026-08-28T00:00:00Z', state: null, label: null, requestedReviewer: null,
      excerpt: '`TransactionLogAccess.java:562` declares the same record.', path: 'plugin/trino-delta-lake/src/main/java/CheckpointWriter.java', line: 101,
    })]);

    // What happened waits under the latest line, which unfolds it while hovered.
    expect(html).toContain('<div class="item-row__latest has-detail">');
    expect(html).toContain('<span>Latest: ravi-moreau left a review comment · ');
    expect(html).toContain('<div class="item-row__detail atn-detail">');
    expect(html).toContain('<p><code>TransactionLogAccess.java:562</code> declares the same record.</p>');
    expect(html).toContain('<div class="atn-detail__file" title="plugin/trino-delta-lake/src/main/java/CheckpointWriter.java:101">on CheckpointWriter.java:101</div>');
  });

  it('draws a ticket\'s status move as the old status struck through, then the new one, then the note', () => {
    const [ticket] = jiraAttentionItems([{ ...jiraIssue, latestActivity: {
      kind: 'STATUS_CHANGED', at: jiraIssue.updatedAt, actor: null, from: { id: '3', name: 'In Progress', category: 'indeterminate' },
      to: { id: '6', name: 'Blocked', category: 'indeterminate' }, note: 'Staging is mid-upgrade.', fields: [],
    } }]);
    const html = list([ticket]);

    expect(html).toContain('<span class="card-src is-jira">');
    expect(html).toMatch(/class="item-row__title">.*?class="pill is-outline">To do</);
    expect(html).toContain('<div class="item-row__latest has-detail">');
    expect(html).toContain('<span>Latest: Moved to Blocked · ');
    expect(html).toMatch(/<span class="pill is-blue is-square is-struck">In Progress<\/span><svg[^>]*aria-label="to"[^>]*>.*?<\/svg><span class="pill is-red is-square">Blocked<\/span>/);
    expect(html).toContain('<div class="markdown-body"><p>Staging is mid-upgrade.</p></div>');
  });

  it('shows at most four rows, counts nothing, and filters by source', () => {
    const items = [30920, 30921, 30922, 30923, 30924].map(number => prItem(number)).concat(issueItem(7));
    const html = list(items, {}, 'workspace');

    expect(html.match(/class="card-row item-row"/g)).toHaveLength(4);
    // Every watched update is eligible, so a total would count the repositories, not what needs the user.
    expect(html).toMatch(/Needs attention<\/span><button[^>]*aria-pressed="false"[^>]*>Open only<\/button><div class="atn-filters"/);
    expect(html).toMatch(/>PRs<\/button><button[^>]*>Issues<\/button><button[^>]*>Jira<\/button>/);
    // Without Jira connected there is no Jira tab.
    expect(list(items, {}, 'workspace', false)).not.toContain('>Jira</button>');
    // A workspace's own rows do not repeat its repository.
    expect(html).not.toContain('item-row__muted');

    expect(attentionCards(items, 'issue').map(item => item.number)).toEqual([7]);
  });

  it('ranks cards across sources, newest first within a rank, within a filter before applying the cap', () => {
    const approval = { ...taskAttentionItem({ ...previewTask('workspace-1', 'task-1'), updatedAt: '2026-09-27T00:00:00Z' }, null,
      { id: 'workspace-1', name: 'Workspace', repository: 'trinodb/trino' }), kind: 'approval' as const };
    const mention = prItem(3, 'updated');
    const newest = { ...mention, updatedAt: '2026-09-28T13:00:00Z', pr: { ...mention.pr, mentioned: true } };
    const issue = { ...issueItem(7), updatedAt: '2026-09-28T11:00:00Z' };
    const [ticket] = jiraAttentionItems([{ ...jiraIssue, updatedAt: '2026-09-28T12:00:00Z' }]);
    const untied = { ...prItem(4, 'updated'), updatedAt: '2026-09-28T14:00:00Z' };
    const items = [approval, prItem(1), { ...prItem(2), updatedAt: '2026-09-28T09:00:00Z' }, newest, issue, ticket, untied];
    const now = Date.parse('2026-09-29T00:00:00Z');
    expect(attentionCards(items, 'all', 4, undefined, now).map(item => item.key)).toEqual([approval.key, newest.key, ticket.key, issue.key]);
    // #1 is older than a week, so it follows the ranked rows.
    expect(attentionCards(items, 'pr', 4, undefined, now).map(item => item.number)).toEqual([3, 2, 1]);
    expect(items[0]).toBe(approval);
  });

  it("puts a circle member's review request first in both Homes and leaves out an outsider's newer update", () => {
    vi.spyOn(teamHooks, 'useTeams').mockReturnValue({ directory: { accountLogin: 'viewer', circles: [{
      id: 'trusted', name: 'Maintainers', description: '', avatar: { color: 'gray', shape: 'square' }, repositories: [], members: [{ githubId: 1, login: 'alice', name: 'Alice', avatarUrl: '' }],
    }] }, loading: false, error: null });
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-08-30T00:00:00Z'));
    const items = [1, 2, 3, 4].map(number => by(prItem(number), 'bob'));
    items.push({ ...prItem(5), updatedAt: '2026-08-24T00:00:00Z' }, { ...by(prItem(6, 'updated'), 'outsider'), updatedAt: '2026-08-29T13:00:00Z' });
    for (const variant of ['home', 'workspace'] as const) {
      const html = list(items, {}, variant);
      expect([...html.matchAll(/class="card-key">#(\d+)</g)].map(match => Number(match[1]))).toEqual([5, 1, 2, 3]);
    }
  });

  it('sorts an expired retained snooze with the other cards before applying the cap', () => {
    const now = Date.parse('2026-09-30T00:00:00Z');
    vi.spyOn(Date, 'now').mockReturnValue(now);
    const items = [1, 2, 3, 4].map(number => prItem(number));
    const reminder = { ...prItem(5), updatedAt: '2026-09-28T13:00:00Z' };
    const handled = { [reminder.key]: handledEntry(reminder, 'snooze', now, '1 hour') };
    const html = list(items, handled);
    expect([...html.matchAll(/class="card-key">#(\d+)</g)].map(match => Number(match[1]))).toEqual([5, 1, 2, 3]);
  });

  it('brings in the next item when a card is acknowledged or snoozed, still capped at four', () => {
    const now = Date.parse('2026-08-30T00:00:00Z');
    vi.spyOn(Date, 'now').mockReturnValue(now);
    const items = [30920, 30921, 30922, 30923, 30924, 30925].map(number => prItem(number));
    const shown = (handled: Handled) => attentionCards(visibleAttention(items, handled, now), 'all').map(item => item.number);
    expect(shown({})).toEqual([30920, 30921, 30922, 30923]);

    const acknowledged = { [items[1].key]: handledEntry(items[1], 'ack', null, 'acknowledged') };
    expect(shown(acknowledged)).toEqual([30920, 30922, 30923, 30924]);
    const snoozed = { ...acknowledged, [items[0].key]: handledEntry(items[0], 'snooze', now + 3_600_000, '1 hour') };
    expect(shown(snoozed)).toEqual([30922, 30923, 30924, 30925]);
    expect(list(items, snoozed).match(/class="card-row item-row"/g)).toHaveLength(4);
  });

  it('offers development for an issue, leaves its labels to the hover card, and says so when nothing needs you', () => {
    const html = list([issueItem(7)]);
    expect(html).toContain('<span class="card-src is-issue">');
    expect(html).toContain('class="pill is-outline">Assigned to you<');
    expect(html).toMatch(/<span>finn-ep<\/span><\/span><span>opened · assigned to you<\/span>/);
    // An issue's opening is the line above; there is no event to name below it.
    expect(html).not.toContain('Latest:');
    expect(html).toContain('aria-label="Start development for #7"');
    expect(html).not.toContain('>bug</span>');

    expect(list([], {}, 'workspace')).toContain('Nothing needs you right now.');
  });

  it('names linked items by their keys, with titles and statuses on hover', () => {
    const item: AttentionItem = {
      ...prItem(31264),
      links: [
        { source: 'jira', key: 'TRINO-412', title: 'Validate the fix', status: 'Blocked', tone: 'red' },
        { source: 'issue', key: '#31190', title: 'Checkpoint drops deletion vectors', status: 'Open', tone: 'green' },
      ],
    };
    const html = list([item]);
    expect(html).toContain('>TRINO-412</span>');
    expect(html).toContain('>#31190</span>');
    expect(html).not.toContain('LINKED');
    expect(html).toContain('<span class="card-link" title="Validate the fix · Blocked">');
    expect(html).toContain('<span class="card-link" title="Checkpoint drops deletion vectors · Open">');
  });
});
