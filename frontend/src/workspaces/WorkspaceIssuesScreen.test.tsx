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
import type { PullTimelinePayloadDto, RepositoryMetadataDto, WorkspaceIssueTimelineEventDto } from '../types';
import { appendIssuePage, IssueOverview, issueParticipants } from './WorkspaceIssuesScreen';

const payload: PullTimelinePayloadDto = {
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

const issue = {
  id: 7,
  number: 7,
  title: 'Example issue',
  body: '## Description\n\nIssue details.',
  author: 'alice',
  authorAvatarUrl: 'https://avatars.example/alice',
  htmlUrl: 'https://github.com/owner/repo/issues/7',
  labels: ['correctness'],
  assignees: ['bob'],
  commentCount: 1,
  createdAt: '2026-08-20T10:00:00Z',
  updatedAt: '2026-08-21T10:00:00Z',
};

const timeline: WorkspaceIssueTimelineEventDto[] = [
  { githubId: 1, event: 'assigned', actor: 'alice', timestamp: '2026-08-20T11:00:00Z', body: null, payload },
  {
    githubId: 2,
    event: 'commented',
    actor: 'carol',
    timestamp: '2026-08-21T10:00:00Z',
    body: 'I can reproduce this.',
    payload: { ...payload, actorAvatarUrl: 'https://avatars.example/carol', reactions: [{ content: '+1', count: 2 }] },
  },
];

const metadata: RepositoryMetadataDto = {
  repositoryId: 1,
  repository: { owner: 'owner', repo: 'repo' },
  contributors: [],
  labels: [{ id: 1, name: 'correctness', color: 'd1242f' }],
  topics: [],
  contributorsSyncedAt: null,
  labelsTopicsSyncedAt: null,
};

describe('WorkspaceIssuesScreen', () => {
  it('renders the PR discussion style with GitHub issue metadata and activity', () => {
    const html = renderToStaticMarkup(<IssueOverview issue={issue} metadata={metadata} timeline={timeline}
      issueType={{ current: 'Task', available: ['Task', 'Bug'] }}
      repository="owner/repo" workspaceId="workspace-1" timelineLoading={false}
      onTypeChange={async () => {}} onMetadataChange={async () => {}}
      onClose={async () => {}} onComment={async () => {}} />);

    expect(html).toContain('class="pr-overview issue-overview"');
    expect(html).toContain('Example issue <span>#7</span>');
    expect(html).toContain('class="pr-card pr-description"');
    expect(html).toContain('I can reproduce this.');
    expect(html).toContain('assigned this issue');
    expect(html).toContain('Assignees');
    expect(html).toContain('Participants');
    expect(html).toContain('aria-label="Issue type"');
    expect(html).toContain('data-type="task"');
    expect(html).toContain('value="Task" selected=""');
    expect(html).toContain('class="pr-copy-button"');
    expect(html).toContain('aria-label="Copy issue link"');
    expect(html).not.toContain('issue-rail');
    expect(html).not.toContain('aria-label="Add reaction"');
    expect(html).toContain('class="pr-composer"');
    expect(html).toContain('>Close issue</button>');
  });

  it('deduplicates participants and keeps the best avatar', () => {
    const participants = issueParticipants(issue, [
      ...timeline,
      { githubId: 3, event: 'assigned', actor: 'BOB', timestamp: null, body: null, payload: { ...payload, actorAvatarUrl: 'https://avatars.example/bob' } },
    ]);

    expect(participants).toEqual([
      { login: 'alice', avatarUrl: 'https://avatars.example/alice' },
      { login: 'bob', avatarUrl: 'https://avatars.example/bob' },
      { login: 'carol', avatarUrl: 'https://avatars.example/carol' },
    ]);
  });

  it('appends only the next database page without duplicate issues', () => {
    const first = {
      repositoryId: 1,
      repository: { owner: 'owner', repo: 'repo' },
      issues: [issue],
      page: 0,
      pageSize: 50,
      totalIssues: 2,
      hasMore: true,
      syncedAt: '2026-08-21T10:00:00Z',
    };
    const second = {
      ...first,
      issues: [issue, { ...issue, id: 8, number: 8, title: 'Next issue' }],
      page: 1,
      hasMore: false,
    };

    expect(appendIssuePage(first, second).issues.map(value => value.id)).toEqual([7, 8]);
    expect(appendIssuePage(first, { ...second, page: 2 })).toBe(first);
  });
});
