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
import AddRepoModal from '../../AddRepoModal';
import { assert, button, changeInput, element, runScenarios, settle, until } from '../../fixtures/harness';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import SettingsWindow from '../../settings/SettingsWindow';
import type { AppPreferencesDto, Bridge, HostingConnectionDto, HostingServerDto, WorkspaceDto } from '../../types';
import { workspaceFixture } from '../../workspaces/fixtures/workspace';
import WorkspaceShell from '../../workspaces/WorkspaceShell';
import { GitLabConnectionForm } from '../GitLabAccounts';
import '../../index.css';

const SERVER: HostingServerDto = { provider: 'GITLAB', webBaseUrl: 'https://gitlab.example.com:8443/tools', apiBaseUrl: 'https://gitlab.example.com:8443/tools/api/v4' };
const account = (id: number, server = SERVER): HostingConnectionDto => ({
  account: { id, instanceId: id === 3 ? 2 : 1, remoteId: String(id), login: `developer-${id}` },
  instance: { ...server, id: id === 3 ? 2 : 1 }, connected: true,
});
const WORKSPACE = workspaceFixture({ hostingServer: SERVER, name: 'group/subgroup/project', selectedOwner: 'group/subgroup',
  monitoredOwner: 'group/subgroup', lastCodeSyncAt: '2026-10-01T00:00:00Z' });
const preferences: AppPreferencesDto = { theme: 'SYSTEM', taskRetentionDays: 7, recentRetentionDays: 14, prWarmCount: 30, autoMergeDelayMinutes: 60 };

function select(host: HTMLElement, id: number) {
  const field = element<HTMLSelectElement>('[aria-label="GitLab account"]', host);
  field.value = String(id); field.dispatchEvent(new Event('change', { bubbles: true }));
}

