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
import { jiraAttentionItems } from '../jira/attention';
import { jiraIssue } from '../jira/fixtures/jira';
import { attentionCards, handledEntry, visibleAttention, type AttentionItem, type Handled } from './attention';
import type { PullRequestDto } from '../types';

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

function list(candidates: AttentionItem[], handled: Handled = {}, variant: 'home' | 'workspace' = 'home'): string {
  return renderToStaticMarkup(
    <AttentionList variant={variant} candidates={candidates} handled={handled} viewerLogin="viewer"
      onHandledChange={() => {}} onOpen={() => {}} onStartIssue={() => {}} />);
}

describe('AttentionList', () => {
  afterEach(() => { vi.restoreAllMocks(); });

  it('draws a card tinted by its source, with the reason, the actor and what happened', () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-08-30T00:00:00Z'));
    const html = list([prItem(30920, 'review', {
      event: 'review_requested', actor: 'bob', timestamp: '2026-08-28T00:00:00Z', state: null, label: null, requestedReviewer: 'viewer',
    })]);

    expect(html).toContain('class="atn-card is-pr"');
    expect(html).toContain('lucide-git-pull-request');
    expect(html).toContain('>Pull request<');
    expect(html).toContain('class="atn-card__key">#30920<');
    // Global Home names the repository on each card; the age is the actual event's.
    expect(html).toContain('trinodb/trino</span>');
    expect(html).toContain('dateTime="2026-08-28T00:00:00Z"');
    expect(html).toContain('>2d</time>');
    expect(html).toContain('class="atn-tag is-blue">Review requested<');
    expect(html).toContain('<b>bob</b> requested your review');
    expect(html).toContain('aria-label="Acknowledge #30920"');
    expect(html).toContain('aria-label="Snooze #30920"');
    // The "All issues" and "All pull requests" links are gone.
    expect(html).not.toContain('All pull requests');
    expect(html).not.toContain('All issues');
  });

  it('draws a ticket\'s status move as the old status struck through, then the new one, then the note', () => {
    const [ticket] = jiraAttentionItems([{ ...jiraIssue, latestActivity: {
      kind: 'STATUS_CHANGED', at: jiraIssue.updatedAt, actor: null, from: { id: '3', name: 'In Progress', category: 'indeterminate' },
      to: { id: '6', name: 'Blocked', category: 'indeterminate' }, note: 'Staging is mid-upgrade.', fields: [],
    } }]);
    const html = list([ticket]);

    expect(html).toContain('class="atn-card is-jira"');
    expect(html).toContain('class="atn-tag is-red">Status changed<');
    expect(html).toMatch(/<span class="atn-status is-blue is-from">In Progress<\/span><svg[^>]*aria-label="to"[^>]*>.*?<\/svg><span class="atn-status is-red">Blocked<\/span>/);
    expect(html).toContain('class="atn-detail__quote">“Staging is mid-upgrade.”<');
  });

  it('shows at most four cards, counts the rest, and filters by source', () => {
    const items = [30920, 30921, 30922, 30923, 30924].map(number => prItem(number)).concat(issueItem(7));
    const html = list(items, {}, 'workspace');

    expect(html.match(/class="atn-card /g)).toHaveLength(4);
    expect(html).toContain('class="atn-count">4 of 6<');
    expect(html).toMatch(/>PRs <span class="atn-filter__count">5</);
    expect(html).toMatch(/>Issues <span class="atn-filter__count">1</);
    expect(html).toMatch(/>Jira <span class="atn-filter__count">0</);
    // A workspace's own cards do not repeat its repository.
    expect(html).not.toContain('trinodb/trino</span>');

    const issues = attentionCards(items, 'issue');
    expect(issues.cards.map(item => item.number)).toEqual([7]);
    expect(issues.counts).toEqual({ all: 6, pr: 5, issue: 1, jira: 0, task: 0 });
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
    expect(html).toContain('class="atn-card is-issue"');
    expect(html).toContain('class="atn-tag is-green">Assigned to you<');
    expect(html).toContain('<b>finn-ep</b> opened this issue');
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
    // Titles and statuses wait until the row unfolds.
    expect(html).not.toContain('Validate the fix');
  });
});
