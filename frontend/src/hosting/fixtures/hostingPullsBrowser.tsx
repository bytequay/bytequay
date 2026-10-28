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
import { useState } from 'react';
import { flushSync } from 'react-dom';
import { assert, button, element, runScenarios, settle, until } from '../../fixtures/harness';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { pr, prDetail } from '../../pulls/fixtures/pullRequest';
import type { Bridge, WorkspaceDto } from '../../types';
import { workspaceFixture } from '../../workspaces/fixtures/workspace';
import WorkspaceShell from '../../workspaces/WorkspaceShell';
import '../../index.css';

const server = { provider: 'GITLAB' as const, webBaseUrl: 'https://gitlab.example.com:8443/tools', apiBaseUrl: 'https://gitlab.example.com:8443/tools/api/v4' };
const gitlab = workspaceFixture({ id: 'private-project', hostingServer: server, selectedOwner: 'group/subgroup', monitoredOwner: 'group/subgroup' });
const github = workspaceFixture({ id: 'github-project' });

async function sharedRequests(workspace: WorkspaceDto) {
  const view = createFixtureRoot();
  const repository = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
  const htmlUrl = workspace.hostingServer ? `${server.webBaseUrl}/${repository}/-/merge_requests/7` : `https://github.com/${repository}/pull/7`;
  let title = 'Shared request';
  let monitored = false;
  let failSync = false;
  let synced: (() => void) | undefined;
  let reads = 0;
  const routed: string[] = [];
  const opened: string[] = [];
  const row = () => pr({ id: 70, repo: repository, number: 7, title, author: 'alice', origin: 'REVIEW_REQUESTED', htmlUrl,
    handledAction: 'COMMENTED', reviewedAt: '2026-02-02T00:00:00Z', monitoredAt: monitored ? '2026-02-01T00:00:00Z' : null, commentCount: 4 });
  const details = prDetail({ id: 70, repo: repository, number: 7, title, body: '**Shared detail** with #9.', author: 'alice', state: 'open', htmlUrl,
    draft: false, viewerCanWrite: false });
  const route = (workspaceId?: string) => { assert(workspaceId === workspace.id, 'Request lost its workspace account'); routed.push(workspaceId); };
  const bridge: Partial<Bridge> = {
    fetchPrs: async (id, page, size) => { route(id); reads++; return { pullRequests: [row()], page, pageSize: size, totalPullRequests: 1, openPullRequests: 1, hasMore: false }; },
    getWorkspaceHostingViewer: async id => { route(id); return 'bob'; },
    fetchWorkspaceHostingAttention: async id => { route(id); return { login: 'bob', pullRequests: [row()] }; },
    onPullRequestsSynced: callback => { synced = callback; return () => { synced = undefined; }; },
    syncPrs: async id => { route(id); if (failSync) throw new Error('Server offline'); title = 'Refreshed request'; },
    setPrMonitored: async (id, value, workspaceId) => { route(workspaceId); assert(id === 70, 'Monitoring must use the saved request ID'); monitored = value; return row(); },
    fetchPullRequestDetailByRef: async (_repo, _number, id) => { route(id); return details; },
    refreshPullRequestDetailByRef: async (_repo, _number, _checks, _opened, id) => { route(id); return { full: true, detail: details, status: null }; },
    refreshAllPullRequestDetailByRef: async (_repo, _number, id) => { route(id); return details; },
    findAgentReview: async () => null, findPullRequestTask: async () => null, listLocalReviewComments: async () => [], recordRecentTouch: async () => {},
    fetchPrDiffFiles: async () => [], fetchPrDiffCommits: async () => [],
    openInAppBrowser: async url => { opened.push(url); },
  };
  window.bridge = bridge as Bridge;
  function Screen() {
    const [number, setNumber] = useState<number | null>(null);
    return <WorkspaceShell workspace={workspace} tab="prs" onWorkspaceChanged={() => {}}
      taskAttention={{ items: [], tasks: [], readStartedAt: {}, activity: {}, refresh: () => {} }}
      initialPullRequestNumber={number} initialIssueNumber={null} initialAgentTaskId={null} onSelectTab={() => {}}
      onSelectAgentTask={() => {}} onSelectPullRequest={setNumber} onSelectIssue={() => {}} />;
  }
  try {
    flushSync(() => view.root.render(<Screen />));
    await until(() => reads > 0, 'Shared request list did not load');
    button('Open', view.host).click(); await settle();
    assert(view.host.textContent?.includes('Shared request') === true && view.host.querySelector('.pull-row') !== null, 'Both providers must use the existing rich row');
    assert(view.host.textContent?.includes('Passed') === true, 'Normalized checks must remain visible');
    button(`Monitor PR: ${repository} #7`, view.host).click(); await until(() => monitored, 'Monitoring did not save');
    window.dispatchEvent(new KeyboardEvent('keydown', { key: '5', metaKey: true, bubbles: true })); await settle();
    assert(view.host.querySelector('.pull-row__title')?.textContent?.includes('Shared request') === true, 'Reviewed filter must remain available');
    const before = reads; synced?.(); await until(() => reads > before, 'Shared refresh did not reload the cache');
    failSync = true; button('Sync pull requests', view.host).click();
    await until(() => view.host.textContent?.includes('Server offline') === true, 'Refresh failure was not shown');
    assert(view.host.textContent?.includes('Shared request') === true, 'Failed refresh discarded saved rows');
    failSync = false; button('Sync pull requests', view.host).click();
    await until(() => view.host.textContent?.includes('Refreshed request') === true, 'Refresh did not reload the shared cache');
    element<HTMLButtonElement>('.pull-row', view.host).click();
    await until(() => view.host.textContent?.includes('Shared detail') === true, 'Existing request detail did not open');
    assert(view.host.querySelector('.pull-detail') !== null, 'Detail must use the existing review screen');
    if (workspace.hostingServer) {
      element<HTMLAnchorElement>('a[href$="/-/issues/9"]', view.host).click();
      await until(() => opened.length > 0, 'Private server issue reference did not open');
      assert(opened[0] === `${server.webBaseUrl}/${repository}/-/issues/9`, 'Issue reference lost its private server or nested namespace');
      assert([...view.host.querySelectorAll<HTMLImageElement>('img')].every(image => !image.src.startsWith('https://github.com')), 'GitLab actors must not request GitHub avatars');
    }
    assert(routed.length > 3, 'List and detail must use the selected workspace');
  }
  finally { view.dispose(); }
}

export async function run() {
  return runScenarios([
    ['GitHub uses the shared list, cached detail and account routing', () => sharedRequests(github)],
    ['GitLab uses the same rich list, review detail and private server links', () => sharedRequests(gitlab)],
  ]);
}
