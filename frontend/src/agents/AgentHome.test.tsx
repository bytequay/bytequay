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
import { setCached } from '../dataCache';
import { ATTENTION_SEED, type AttentionItem } from '../home/attention';
import type { PullRequestDto } from '../types';
import AgentHome from './AgentHome';
import AgentTasksView from './AgentTasksView';
import { parseAgentEventSequence } from './events';
import fixture from './fixtures/agent-events-v2.json';
import { createPreviewBridge, previewTask } from './fixtures/previewBridge';
import { initialAgentTaskState } from './model';
import { TaskWindows } from './TaskCard';

function home(workspaceId: string): string {
  return renderToStaticMarkup(
    <AgentHome workspaceId={workspaceId} repository="octo/app" bridge={createPreviewBridge(workspaceId)} onOpenTask={() => {}} onShowAll={() => {}} onStartIssue={() => {}} onOpenIssues={() => {}} />);
}

describe('agent home on a return visit', () => {
  it('paints the attention rows Home last read for this repository, ranked as its own reads will rank them', () => {
    expect(home('workspace-attention')).not.toContain('Needs attention');

    setCached(ATTENTION_SEED.prs, [
      pullRequest('octo/app', 7, 'Retry flaky upload'),
      pullRequest('octo/other', 8, 'Elsewhere'),
      // Only the login makes this one review work.
      { ...pullRequest('octo/app', 9, 'Reviewer named by login'), origin: 'OTHER', requestedReviewers: ['me'] },
    ]);
    setCached(ATTENTION_SEED.profile, { login: 'me' });
    setCached(ATTENTION_SEED.issues, [issue('workspace-attention', 3, 'Assigned in this workspace'), issue('workspace-other', 4, 'Assigned in another workspace')]);
    const seeded = home('workspace-attention');
    expect(seeded).toContain('Needs attention');
    expect(seeded).toContain('Retry flaky upload');
    expect(seeded).toContain('Reviewer named by login');
    expect(seeded).toContain('Assigned in this workspace');
    expect(seeded).toContain('Start development for #3');
    expect(seeded).toContain('All issues');
    expect(seeded).toContain('All pull requests');
    expect(seeded).not.toContain('Elsewhere');
    expect(seeded).not.toContain('Assigned in another workspace');
  });

  it('paints the last open tasks before the list read answers', () => {
    expect(home('workspace-tasks')).toContain('Open tasks · 0');

    setCached('agent-tasks:workspace-tasks', [
      { ...previewTask('workspace-tasks', 'open-1'), title: 'Audit storage' },
      { ...previewTask('workspace-tasks', 'done-1'), title: 'Finished work', state: 'COMPLETED' },
    ]);
    const seeded = home('workspace-tasks');
    expect(seeded).toContain('Open tasks · 1');
    expect(seeded).toContain('aria-label="Open task: Audit storage"');
    expect(seeded).not.toContain('Finished work');
  });

  it('paints task rows with their pull request and their checkout\'s last known changes', () => {
    setCached('agent-tasks:workspace-rows', [
      { ...previewTask('workspace-rows', 'row-1'), title: 'Refactor nits', pullRequest: { number: 31105, title: 'Clean up nits', state: 'open', draft: true } },
      { ...previewTask('workspace-rows', 'row-2'), title: 'Another nit' },
    ]);
    setCached('agent-task-changes:workspace-rows', { 'row-2': { uncommitted: false, commits: 2 } });
    const markup = renderToStaticMarkup(
      <AgentTasksView workspaceId="workspace-rows" bridge={createPreviewBridge('workspace-rows')} onOpenTask={() => {}} onOpenReview={() => {}} />);

    expect(markup).toContain('#31105 Clean up nits');
    expect(markup).toContain('2 commits');
  });

  it('paints a window from its last state instead of an empty card', () => {
    const task = { ...previewTask('workspace-windows', 'window-1'), title: 'Audit storage' };
    const windows = () => renderToStaticMarkup(
      <TaskWindows tasks={[task]} providers={[]} width={250} bridge={() => createPreviewBridge('workspace-windows')} onOpen={() => {}} />);
    expect(windows()).not.toContain('class="agt-turn"');

    setCached('task-window:window-1', { ...initialAgentTaskState(), task, events: parseAgentEventSequence(fixture.events) });
    const seeded = windows();
    expect(seeded).toContain('class="agt-turn"');
    expect(seeded).toContain('I found one authentication regression.');
  });
});

function pullRequest(repo: string, number: number, title: string): PullRequestDto {
  return {
    id: number, repo, number, title, author: 'alice', htmlUrl: `https://github.com/${repo}/pull/${number}`,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-11T00:00:00Z', origin: 'REVIEW_REQUESTED',
    labels: [], labelColors: null, draft: false, viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [],
    ciStatus: null, additions: 0, deletions: 0, commentCount: 0, attentionReason: null, state: 'open',
    mergeable: null, mergeableState: null, headPushedAt: null, headRef: 'feature',
  };
}

function issue(workspaceId: string, number: number, title: string): AttentionItem {
  return {
    key: `octo/app#${number}`, type: 'issue', kind: 'assigned', repo: 'octo/app', number, title, author: 'alice', authorAvatarUrl: null,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-11T00:00:00Z', labels: [], pr: null, body: '', commentCount: 0, workspaceId,
  };
}
