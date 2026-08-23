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
import AppSidebar, { type AppPage } from './AppSidebar';
import HomePage from './home/HomePage';
import { pullRequestFromRoute, pullRequestRoute } from './githubReference';
import InAppBrowser from './InAppBrowser';
import LogoLoading from './LogoLoading';
import OnboardingScreen from './OnboardingScreen';
import PullsScreen from './pulls/PullsScreen';
import SettingsShell from './settings/SettingsShell';
import WorkspacesPage from './workspaces/WorkspacesPage';
import WorkspaceShell, { type WorkspaceTab } from './workspaces/WorkspaceShell';
import {
  workspaceFromDto,
  workspaceLocationFromRoute,
  workspaceRoute,
  type Workspace,
} from './workspaces/model';

type Status = 'checking' | 'needs-pat' | 'ready';

function pageFromRoute(hash: string): AppPage {
  const match = /^#\/(home|pulls|workspaces|settings)$/.exec(hash);
  return match ? match[1] as AppPage : 'home';
}

export default function App() {
  const [status, setStatus] = useState<Status>('checking');
  const [page, setPage] = useState<AppPage>('home');
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [workspaceTab, setWorkspaceTab] = useState<WorkspaceTab>('home');
  const [initialPr, setInitialPr] = useState<{ repo: string; number: number } | undefined>();
  const [initialIssueNumber, setInitialIssueNumber] = useState<number | null>(null);
  const [browserUrl, setBrowserUrl] = useState<string | null>(null);
  const [fullScreen, setFullScreen] = useState(false);

  useEffect(() => {
    void window.bridge.hasPat().then(hasPat => setStatus(hasPat ? 'ready' : 'needs-pat'));
    const stopBrowser = window.bridge.onInAppOpenRequest(({ url }) => setBrowserUrl(url));
    const stopFullScreen = window.bridge.onFullScreenChange(setFullScreen);
    void window.bridge.getFullScreen().then(setFullScreen);
    return () => {
      stopBrowser();
      stopFullScreen();
    };
  }, []);

  useEffect(() => {
    if (status !== 'ready') return;
    let request = 0;
    const applyRoute = () => {
      const workspaceLocation = workspaceLocationFromRoute(window.location.hash);
      if (!workspaceLocation) {
        request++;
        setWorkspace(null);
        setInitialIssueNumber(null);
        const pullRequest = pullRequestFromRoute(window.location.hash);
        setInitialPr(pullRequest ?? undefined);
        setPage(pullRequest ? 'pulls' : pageFromRoute(window.location.hash));
        return;
      }
      const current = ++request;
      setPage('workspaces');
      setWorkspace(null);
      setWorkspaceTab(workspaceLocation.tab ?? 'home');
      setInitialIssueNumber(workspaceLocation.issueNumber);
      void window.bridge.getWorkspace(workspaceLocation.workspaceId)
        .then(details => {
          if (request === current) setWorkspace(workspaceFromDto(details.summary));
        })
        .catch(() => {
          if (request === current) window.location.hash = '#/workspaces';
        });
    };
    applyRoute();
    window.addEventListener('hashchange', applyRoute);
    return () => {
      request++;
      window.removeEventListener('hashchange', applyRoute);
    };
  }, [status]);

  if (status === 'checking') return <LogoLoading />;
  if (status === 'needs-pat') {
    return <OnboardingScreen onSaved={() => setStatus('ready')} />;
  }

  const openPr = (owner: string, repo: string, number?: number) => {
    setInitialPr(number === undefined ? undefined : { repo: `${owner}/${repo}`, number });
    setWorkspace(null);
    setPage('pulls');
    window.location.hash = number === undefined
      ? '#/pulls'
      : pullRequestRoute({ owner, repo, number });
  };

  const openWorkspace = (workspaceId: string) => {
    window.location.hash = workspaceRoute(workspaceId);
  };

  const inWorkspace = page === 'workspaces' && workspace !== null;
  const selectWorkspaceTab = (next: WorkspaceTab) => {
    setWorkspaceTab(next);
    setInitialIssueNumber(null);
    if (workspace) {
      window.history.replaceState(null, '', next === 'issues'
        ? `${workspaceRoute(workspace.id)}/issues`
        : workspaceRoute(workspace.id));
    }
  };

  return (
    <div className="app-shell">
      <AppSidebar
        page={page}
        onNavigate={next => {
          setPage(next);
          setWorkspace(null);
          window.location.hash = `#/${next}`;
        }}
        workspace={inWorkspace ? workspace : null}
        workspaceTab={workspaceTab}
        onWorkspaceTab={selectWorkspaceTab}
      />
      <main className="app-content">
        {page === 'home' && (
          <HomePage
            onSelectRepo={openPr}
            onGoToMyPrs={() => {
              setInitialPr(undefined);
              setPage('pulls');
              window.location.hash = '#/pulls';
            }}
            onOpenWorkspace={openWorkspace}
          />
        )}
        {page === 'pulls' && <PullsScreen initialPr={initialPr} />}
        {page === 'workspaces' && workspace === null && (
          <WorkspacesPage
            onOpen={openWorkspace}
          />
        )}
        {inWorkspace && workspace !== null && (
          <WorkspaceShell
            key={workspace.id}
            workspace={workspace}
            tab={workspaceTab}
            onSelectTab={selectWorkspaceTab}
            onWorkspaceChanged={setWorkspace}
            initialIssueNumber={initialIssueNumber}
            onRemoved={() => { window.location.hash = '#/workspaces'; }}
          />
        )}
        {page === 'settings' && <SettingsShell />}
      </main>
      {browserUrl !== null && (
        <InAppBrowser url={browserUrl} fullScreen={fullScreen} onClose={() => setBrowserUrl(null)} />
      )}
    </div>
  );
}
