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
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import AddRepoModal from '../../AddRepoModal';
import ErrorDetails from '../../ErrorDetails';
import { assert, button, changeInput, element, runScenarios, settle, until } from '../../fixtures/harness';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import SettingsWindow from '../../settings/SettingsWindow';
import type { AppPreferencesDto, Bridge, HostingConnectionDto, HostingPermissionsDto, HostingRepositoryDto, HostingServerDto, WorkspaceDto } from '../../types';
import { workspaceFixture } from '../../workspaces/fixtures/workspace';
import WorkspaceShell from '../../workspaces/WorkspaceShell';
import GitLabAccounts, { GitLabConnectionForm } from '../GitLabAccounts';
import GitLabSettings from '../GitLabSettings';
import '../../index.css';

const readyCli = async () => ({ ready: true, homebrewAvailable: true });
const SERVER: HostingServerDto = { provider: 'GITLAB', webBaseUrl: 'https://gitlab.example.com:8443/tools', apiBaseUrl: 'https://gitlab.example.com:8443/tools/api/v4' };
const account = (id: number, server = SERVER): HostingConnectionDto => ({
  account: { id, instanceId: id === 3 ? 2 : 1, remoteId: String(id), login: `developer-${id}` },
  instance: { ...server, id: id === 3 ? 2 : 1 }, connected: true, permissions: null,
});
const WORKSPACE = workspaceFixture({ hostingServer: SERVER, name: 'group/subgroup/project', selectedOwner: 'group/subgroup',
  monitoredOwner: 'group/subgroup', lastCodeSyncAt: '2026-10-01T00:00:00Z' });
const repository = (id = 8, server = SERVER, fullPath = 'group/subgroup/project'): HostingRepositoryDto => ({
  id, instanceId: 1, remoteId: '42', fullPath, webUrl: `${server.webBaseUrl}/${fullPath}`,
  httpCloneUrl: `${server.webBaseUrl}/${fullPath}.git`, sshCloneUrl: null, defaultBranch: 'main',
});
const preferences: AppPreferencesDto = { theme: 'SYSTEM', taskRetentionDays: 7, recentRetentionDays: 14, prWarmCount: 30, autoMergeDelayMinutes: 60 };

function select(host: HTMLElement, id: number) {
  const field = element<HTMLSelectElement>('[aria-label="GitLab account"]', host);
  field.value = String(id); field.dispatchEvent(new Event('change', { bubbles: true }));
}

function accountCard(host: HTMLElement, login: string): HTMLElement {
  const card = [...host.querySelectorAll<HTMLElement>('.setw-card')].find(value => value.textContent?.includes(login));
  assert(card !== undefined, `Missing account card for ${login}`);
  return card;
}

