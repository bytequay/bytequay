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
import { useEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import { assert, button, changeInput, element, runScenarios, settle, until } from '../../fixtures/harness';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { pr, prDetail } from '../../pulls/fixtures/pullRequest';
import type { Bridge, HostingPullRequestSummaryDto, WorkspaceHostingRequestDto } from '../../types';
import { workspaceFixture } from '../../workspaces/fixtures/workspace';
import { workspacePullRequestRoute, workspaceRoute } from '../../workspaces/model';
import WorkspaceShell from '../../workspaces/WorkspaceShell';
import '../../index.css';

const server = { provider: 'GITLAB' as const, webBaseUrl: 'https://gitlab.example.com:8443/tools', apiBaseUrl: 'https://gitlab.example.com:8443/tools/api/v4' };
const gitlab = workspaceFixture({ id: 'private-project', hostingServer: server, selectedOwner: 'group/subgroup', monitoredOwner: 'group/subgroup' });
const github = workspaceFixture({ id: 'github-project' });
const summary = (number: number, overrides: Partial<HostingPullRequestSummaryDto> = {}): HostingPullRequestSummaryDto => ({
  repo: 'group/subgroup/project', number, title: `Change ${number}`, author: 'alice', htmlUrl: `${server.webBaseUrl}/group/subgroup/project/-/merge_requests/${number}`,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-02T00:00:00Z', origin: 'REVIEW_REQUESTED', labels: ['bug'], labelColors: {},
  draft: false, state: 'open', closedAt: null, mergedAt: null, requestedReviewers: ['bob'], headRef: 'fix', ...overrides,
});
const row = (number: number, overrides: Partial<HostingPullRequestSummaryDto> = {}): WorkspaceHostingRequestDto => ({ summary: summary(number, overrides), github: null });

export async function run() {
  return runScenarios([
    ['GitLab lists cached pages, filters loaded requests, refreshes and opens its private URL without GitHub reads', async () => {
      const view = createFixtureRoot();
      const reads: { id: string; page: number }[] = [];
      const opened: string[] = [];
      let syncs = 0;
      let failSync = false;
      let failMore = false;
      let title = 'Change 7';
      let synced: (() => void) | undefined;
      const bridge: Partial<Bridge> = {
        fetchWorkspaceHostingRequests: async (id, page) => {
          reads.push({ id, page });
          if (failMore && page === 2) throw new Error('Saved page unavailable');
          return page === 1 ? { items: [row(7, { title })], hasNext: true }
            : { items: [row(7, { title }), row(8, { state: 'merged', mergedAt: '2026-01-03T00:00:00Z' })], hasNext: false };
        },
        onPullRequestsSynced: callback => { synced = callback; return () => { synced = undefined; }; },
        syncPrs: async id => { assert(id === gitlab.id, 'Sync must keep workspace identity'); syncs++; if (failSync) throw new Error('Server offline'); title = 'Refreshed change'; },
        openInAppBrowser: async url => { opened.push(url); },
      };
      window.bridge = bridge as Bridge;
      try {
        flushSync(() => view.root.render(<WorkspaceShell workspace={gitlab} tab="prs" onWorkspaceChanged={() => {}}
          taskAttention={{ items: [], tasks: [], readStartedAt: {}, activity: {}, refresh: () => {} }}
          initialPullRequestNumber={null} initialIssueNumber={null} initialAgentTaskId={null} onSelectTab={() => {}}
          onSelectAgentTask={() => {}} onSelectPullRequest={() => { throw new Error('GitLab must not use GitHub routes'); }} onSelectIssue={() => {}} />));
        await until(() => view.host.textContent?.includes('Change 7') === true, 'GitLab cached page did not load');
        assert(reads.length === 1 && reads[0].id === gitlab.id && reads[0].page === 1 && syncs === 0, 'Opening the list must only read its first cached page');
        assert(view.host.textContent?.includes('No CI') === false && view.host.querySelector('img') === null, 'Summary rows must not invent CI or use GitHub avatars');
        failMore = true; button('Load more', view.host).click();
        await until(() => view.host.textContent?.includes('Saved page unavailable') === true, 'Page failure was not shown');
        assert(view.host.textContent?.includes('Change 7') === true, 'Page failure discarded saved rows');
        failMore = false; button('Load more', view.host).click();
        await until(() => !view.host.textContent?.includes('Load more'), 'Next page did not finish');
        assert(view.host.querySelectorAll('.hosting-request-row').length === 1, 'Duplicate page rows must be collapsed and merged requests kept out of Open');
        button('Closed', view.host).click(); await settle();
        assert(view.host.textContent?.includes('Change 8') === true && view.host.textContent?.includes('Merged') === true, 'Closed filter must include merged requests');
        const search = element<HTMLInputElement>('[aria-label="Search loaded requests"]', view.host);
        changeInput(search, '!7'); await settle();
        button('Change 7 !7', view.host).click(); await until(() => opened.length === 1, 'Merge request did not open');
        assert(opened[0] === summary(7).htmlUrl, 'Private server port, prefix and nested namespace must be preserved');
        button('Open', view.host).click(); await settle();
        failSync = true; button('Sync merge requests', view.host).click();
        await until(() => view.host.textContent?.includes('Server offline') === true, 'Refresh failure was not shown');
        assert(view.host.textContent?.includes('Change 7') === true, 'Refresh failure discarded saved rows');
        failSync = false; button('Sync merge requests', view.host).click();
        await until(() => view.host.textContent?.includes('Refreshed change') === true, 'Successful refresh did not reload saved pages');
        assert(reads.slice(-2).map(value => value.page).join(',') === '1,2', 'Refresh must retain loaded pages');
        title = 'Scheduled change'; synced?.();
        await until(() => view.host.textContent?.includes('Scheduled change') === true, 'Scheduled shared refresh did not reload the list');
        assert(view.host.textContent?.includes('Server offline') === false, 'Successful refresh must clear its error');
      } finally { view.dispose(); }
    }],
    ['GitHub retains rich rows, monitoring, reviewed order and routed details while listing the shared cache', async () => {
      const view = createFixtureRoot();
      const repository = `${github.monitoredOwner}/${github.monitoredRepo}`;
      const details = prDetail({ id: 70, repo: repository, number: 7, title: 'Shared title', body: 'GitHub detail remains available', author: 'alice', state: 'open',
        htmlUrl: `https://github.com/${repository}/pull/7`, draft: false, viewerCanWrite: false });
      let monitored = false;
      let reads = 0;
      const writes: number[] = [];
      const reference = summary(7, { repo: repository, title: 'Shared title', htmlUrl: details.htmlUrl ?? '', origin: 'REVIEW_REQUESTED' });
      const bridge: Partial<Bridge> = {
        fetchWorkspaceHostingRequests: async id => {
          assert(id === github.id, 'GitHub list lost its workspace'); reads++;
          return { items: [
            { summary: reference, github: pr({ id: 70, repo: repository, number: 7, title: 'Old projected title', origin: 'REVIEW_REQUESTED',
              handledAction: 'COMMENTED', reviewedAt: '2026-02-02T00:00:00Z', monitoredAt: monitored ? '2026-02-01T00:00:00Z' : null, commentCount: 4 }) },
            { summary: summary(8, { repo: repository, title: 'Earlier review', updatedAt: '2026-02-03T00:00:00Z' }),
              github: pr({ id: 80, repo: repository, number: 8, handledAction: 'APPROVED', reviewedAt: '2026-02-01T00:00:00Z' }) },
          ], hasNext: false };
        },
        setPrMonitored: async (id, value) => { writes.push(id); monitored = value; return pr({ id, repo: repository, number: 7, monitoredAt: '2026-02-01T00:00:00Z', handledAction: 'COMMENTED', reviewedAt: '2026-02-02T00:00:00Z' }); },
        onPullRequestsSynced: () => () => {},
        getUserProfile: async () => ({ login: 'bob', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0, following: 0, bio: null, location: null, company: null, email: null }),
        fetchPullRequestDetailByRef: async () => details,
        refreshPullRequestDetailByRef: async () => ({ full: true, detail: details, status: null }),
        refreshAllPullRequestDetailByRef: async () => details,
        findAgentReview: async () => null, findPullRequestTask: async () => null, listLocalReviewComments: async () => [], recordRecentTouch: async () => {},
        fetchPrDiffFiles: async () => [], fetchPrDiffCommits: async () => [],
        fetchPrs: async () => { throw new Error('Workspace list must not use legacy PR pages'); },
      };
      window.bridge = bridge as Bridge;
      const previousHash = window.location.hash;
      window.location.hash = workspaceRoute(github.id, 'prs');
      function Screen() {
        const [number, setNumber] = useState<number | null>(null);
        useEffect(() => {
          const changed = () => { const match = window.location.hash.match(/\/prs\/(\d+)$/); setNumber(match === null ? null : Number(match[1])); };
          window.addEventListener('hashchange', changed); return () => window.removeEventListener('hashchange', changed);
        }, []);
        return <WorkspaceShell workspace={github} tab="prs" onWorkspaceChanged={() => {}}
          taskAttention={{ items: [], tasks: [], readStartedAt: {}, activity: {}, refresh: () => {} }}
          initialPullRequestNumber={number} initialIssueNumber={null} initialAgentTaskId={null} onSelectTab={() => { throw new Error('Selecting same tab does not close details'); }}
          onSelectAgentTask={() => {}} onSelectPullRequest={next => { window.location.hash = workspacePullRequestRoute(github.id, next); }} onSelectIssue={() => {}} />;
      }
      try {
        flushSync(() => view.root.render(<Screen />));
        await until(() => reads > 0 && view.host.querySelector('.hp-loading') === null, 'GitHub shared list did not load');
        button('Open', view.host).click(); await settle();
        assert(view.host.textContent?.includes('Shared title') === true && !view.host.textContent?.includes('Old projected title'), 'Shared summary must be the title source');
        assert(view.host.textContent?.includes('Passed') === true, 'Available GitHub enrichment must remain visible');
        button(`Monitor PR: ${repository} #7`, view.host).click(); await until(() => writes.length === 1, 'Monitoring did not save');
        assert(writes[0] === 70, 'Monitoring must use the real legacy ID, not remote number');
        window.dispatchEvent(new KeyboardEvent('keydown', { key: '5', metaKey: true, bubbles: true })); await settle();
        assert(view.host.querySelector('.pull-row__title')?.textContent?.includes('Shared title') === true, 'Reviewed filter must keep reviewedAt ordering');
        const before = reads; window.dispatchEvent(new CustomEvent('bytequay:pr-attention-updated', { detail: [] }));
        await until(() => reads > before, 'Attention changes must refresh rich cached fields');
        element<HTMLButtonElement>('.pull-row', view.host).click();
        await until(() => view.host.textContent?.includes('GitHub detail remains available') === true, 'Existing GitHub detail did not open');
        assert(window.location.hash.endsWith('/prs/7'), 'Selected GitHub request must have a scoped route');
        button('Close pull request details', view.host).click();
        await until(() => view.host.querySelector('[role="dialog"]') === null, 'Detail overlay did not close');
        assert(window.location.hash === workspaceRoute(github.id, 'prs'), 'Closing details must remove the request number');
      } finally { view.dispose(); window.location.hash = previousHash; }
    }],
  ]);
}
