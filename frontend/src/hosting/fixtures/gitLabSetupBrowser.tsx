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

const readyCli = async () => ({ ready: true, homebrewAvailable: true });
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
    ['Install GitLab CLI on click before browser sign-in and stop on installation failure', async () => {
      const view = createFixtureRoot();
      const installation = Promise.withResolvers<{ ready: boolean; homebrewAvailable: boolean }>();
      let installs = 0;
      let logins = 0;
      let connected = false;
      const bridge: Partial<Bridge> = {
        getGitLabCliStatus: async () => ({ ready: false, homebrewAvailable: true }),
        installGitLabCli: async () => { installs++; if (installs === 1) throw new Error('Installation failed'); return installation.promise; },
        connectHostingCliAccount: async request => { logins++; assert(request.webBaseUrl === SERVER.webBaseUrl, 'Sign-in lost its selected server'); return account(1, request); },
      };
      window.bridge = bridge as Bridge;
      try {
        flushSync(() => view.root.render(<GitLabConnectionForm server={SERVER} onConnected={() => { connected = true; }} />));
        await until(() => view.host.textContent?.includes('Install and sign in') === true, 'Missing install guidance');
        assert(installs === 0 && logins === 0 && view.host.textContent?.includes('brew install glab'), 'Opening setup must not install or authenticate');
        button('Install and sign in', view.host).click();
        await until(() => view.host.querySelector('[role="alert"]')?.textContent === 'Installation failed', 'Install failure was not shown');
        assert(logins === 0 && !connected, 'Failed installation must not start sign-in');
        button('Install and sign in', view.host).click();
        await until(() => installs === 2, 'Installation was not retryable');
        assert(button('Installing GitLab CLI…', view.host).disabled && button('Create access token', view.host).disabled, 'Installation must disable connection controls');
        installation.resolve({ ready: true, homebrewAvailable: true });
        await until(() => connected, 'Successful installation did not continue to browser sign-in');
        assert(Number(logins) === 1 && element<HTMLInputElement>('[aria-label="GitLab personal access token"]', view.host).value === '', 'Guided sign-in must not require a token');
      } finally { view.dispose(); }
    }],
    ['Retry CLI checks and guide Homebrew setup without installing or authenticating automatically', async () => {
      const view = createFixtureRoot();
      const opened: string[] = [];
      let checks = 0;
      let installs = 0;
      let logins = 0;
      const bridge: Partial<Bridge> = {
        getGitLabCliStatus: async () => { checks++; if (checks === 1) throw new Error('CLI check failed'); return { ready: checks > 2, homebrewAvailable: checks > 2 }; },
        installGitLabCli: async () => { installs++; return readyCli(); },
        openExternal: async url => { opened.push(url); },
        connectHostingCliAccount: async request => { logins++; return account(1, request); },
      };
      window.bridge = bridge as Bridge;
      try {
        flushSync(() => view.root.render(<GitLabConnectionForm onConnected={() => {}} />));
        await until(() => view.host.querySelector('[role="alert"]')?.textContent === 'CLI check failed', 'Check failure was not shown');
        button('Check again', view.host).click();
        await until(() => view.host.textContent?.includes('Open Homebrew setup') === true, 'Missing Homebrew guidance');
        button('Open Homebrew setup', view.host).click(); await settle();
        assert(opened[0] === 'https://brew.sh' && !element<HTMLInputElement>('[aria-label="GitLab personal access token"]', view.host).disabled, 'Homebrew guidance must retain token fallback');
        button('Check again', view.host).click();
        await until(() => view.host.textContent?.includes('GitLab CLI is ready.') === true, 'Recheck did not find installed CLI');
        assert(checks === 3 && installs === 0 && logins === 0, 'Status checks must not install or sign in');
        button('Sign in with GitLab', view.host).click(); await until(() => logins === 1, 'Ready CLI did not start sign-in');
        assert(installs === 0, 'Ready CLI must skip installation');
      } finally { view.dispose(); }
    }],
    ['Sign in through GitLab CLI without a token and retry a failed browser login', async () => {
      const view = createFixtureRoot();
      const requests: HostingServerDto[] = [];
      let connected: HostingConnectionDto | undefined;
      const login = Promise.withResolvers<void>();
      let tokenConnections = 0;
      const bridge: Partial<Bridge> = {
        getGitLabCliStatus: readyCli,
        connectHostingCliAccount: async request => {
          requests.push(request);
          if (requests.length === 1) throw new Error('Install or update GitLab CLI to sign in');
          await login.promise;
          return account(1, request);
        },
        connectHostingAccount: async request => { tokenConnections++; return account(1, request); },
      };
      window.bridge = bridge as Bridge;
      try {
        flushSync(() => view.root.render(<GitLabConnectionForm onConnected={value => { connected = value; }} />));
        await until(() => view.host.textContent?.includes('GitLab CLI is ready.') === true, 'GitLab CLI check did not finish');
        button('Sign in with GitLab', view.host).click();
        await until(() => view.host.querySelector('[role="alert"]')?.textContent?.includes('Install or update') === true, 'CLI login error was not shown');
        assert(requests[0].webBaseUrl === 'https://gitlab.com' && connected === undefined, 'Failed GitLab.com login connected an account');
        changeInput(element<HTMLInputElement>('[aria-label="GitLab server"]', view.host), `${SERVER.webBaseUrl}/`);
        await settle(); button('Sign in with GitLab', view.host).click();
        await until(() => requests.length === 2, 'CLI login did not start');
        assert(button('Waiting for GitLab…', view.host).disabled && button('Create access token', view.host).disabled, 'Login must disable connection controls');
        assert(element<HTMLInputElement>('[aria-label="GitLab personal access token"]', view.host).value === '', 'CLI login must not require a token');
        login.resolve(); await until(() => connected !== undefined, 'CLI login did not select its account');
        assert(requests[1].webBaseUrl === SERVER.webBaseUrl && requests[1].apiBaseUrl === SERVER.apiBaseUrl, 'CLI login lost the private port or prefix');
        assert(!('token' in requests[1]) && tokenConnections === 0, 'CLI login must not send a token or submit the token form');
        assert(view.host.querySelector('[role="alert"]') === null, 'Successful retry retained the login error');
      } finally { view.dispose(); }
    }],
    ['Open token creation for the selected GitLab server without connecting', async () => {
      const view = createFixtureRoot();
      const opened: string[] = [];
      let connections = 0;
      let openingFails = false;
      const bridge: Partial<Bridge> = {
        getGitLabCliStatus: readyCli,
        openExternal: async url => { if (openingFails) throw new Error('Browser could not open'); opened.push(url); },
        connectHostingAccount: async request => { connections++; return account(1, request); },
      };
      window.bridge = bridge as Bridge;
      try {
        flushSync(() => view.root.render(<GitLabConnectionForm onConnected={() => {}} />));
        const server = element<HTMLInputElement>('[aria-label="GitLab server"]', view.host);
        const token = element<HTMLInputElement>('[aria-label="GitLab personal access token"]', view.host);
        for (const webBaseUrl of ['https://gitlab.com', `${SERVER.webBaseUrl}/`]) {
          changeInput(server, webBaseUrl); await settle(); button('Create access token', view.host).click(); await settle();
          const target = new URL(opened.at(-1) ?? '');
          assert(`${target.origin}${target.pathname}` === `${webBaseUrl.replace(/\/+$/, '')}/-/user_settings/personal_access_tokens`, 'Token page lost the selected server');
          assert(target.searchParams.get('name') === 'ByteQuay' && target.searchParams.get('scopes') === 'read_api,read_repository', 'Token name or scopes differ');
        }
        assert(opened.length === 2 && connections === 0 && token.value === '', 'Token shortcut must work without a token or a connection');
        for (const invalid of ['', 'not a URL', 'ftp://gitlab.example.com', 'https://user:password@gitlab.example.com', 'https://gitlab.example.com?query=yes', 'https://gitlab.example.com#fragment']) {
          changeInput(server, invalid); await settle(); button('Create access token', view.host).click(); await settle();
          assert(opened.length === 2 && view.host.querySelector('[role="alert"]')?.textContent, `Invalid server must show an error without opening: ${invalid}`);
        }
        openingFails = true;
        changeInput(server, 'https://gitlab.com'); await settle(); button('Create access token', view.host).click();
        await until(() => view.host.querySelector('[role="alert"]')?.textContent?.includes('Browser could not open') === true, 'Browser failure was not shown');
        assert(connections === 0, 'Token shortcut must not submit the connection form');
      } finally { view.dispose(); }
    }],
    ['Connect GitLab.com with its API URL and clear the token', async () => {
      const view = createFixtureRoot();
      const requests: (HostingServerDto & { token: string })[] = [];
      let connected = false;
      window.bridge = { getGitLabCliStatus: readyCli, connectHostingAccount: async request => { requests.push(request); return account(1, request); } } as Bridge;
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
        getGitLabCliStatus: readyCli,
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
        getGitLabCliStatus: readyCli,
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