export async function run() {
  return runScenarios([
    ['Copy readable error diagnostics with app version and report clipboard failures', async () => {
      const view = createFixtureRoot();
      const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
      const copied: string[] = [];
      let versionFails = false;
      let clipboardFails = true;
      let versionReads = 0;
      const message = 'GitLab project access failed (HTTP 404).\n\nRequest: GET /api/v4/projects/:project/merge_requests/:request\n'
        + 'Time (UTC): 2026-01-01T12:00:00Z\nGitLab request ID: request-123';
      window.bridge = { getAppVersion: async () => { versionReads++; if (versionFails) throw new Error('Version unavailable'); return { version: '0.5.3' }; } } as Bridge;
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async (value: string) => {
        if (clipboardFails) throw new Error('Clipboard unavailable');
        copied.push(value);
      } } });
      try {
        flushSync(() => view.root.render(<ErrorDetails message={message} />));
        assert(element('.error-details__message', view.host).textContent === message && versionReads === 0, 'Diagnostics must remain readable without requesting app details on display');
        assert(getComputedStyle(element('.error-details__message', view.host)).whiteSpace === 'pre-wrap', 'Diagnostic newlines must remain visible');
        button('Copy error details', view.host).click();
        await until(() => view.host.textContent?.includes('Could not copy. Try again.') === true, 'Clipboard failure must be visible');
        assert(copied.length === 0 && view.host.querySelector('.is-copied') === null, 'Failed clipboard write must not claim success');
        clipboardFails = false;
        button('Copy error details', view.host).click();
        await until(() => copied.length === 1, 'Copy retry did not finish');
        assert(copied[0] === `ByteQuay 0.5.3\n\n${message}`, 'Copied diagnostics lost version, status, endpoint, time or request ID');
        assert(!view.host.textContent?.includes('Could not copy. Try again.'), 'Successful copy retained its failure');
        versionFails = true;
        element<HTMLButtonElement>('.pr-copy-button', view.host).click();
        await until(() => copied.length === 2, 'Version failure must not prevent copying');
        assert(copied[1] === message, 'Failed version lookup changed the error details');
      } finally {
        view.dispose();
        if (originalClipboard) Object.defineProperty(navigator, 'clipboard', originalClipboard);
        else Reflect.deleteProperty(navigator, 'clipboard');
      }
    }],
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
        assert(installs === 0 && logins === 0, 'Opening setup must not install or authenticate');
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
        await until(() => view.host.textContent?.includes('Sign in with GitLab') === true, 'Recheck did not find installed CLI');
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
        await until(() => view.host.textContent?.includes('Sign in with GitLab') === true, 'GitLab CLI check did not finish');
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
          assert(target.searchParams.get('name') === 'ByteQuay' && target.searchParams.get('scopes') === 'api', 'Token name or scopes differ');
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
      window.bridge = { getGitLabCliStatus: readyCli, connectHostingAccount: async request => {
        requests.push(request);
        if (requests.length === 1) throw new Error("Error invoking remote method 'hosting:connect': Error: Missing api permission. Create a token with api scope.");
        return account(1, request);
      } } as Bridge;
      try {
        flushSync(() => view.root.render(<GitLabConnectionForm onConnected={() => { connected = true; }} />));
        const token = element<HTMLInputElement>('[aria-label="GitLab personal access token"]', view.host);
        changeInput(token, 'test-token'); await settle(); button('Connect GitLab', view.host).click();
        await until(() => view.host.querySelector('[role="alert"]')?.textContent === 'Missing api permission. Create a token with api scope.', 'Permission rejection lost its repair guidance');
        assert(!connected && token.value === 'test-token', 'Missing permission must keep the connection form open for correction');
        changeInput(token, 'replacement-token'); await settle(); button('Connect GitLab', view.host).click();
        await until(() => connected, 'GitLab.com connection did not finish');
        assert(requests[0].webBaseUrl === 'https://gitlab.com' && requests[0].apiBaseUrl === 'https://gitlab.com/api/v4', 'GitLab.com URLs differ');
        assert(element<HTMLInputElement>('[aria-label="GitLab personal access token"]', view.host).value === '', 'Submitted token remains visible');
      } finally { view.dispose(); }
    }],
    ['Connect a private GitLab server, search a nested project, and watch it without GitHub calls', async () => {
      const view = createFixtureRoot();
      const requests: (HostingServerDto & { token: string })[] = [];
      const searches: { accountId: number; query: string }[] = [];
      const created: { accountId: number; repositoryId: number }[] = [];
      const bridge: Partial<Bridge> = {
        ...createPreviewBridge(WORKSPACE.id),
        getWorkspaceHostingViewer: async () => (await window.bridge.getUserProfile?.().catch(() => null))?.login ?? null,
    fetchWorkspaceHostingAttention: async () => ({ login: null, pullRequests: [] }),
        onPullRequestsSynced: () => () => {},
        listJiraConnections: async () => [], listJiraAttention: async () => [],
        getGitLabCliStatus: readyCli,
        listHostingAccounts: async () => [],
        connectHostingAccount: async request => {
          requests.push(request);
          return { ...account(1, request), permissions: { status: 'VERIFIED', scopes: ['api'], detail: 'API scope verified; project membership still applies.' } };
        },
        searchHostingRepositories: async (accountId, query) => { searches.push({ accountId, query }); return query === '' ? [] : [repository()]; },
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
        await until(() => view.host.querySelector('[aria-label="Search GitLab projects"]') !== null, 'Connected account was not selected');
        assert(view.host.textContent?.includes('Permissions verified') && view.host.textContent.includes('Granted scopes: api'),
          'Workspace setup must show the permissions returned by sign-in');
        assert(requests[0].webBaseUrl === SERVER.webBaseUrl && requests[0].apiBaseUrl === SERVER.apiBaseUrl, 'Private port or prefix was lost');
        changeInput(element<HTMLInputElement>('[aria-label="Search GitLab projects"]', view.host), 'group/subgroup/project');
        await until(() => view.host.querySelector('.modal-repo-row') !== null, 'Project search did not return a row');
        element<HTMLElement>('.modal-repo-row', view.host).click();
        await until(() => view.host.querySelector('.agt-home') !== null, 'Created workspace did not open');
        assert(searches.some(value => value.accountId === 1 && value.query === 'group/subgroup/project'), 'Search lost the selected account or full project path');
        assert(created[0].accountId === 1 && created[0].repositoryId === 8, 'Create did not use the resolved project');
        assert(view.host.textContent?.includes('Needs attention') === true && view.host.textContent?.includes('Open tasks') === true, 'GitLab must render the existing Home');
      } finally { view.dispose(); }
    }],
    ['Select the first connected account automatically and watch a project by name', async () => {
      for (const count of [1, 2]) {
        const view = createFixtureRoot();
        const searches: { accountId: number; query: string }[] = [];
        const created: number[] = [];
        const publicServer: HostingServerDto = { provider: 'GITLAB', webBaseUrl: 'https://gitlab.com', apiBaseUrl: 'https://gitlab.com/api/v4' };
        const rows = [account(1, publicServer), ...(count === 2 ? [account(2)] : [])];
        window.bridge = {
          listHostingAccounts: async () => [{ ...account(9), connected: false }, ...rows],
          searchHostingRepositories: async (accountId, query, limit) => {
            assert(limit === 30, 'Search limit differs from GitHub'); searches.push({ accountId, query });
            return query === '' ? [] : [repository(8, publicServer)];
          },
          createHostingWorkspace: async (accountId, repositoryId) => { created.push(accountId, repositoryId); return WORKSPACE; },
        } as Partial<Bridge> as Bridge;
        let added = false;
        try {
          flushSync(() => view.root.render(<AddRepoModal defaultProvider="GITLAB" onAdded={() => { added = true; }} onClose={() => {}} onOpenWorkspace={() => {}} />));
          await until(() => searches.length === 1, 'First connected account was not selected');
          assert(element<HTMLSelectElement>('[aria-label="GitLab account"]', view.host).value === '1', 'Selected account was not filled in');
          assert(searches[0].accountId === 1 && searches[0].query === '', 'Opening search must list the selected account projects');
          assert(view.host.querySelector('h2')?.textContent === 'Watch a repository', 'Watch header should match GitHub');
          assert(!view.host.textContent?.includes('Create GitLab workspace') && !view.host.textContent?.includes('Find project'), 'Extra creation step is still present');
          changeInput(element<HTMLInputElement>('[aria-label="Search GitLab projects"]', view.host), 'project');
          await until(() => view.host.querySelector('.modal-repo-row') !== null, 'Typing a project name did not search');
          assert(searches.length === 2 && searches[1].query === 'project' && created.length === 0, 'Typing must search without creating a workspace');
          element<HTMLElement>('.modal-repo-row', view.host).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
          await until(() => added, 'Watching a result did not create the workspace');
          assert(created.join(',') === '1,8', 'Watching lost its account or repository identity');
        } finally { view.dispose(); }
      }
    }],
    ['Switch accounts for project search and recover from empty results or errors', async () => {
      const view = createFixtureRoot();
      const searches: { accountId: number; query: string }[] = [];
      window.bridge = {
        listHostingAccounts: async () => [account(1), account(2)],
        searchHostingRepositories: async (accountId, query) => {
          searches.push({ accountId, query });
          if (query === 'failure') throw new Error("Error invoking remote method 'hosting:repositories:search': Error: Project search failed");
          return query === 'project' ? [repository()] : [];
        },
      } as Partial<Bridge> as Bridge;
      try {
        flushSync(() => view.root.render(<AddRepoModal initialWorkspace={WORKSPACE} onAdded={() => {}} onClose={() => {}} onOpenWorkspace={() => {}} />));
        await until(() => searches.length === 1, 'Default account did not load');
        assert(searches[0].query === 'group/subgroup/project', 'Watching an existing workspace lost its nested project path');
        select(view.host, 2);
        await until(() => searches.length === 2, 'Changing the account did not refresh projects');
        assert(searches[1].accountId === 2, 'Search reused the first account');
        const field = element<HTMLInputElement>('[aria-label="Search GitLab projects"]', view.host);
        changeInput(field, 'missing');
        await until(() => view.host.textContent?.includes('No repository matches “missing”.') === true, 'Empty search was not shown');
        changeInput(field, 'failure');
        await until(() => view.host.querySelector('[role="alert"]')?.textContent === 'Project search failed', 'Search failure was not shown');
        changeInput(field, 'project');
        await until(() => view.host.querySelector('.modal-repo-row') !== null, 'Search did not recover');
        assert(view.host.querySelector('[role="alert"]') === null && searches.at(-1)?.accountId === 2, 'Recovery lost the selected account');
      } finally { view.dispose(); }
    }],
    ['Show all GitLab account cards immediately without selecting or changing an account', async () => {
      const view = createFixtureRoot();
      const publicServer: HostingServerDto = { provider: 'GITLAB', webBaseUrl: 'https://gitlab.com', apiBaseUrl: 'https://gitlab.com/api/v4' };
      let writes = 0;
      window.bridge = {
        listHostingAccounts: async () => [account(1, publicServer), { ...account(2), connected: false }],
        disconnectHostingAccount: async () => { writes++; },
        connectHostingAccount: async request => { writes++; return account(1, request); },
        connectHostingCliAccount: async request => { writes++; return account(1, request); },
      } as Partial<Bridge> as Bridge;
      try {
        flushSync(() => view.root.render(<GitLabSettings />));
        await until(() => view.host.textContent?.includes('developer-2') === true, 'Saved accounts did not appear');
        const connected = accountCard(view.host, 'developer-1');
        const disconnected = accountCard(view.host, 'developer-2');
        assert(connected.textContent?.includes('https://gitlab.com') && connected.textContent.includes('Connected'), 'Connected account must show its server and status');
        assert(disconnected.textContent?.includes(SERVER.webBaseUrl) && disconnected.textContent.includes('Disconnected'), 'Disconnected account must remain visible with its server');
        button('Update…', connected); button('Disconnect', connected); button('Reconnect', disconnected);
        assert(view.host.querySelector('select') === null && writes === 0, 'Opening settings must not select or change an account');
      } finally { view.dispose(); }
    }],
    ['Disconnect only the chosen GitLab account and retain it after a failure', async () => {
      const view = createFixtureRoot();
      const disconnected: number[] = [];
      let rows = [account(1), account(2)];
      window.bridge = {
        listHostingAccounts: async () => rows,
        disconnectHostingAccount: async id => {
          disconnected.push(id);
          if (disconnected.length === 1) throw new Error('Disconnect failed');
          rows = rows.map(value => value.account.id === id ? { ...value, connected: false } : value);
        },
      } as Partial<Bridge> as Bridge;
      try {
        flushSync(() => view.root.render(<GitLabSettings />));
        await until(() => view.host.textContent?.includes('developer-2') === true, 'Accounts did not load');
        button('Disconnect', accountCard(view.host, 'developer-2')).click();
        await until(() => view.host.querySelector('[role="alert"]')?.textContent === 'Disconnect failed', 'Disconnect failure was not shown');
        assert(accountCard(view.host, 'developer-2').textContent?.includes('Connected'), 'Failed disconnect removed the account');
        button('Disconnect', accountCard(view.host, 'developer-2')).click();
        await until(() => accountCard(view.host, 'developer-2').textContent?.includes('Disconnected') === true, 'Successful disconnect did not update the card');
        assert(disconnected.join(',') === '2,2' && accountCard(view.host, 'developer-1').textContent?.includes('Connected'), 'Disconnect changed the wrong account');
        assert(view.host.querySelector('[role="alert"]') === null, 'Successful retry retained the error');
      } finally { view.dispose(); }
    }],
    ['Check permissions for one saved account and distinguish missing scopes from unavailable inspection', async () => {
      const view = createFixtureRoot();
      const checked: number[] = [];
      let failure = false;
      let permissions: HostingPermissionsDto = { status: 'MISSING', scopes: ['read_user'], detail: 'Reconnect with the api permission to load projects.' };
      window.bridge = {
        listHostingAccounts: async () => [account(1), account(2)],
        checkHostingPermissions: async id => {
          checked.push(id);
          if (failure) throw new Error("Error invoking remote method 'hosting:permissions': Error: Reconnect this account");
          return permissions;
        },
      } as Partial<Bridge> as Bridge;
      try {
        flushSync(() => view.root.render(<GitLabSettings />));
        await until(() => view.host.textContent?.includes('developer-2') === true, 'Accounts did not load');
        assert(checked.length === 0 && accountCard(view.host, 'developer-1').textContent?.includes('Permissions have not been checked.'),
          'Opening settings must not inspect credentials automatically');
        const chosen = () => accountCard(view.host, 'developer-2');
        button('Check permissions', chosen()).click();
        await until(() => chosen().textContent?.includes('Missing permissions') === true, 'Missing permission report did not appear');
        assert(chosen().textContent?.includes('Granted scopes: read_user') && chosen().textContent?.includes(permissions.detail), 'Granted scopes or repair guidance is missing');
        assert(accountCard(view.host, 'developer-1').textContent?.includes('Permissions have not been checked.'), 'Checking changed the wrong account');

        permissions = { status: 'UNAVAILABLE', scopes: [], detail: 'This server cannot inspect the token; permissions remain unverified.' };
        button('Check permissions', chosen()).click();
        await until(() => chosen().textContent?.includes('Permissions unavailable') === true, 'Unavailable inspection was not distinguished');
        assert(chosen().textContent?.includes(permissions.detail) && !chosen().textContent?.includes('read_user'), 'Unavailable inspection retained stale scopes');
        permissions = { status: 'VERIFIED', scopes: ['api'], detail: 'API scope verified; project membership still applies.' };
        button('Check permissions', chosen()).click();
        await until(() => chosen().textContent?.includes('Permissions verified') === true, 'Verified report did not appear');
        assert(chosen().textContent?.includes('Connected') && checked.join(',') === '2,2,2', 'Checks must preserve the connection and target account');
        failure = true;
        button('Check permissions', chosen()).click();
        await until(() => view.host.querySelector('[role="alert"]')?.textContent === 'Reconnect this account', 'Check failure did not show its actionable reason');
        assert(!chosen().textContent?.includes('Permissions verified'), 'A failed recheck must clear its previous verified result');
      } finally { view.dispose(); }
    }],
    ['Starting a rejected reconnect clears only permission reports on that server', async () => {
      for (const surface of ['settings', 'selector']) {
        const view = createFixtureRoot();
        const login = Promise.withResolvers<HostingConnectionDto>();
        let logins = 0;
        const publicServer: HostingServerDto = { provider: 'GITLAB', webBaseUrl: 'https://gitlab.com', apiBaseUrl: 'https://gitlab.com/api/v4' };
        const rows = [account(1), account(2), account(3, publicServer)].map(row => ({
          ...row, permissions: { status: 'VERIFIED' as const, scopes: ['api'], detail: 'API permission verified.' },
        }));
        window.bridge = {
          getGitLabCliStatus: readyCli,
          listHostingAccounts: async () => rows,
          connectHostingCliAccount: async request => { logins++; assert(request.webBaseUrl === SERVER.webBaseUrl, 'Wrong reconnect server'); return login.promise; },
        } as Partial<Bridge> as Bridge;
        const Selector = () => {
          const [selected, setSelected] = useState(1);
          return <GitLabAccounts selected={selected} onSelect={value => setSelected(value.account.id)} />;
        };
        const cleared = () => surface === 'settings'
          ? !accountCard(view.host, 'developer-1').textContent?.includes('Permissions verified') && !accountCard(view.host, 'developer-2').textContent?.includes('Permissions verified')
          : !view.host.textContent?.includes('Permissions verified');
        try {
          flushSync(() => view.root.render(surface === 'settings' ? <GitLabSettings /> : <Selector />));
          await until(() => view.host.textContent?.includes('Permissions verified') === true, 'Verified account report did not load');
          if (surface === 'settings') button('Update…', accountCard(view.host, 'developer-1')).click();
          else button('Add or reconnect a GitLab account', view.host).click();
          await until(() => view.host.textContent?.includes('Sign in with GitLab') === true, 'Reconnect form did not load');
          if (surface === 'selector') changeInput(element<HTMLInputElement>('[aria-label="GitLab server"]', view.host), SERVER.webBaseUrl);
          await settle(); button('Sign in with GitLab', view.host).click();
          await until(() => logins === 1 && cleared(), 'Starting reconnect retained stale verified permissions');
          login.reject(new Error("Error invoking remote method 'hosting:cli-login': Error: Missing api permission"));
          await until(() => view.host.querySelector('[role="alert"]')?.textContent === 'Missing api permission', 'Reconnect rejection did not appear');
          assert(cleared(), 'Rejected reconnect restored stale verified permissions');
          if (surface === 'settings') assert(accountCard(view.host, 'developer-3').textContent?.includes('Permissions verified'), 'Reconnect cleared another server permissions');
          else {
            select(view.host, 2); await settle();
            assert(cleared(), 'Reconnect did not clear the other account on its server');
            select(view.host, 3); await settle();
            assert(view.host.textContent?.includes('Permissions verified'), 'Reconnect cleared another server permissions');
          }
        } finally { view.dispose(); }
      }
    }],
    ['Update and reconnect GitLab accounts on their saved servers', async () => {
      const view = createFixtureRoot();
      const publicServer: HostingServerDto = { provider: 'GITLAB', webBaseUrl: 'https://gitlab.com', apiBaseUrl: 'https://gitlab.com/api/v4' };
      const requests: HostingServerDto[] = [];
      window.bridge = {
        getGitLabCliStatus: readyCli,
        listHostingAccounts: async () => [account(1, publicServer), { ...account(2), connected: false }],
        connectHostingAccount: async request => { requests.push(request); return { ...account(1, request), account: { ...account(1, request).account, login: 'updated-developer' } }; },
        connectHostingCliAccount: async request => { requests.push(request); return account(2, request); },
      } as Partial<Bridge> as Bridge;
      try {
        flushSync(() => view.root.render(<GitLabSettings />));
        await until(() => view.host.textContent?.includes('developer-2') === true, 'Accounts did not load');
        button('Update…', accountCard(view.host, 'developer-1')).click(); await settle();
        const publicField = element<HTMLInputElement>('[aria-label="GitLab server"]', view.host);
        assert(publicField.disabled && publicField.value === publicServer.webBaseUrl, 'Update lost its saved public server');
        changeInput(element<HTMLInputElement>('[aria-label="GitLab personal access token"]', view.host), 'updated-token');
        await settle(); button('Connect GitLab', view.host).click();
        await until(() => view.host.textContent?.includes('updated-developer') === true, 'Updated account was not shown');
        assert(!view.host.textContent?.includes('developer-1'), 'Updating duplicated the previous identity');
        button('Reconnect', accountCard(view.host, 'developer-2')).click();
        await until(() => view.host.textContent?.includes('Sign in with GitLab') === true, 'Reconnect did not open sign-in');
        const privateField = element<HTMLInputElement>('[aria-label="GitLab server"]', view.host);
        assert(privateField.disabled && privateField.value === SERVER.webBaseUrl, 'Reconnect lost its saved private server');
        button('Sign in with GitLab', view.host).click();
        await until(() => accountCard(view.host, 'developer-2').textContent?.includes('Connected') === true, 'Reconnected account did not update');
        assert(requests.length === 2 && requests[0].apiBaseUrl === publicServer.apiBaseUrl && requests[1].apiBaseUrl === SERVER.apiBaseUrl, 'Sign-in used the wrong API server');
      } finally { view.dispose(); }
    }],
    ['Add a GitLab account from empty settings and retain existing accounts when adding another', async () => {
      for (const rows of [[], [account(1)]]) {
        const view = createFixtureRoot();
        let connections = 0;
        window.bridge = {
          getGitLabCliStatus: readyCli,
          listHostingAccounts: async () => rows,
          connectHostingCliAccount: async request => {
            connections++;
            return { ...account(2, request), permissions: { status: 'UNAVAILABLE', scopes: [], detail: 'This server cannot inspect the credential scopes.' } };
          },
        } as Partial<Bridge> as Bridge;
        try {
          flushSync(() => view.root.render(<GitLabSettings />));
          if (rows.length > 0) {
            await until(() => view.host.textContent?.includes('Add GitLab account') === true, 'Add account action did not appear');
            button('Add GitLab account', view.host).click();
          }
          await until(() => view.host.textContent?.includes('Sign in with GitLab') === true, 'New account sign-in did not appear');
          assert(connections === 0, 'Opening settings must not sign in');
          const server = element<HTMLInputElement>('[aria-label="GitLab server"]', view.host);
          assert(!server.disabled && server.value === 'https://gitlab.com', 'New account must allow choosing a server');
          button('Sign in with GitLab', view.host).click();
          await until(() => view.host.textContent?.includes('developer-2') === true, 'Added account did not appear');
          assert(Number(connections) === 1 && accountCard(view.host, 'developer-2').textContent?.includes('Connected'), 'Added account did not become connected');
          assert(accountCard(view.host, 'developer-2').textContent?.includes('Permissions unavailable'), 'Unverified sign-in must retain its permission report');
          if (rows.length > 0) accountCard(view.host, 'developer-1');
        } finally { view.dispose(); }
      }
    }],
    ['Workspace settings switch only between accounts on the same GitLab server', async () => {
      const view = createFixtureRoot();
      const writes: number[] = [];
      let accountId = 1;
      const binding = () => ({ workspaceId: WORKSPACE.id, accountId, selectedRepositoryId: 8, upstreamRepositoryId: null });
      const bridge: Partial<Bridge> = {
        getGitLabCliStatus: readyCli,
        getAppVersion: async () => ({ version: '1.0.0' }), getAppPreferences: async () => preferences,
        getWorkspaceSettings: async () => { throw new Error('No repository settings needed by this account picker'); },
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
