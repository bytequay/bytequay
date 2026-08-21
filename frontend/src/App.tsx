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
import InAppBrowser from './InAppBrowser';
import LogoLoading from './LogoLoading';
import OnboardingScreen from './OnboardingScreen';
import PullsScreen from './pulls/PullsScreen';
import SettingsShell from './settings/SettingsShell';
import WorkspacesPage from './workspaces/WorkspacesPage';
import WorkspaceShell, { type WorkspaceTab } from './workspaces/WorkspaceShell';
import { workspaceFromDto, type Workspace } from './workspaces/model';

type Status = 'checking' | 'needs-pat' | 'ready';

export default function App() {
  const [status, setStatus] = useState<Status>('checking');
  const [page, setPage] = useState<AppPage>('home');
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [workspaceTab, setWorkspaceTab] = useState<WorkspaceTab>('home');
  const [initialPr, setInitialPr] = useState<{ repo: string; number: number } | undefined>();
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

  if (status === 'checking') return <LogoLoading />;
  if (status === 'needs-pat') {
    return <OnboardingScreen onSaved={() => setStatus('ready')} />;
  }

  const openPr = (owner: string, repo: string, number?: number) => {
    setInitialPr(number === undefined ? undefined : { repo: `${owner}/${repo}`, number });
    setWorkspace(null);
    setPage('pulls');
  };

  const inWorkspace = page === 'workspaces' && workspace !== null;

  return (
    <div className="app-shell">
      <AppSidebar
        page={page}
        onNavigate={next => {
          setPage(next);
          setWorkspace(null);
        }}
        workspace={inWorkspace ? workspace : null}
        workspaceTab={workspaceTab}
        onWorkspaceTab={setWorkspaceTab}
        onExitWorkspace={() => setWorkspace(null)}
      />
      <main className="app-content">
        {page === 'home' && (
          <HomePage
            onSelectRepo={openPr}
            onGoToMyPrs={() => {
              setInitialPr(undefined);
              setPage('pulls');
            }}
            onOpenWorkspace={workspaceId => {
              void window.bridge.getWorkspace(workspaceId).then(details => {
                setWorkspace(workspaceFromDto(details.summary));
                setWorkspaceTab('home');
                setPage('workspaces');
              });
            }}
          />
        )}
        {page === 'pulls' && <PullsScreen initialPr={initialPr} />}
        {page === 'workspaces' && workspace === null && (
          <WorkspacesPage
            onOpen={next => {
              setWorkspace(next);
              setWorkspaceTab('home');
            }}
          />
        )}
        {inWorkspace && workspace !== null && (
          <WorkspaceShell
            key={workspace.id}
            workspace={workspace}
            tab={workspaceTab}
            onSelectTab={setWorkspaceTab}
            onWorkspaceChanged={setWorkspace}
            onRemoved={() => setWorkspace(null)}
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
