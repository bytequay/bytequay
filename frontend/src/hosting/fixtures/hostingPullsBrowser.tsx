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
import type { Bridge, HostingPullRequestSnapshotDto, HostingPullRequestSummaryDto, WorkspaceHostingRequestDto } from '../../types';
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
    ['GitLab lists cached pages, filters loaded requests, refreshes and routes selections without GitHub reads', async () => {
      const view = createFixtureRoot();
      const reads: { id: string; page: number }[] = [];
      const selected: number[] = [];
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
      };
      window.bridge = bridge as Bridge;
      try {
        flushSync(() => view.root.render(<WorkspaceShell workspace={gitlab} tab="prs" onWorkspaceChanged={() => {}}
          taskAttention={{ items: [], tasks: [], readStartedAt: {}, activity: {}, refresh: () => {} }}
          initialPullRequestNumber={null} initialIssueNumber={null} initialAgentTaskId={null} onSelectTab={() => {}}
          onSelectAgentTask={() => {}} onSelectPullRequest={number => { selected.push(number); }} onSelectIssue={() => {}} />));
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
        button('Change 7 !7', view.host).click(); await until(() => selected.length === 1, 'Merge request was not selected');
        assert(selected[0] === 7, 'Merge request selection must use the workspace route');
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
    ['GitLab overview keeps scoped cached detail, refresh errors, web links and deep-link navigation', async () => {
      const view = createFixtureRoot();
      const cached: HostingPullRequestSnapshotDto = { pullRequest: summary(7), validatedAt: '2026-01-02T00:00:00Z', detail: {
        title: 'Saved overview', htmlUrl: summary(7).htmlUrl, state: 'open', author: 'alice', authorAvatarUrl: null,
        createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-02T00:00:00Z', mergedAt: null,
        body: '**Cached description** with @alice and #9. [Project issue](/tools/group/subgroup/project/-/issues/9).\n\n'
          + '[External reference](https://github.com/example/project/pull/2)\n\n![Attachment](/uploads/hash/image.png)',
        labels: ['bug'], labelColors: {}, assignees: ['alice'], milestone: 'Release', draft: false,
        requestedReviewerCount: -1, requestedReviewers: [], headRef: 'fix', baseRef: 'main',
      } };
      const reads: number[] = [];
      const refreshes: number[] = [];
      const opened: string[] = [];
      let rejectRefresh: ((error: Error) => void) | undefined;
      const firstRefresh = new Promise<HostingPullRequestSnapshotDto>((_resolve, reject) => { rejectRefresh = reject; });
      const bridge: Partial<Bridge> = {
        fetchWorkspaceHostingRequests: async () => ({ items: [row(7)], hasNext: false }),
        onPullRequestsSynced: () => () => {},
        fetchWorkspaceHostingRequest: async (id, number) => { assert(id === gitlab.id, 'Cached detail lost the workspace'); reads.push(number); return number === 7 ? cached : null; },
        refreshWorkspaceHostingRequest: async (id, number) => {
          assert(id === gitlab.id, 'Refresh lost the workspace'); refreshes.push(number);
          if (refreshes.length === 1) return firstRefresh;
          if (number === 8) throw new Error('Account disconnected');
          return { ...cached, detail: { ...cached.detail, title: 'Current overview', body: 'Updated description\n\n' + 'More detail about the proposed change.\n\n'.repeat(60) } };
        },
        openInAppBrowser: async url => { opened.push(url); },
      };
      window.bridge = bridge as Bridge;
      const previousHash = window.location.hash;
      window.location.hash = workspacePullRequestRoute(gitlab.id, 7);
      function Screen() {
        const [number, setNumber] = useState<number | null>(7);
        useEffect(() => {
          const changed = () => { const match = window.location.hash.match(/\/prs\/(\d+)$/); setNumber(match === null ? null : Number(match[1])); };
          window.addEventListener('hashchange', changed); return () => window.removeEventListener('hashchange', changed);
        }, []);
        return <WorkspaceShell workspace={gitlab} tab="prs" onWorkspaceChanged={() => {}}
          taskAttention={{ items: [], tasks: [], readStartedAt: {}, activity: {}, refresh: () => {} }}
          initialPullRequestNumber={number} initialIssueNumber={null} initialAgentTaskId={null} onSelectTab={() => {}}
          onSelectAgentTask={() => {}} onSelectPullRequest={next => { window.location.hash = workspacePullRequestRoute(gitlab.id, next); }} onSelectIssue={() => {}} />;
      }
      try {
        flushSync(() => view.root.render(<Screen />));
        await until(() => view.host.textContent?.includes('Cached description') === true && refreshes.length === 1, 'Saved details must appear while remote refresh is pending');
        const detail = element<HTMLElement>('[role="dialog"]', view.host);
        assert(reads.join(',') === '7' && refreshes.join(',') === '7', 'Deep link must read and refresh only its request');
        assert(detail.textContent?.includes('fix → main') === true && detail.textContent?.includes('Unavailable') === true, 'Unknown pending reviewers must not become an empty review state');
        assert(detail.querySelector('strong')?.textContent === 'Cached description', 'Description Markdown must render');
        assert(detail.querySelector('.md-reference, .md-code-reference, img') === null, 'GitLab Markdown must not use GitHub previews or fetch upload paths');
        assert(detail.textContent?.includes('/uploads/hash/image.png') === true, 'Unsupported attachments must remain readable');
        element<HTMLAnchorElement>('a[href$="/issues/9"]', detail).click();
        await until(() => opened.length === 1, 'Description link did not open');
        assert(opened[0] === server.webBaseUrl + '/group/subgroup/project/-/issues/9', 'Server-path links must retain private-server coordinates');
        element<HTMLAnchorElement>('a[href="https://github.com/example/project/pull/2"]', detail).click();
        await until(() => opened.length === 2, 'Explicit cross-provider links should remain ordinary browser links');
        button('Open in GitLab', detail).click(); await until(() => opened.length === 3, 'Provider request did not open');
        assert(opened[2] === summary(7).htmlUrl && document.querySelector('dialog:modal') === null, 'The detail layer must allow the embedded browser to be shown');
        rejectRefresh?.(new Error('Server offline'));
        await until(() => detail.textContent?.includes('Server offline') === true, 'Refresh error was not shown');
        assert(detail.textContent?.includes('Showing saved details.') === true && detail.textContent?.includes('Cached description') === true, 'Refresh failure must retain cached content');
        button('Refresh', detail).click();
        await until(() => detail.textContent?.includes('Updated description') === true, 'Refresh retry did not update metadata');
        assert(detail.textContent?.includes('Server offline') === false, 'Successful refresh must clear old errors');
        assert(getComputedStyle(detail).overflowY === 'auto' && detail.scrollHeight > detail.clientHeight, 'Long descriptions must remain scrollable');
        detail.scrollTop = detail.scrollHeight;
        assert(detail.scrollTop > 0, 'The overview must allow scrolling to its final content');
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        await until(() => view.host.querySelector('[role="dialog"]') === null, 'Escape must close details');
        assert(window.location.hash === workspaceRoute(gitlab.id, 'prs'), 'Closing must remove the selected request route');
        window.location.hash = workspacePullRequestRoute(gitlab.id, 8);
        await until(() => view.host.textContent?.includes('Account disconnected') === true, 'Uncached deep link failure must be visible');
        const missing = element<HTMLElement>('[role="dialog"]', view.host);
        assert(missing.textContent?.includes('Merge request !8') === true && !missing.textContent?.includes('Updated description'), 'Uncached requests must not show another request details');
        button('Close merge request details', missing).click();
        await until(() => view.host.querySelector('[role="dialog"]') === null, 'Close button must clear details');
      } finally { view.dispose(); window.location.hash = previousHash; }
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