export async function run() {
  return runScenarios([
    ['Connect GitLab.com with its API URL and clear the token', async () => {
      const view = createFixtureRoot();
      const requests: (HostingServerDto & { token: string })[] = [];
      let connected = false;
      window.bridge = { connectHostingAccount: async request => { requests.push(request); return account(1, request); } } as Bridge;
      try {
        flushSync(() => view.root.render(<GitLabConnectionForm onConnected={() => { connected = true; }} />));
        const token = element<HTMLInputElement>('[aria-label="GitLab personal access token"]', view.host);
        changeInput(token, 'test-token'); await settle(); button('Connect GitLab', view.host).click();
        await until(() => connected, 'GitLab.com connection did not finish');
        assert(requests[0].webBaseUrl === 'https://gitlab.com' && requests[0].apiBaseUrl === 'https://gitlab.com/api/v4', 'GitLab.com URLs differ');
        assert(token.value === '', 'Submitted token remains visible');
      } finally { view.dispose(); }
    }],
    ['Connect a private GitLab server, resolve a nested project, and open its workspace without GitHub calls', async () => {
      const view = createFixtureRoot();
      const requests: (HostingServerDto & { token: string })[] = [];
      const resolved: { accountId: number; path: string }[] = [];
      const created: { accountId: number; repositoryId: number }[] = [];
      const bridge: Partial<Bridge> = {
        listHostingAccounts: async () => [],
        connectHostingAccount: async request => { requests.push(request); return account(1, request); },
        resolveHostingRepository: async (accountId, path) => {
          resolved.push({ accountId, path });
          return { id: 8, instanceId: 1, remoteId: '42', fullPath: path, webUrl: `${SERVER.webBaseUrl}/${path}`,
            httpCloneUrl: `${SERVER.webBaseUrl}/${path}.git`, sshCloneUrl: null, defaultBranch: 'main' };
        },
        createHostingWorkspace: async (accountId, repositoryId) => { created.push({ accountId, repositoryId }); return WORKSPACE; },
      };
      window.bridge = bridge as Bridge;
      function Flow() {
        const [workspace, setWorkspace] = useState<WorkspaceDto | null>(null);
        return workspace === null ? <AddRepoModal onAdded={setWorkspace} onClose={() => {}} onOpenWorkspace={() => {}} />
          : <WorkspaceShell workspace={workspace} onWorkspaceChanged={setWorkspace} tab="home"
            taskAttention={{ items: [], tasks: [], readStartedAt: {}, activity: {}, refresh: () => {} }}
            onSelectTab={() => {}} initialPullRequestNumber={null} initialIssueNumber={null} initialAgentTaskId={null}
            onSelectAgentTask={() => {}} onSelectPullRequest={() => {}} onSelectIssue={() => {}} />;
      }
      try {
        flushSync(() => view.root.render(<Flow />)); button('GitLab', view.host).click();
        await until(() => view.host.querySelector('[aria-label="GitLab server"]') !== null, 'GitLab form did not load');
        changeInput(element<HTMLInputElement>('[aria-label="GitLab server"]', view.host), `${SERVER.webBaseUrl}/`);
        changeInput(element<HTMLInputElement>('[aria-label="GitLab personal access token"]', view.host), 'private-token');
        await settle(); button('Connect GitLab', view.host).click();
        await until(() => view.host.querySelector('[aria-label="GitLab project path"]') !== null, 'Connected account was not selected');
        assert(requests[0].webBaseUrl === SERVER.webBaseUrl && requests[0].apiBaseUrl === SERVER.apiBaseUrl, 'Private port or prefix was lost');
        changeInput(element<HTMLInputElement>('[aria-label="GitLab project path"]', view.host), 'group/subgroup/project');
        await settle(); button('Find project', view.host).click();
        await until(() => view.host.textContent?.includes('clone this project directly') === true, 'Project preview did not load');
        button('Create workspace', view.host).click();
        await until(() => view.host.textContent?.includes('Local checkout ready') === true, 'Created workspace did not open');
        assert(resolved[0].accountId === 1 && resolved[0].path === 'group/subgroup/project', 'Resolve did not use selected account and full project path');
        assert(created[0].accountId === 1 && created[0].repositoryId === 8, 'Create did not use the resolved project');
        assert(view.host.textContent?.includes('not available yet'), 'Workspace must explain unavailable actions');
      } finally { view.dispose(); }
    }],
    ['Workspace settings switch only between accounts on the same GitLab server', async () => {
      const view = createFixtureRoot();
      const writes: number[] = [];
      let accountId = 1;
      const binding = () => ({ workspaceId: WORKSPACE.id, accountId, selectedRepositoryId: 8, upstreamRepositoryId: null });
      const bridge: Partial<Bridge> = {
        getAppVersion: async () => ({ version: '1.0.0' }), getAppPreferences: async () => preferences,
        listHostingAccounts: async () => [account(1), account(2), account(3, { provider: 'GITLAB', webBaseUrl: 'https://gitlab.com', apiBaseUrl: 'https://gitlab.com/api/v4' })],
        getWorkspaceHosting: async () => binding(),
        setWorkspaceHosting: async (_id: string, selected: number) => { accountId = selected; writes.push(selected); return binding(); },
      };
      window.bridge = bridge as Bridge;
      try {
        flushSync(() => view.root.render(<SettingsWindow section="ws-token" workspace={WORKSPACE} onWorkspacesChanged={() => {}} />));
        await until(() => view.host.querySelector('[aria-label="GitLab account"]') !== null, 'Account picker did not load');
        const picker = element<HTMLSelectElement>('[aria-label="GitLab account"]', view.host);
        assert(picker.value === '1' && picker.options.length === 3, 'Binding or same-server filter is incorrect');
        select(view.host, 2); await until(() => picker.value === '2', 'New binding was not displayed');
        assert(writes.length === 1 && writes[0] === 2, 'Account change must update the workspace binding');
      } finally { view.dispose(); }
    }],
  ]);
}
