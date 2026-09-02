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
import { useCallback, useEffect, useRef, useState } from 'react';
import AppSidebar, { type AppPage } from './AppSidebar';
import FindBar from './FindBar';
import HomePage from './home/HomePage';
import { pullRequestFromRoute, pullRequestRoute } from './githubReference';
import InAppBrowser from './InAppBrowser';
import LogoLoading from './LogoLoading';
import {
  canGoBack,
  canGoForward,
  INITIAL_NAVIGATION,
  navigationIndex,
  navigationShortcutDirection,
  popNavigation,
  pushNavigation,
  stampNavigationState,
  type NavigationMode,
} from './navigationHistory';
import OnboardingScreen from './OnboardingScreen';
import PullsScreen from './pulls/PullsScreen';
import RecentPage from './recent/RecentPage';
import RecentPalette, { scrollToWrittenText } from './recent/RecentPalette';
import { splitRepo } from './recent/model';
import SettingsShell from './settings/SettingsShell';
import type { RecentRowDto, RecentWordHitDto, WorkspaceDto } from './types';
import WorkspacesPage from './workspaces/WorkspacesPage';
import WorkspaceShell from './workspaces/WorkspaceShell';
import {
  workspaceIssueRoute,
  workspaceAgentTaskRoute,
  workspaceLocationFromRoute,
  workspacePullRequestRoute,
  workspaceRoute,
  type WorkspaceTab,
} from './workspaces/model';

type Status = 'checking' | 'needs-pat' | 'ready';

function pageFromRoute(hash: string): AppPage {
  const match = /^#\/(home|recent|pulls|workspaces|settings)$/.exec(hash);
  return match ? match[1] as AppPage : 'home';
}

/** Splits a Recent item key back into the repository and number it points at. */
export function parseItemKey(itemKey: string): { repo: string; number: number } | null {
  const match = /^[a-z]+:(.+)#(\d+)$/.exec(itemKey);
  return match ? { repo: match[1], number: Number(match[2]) } : null;
}

export default function App() {
  const [status, setStatus] = useState<Status>('checking');
  const [page, setPage] = useState<AppPage>('home');
  const [workspace, setWorkspace] = useState<WorkspaceDto | null>(null);
  const [workspaceTab, setWorkspaceTab] = useState<WorkspaceTab>('home');
  const [initialPr, setInitialPr] = useState<{ repo: string; number: number } | undefined>();
  const [initialWorkspacePrNumber, setInitialWorkspacePrNumber] = useState<number | null>(null);
  const [initialIssueNumber, setInitialIssueNumber] = useState<number | null>(null);
  const [initialAgentTaskId, setInitialAgentTaskId] = useState<string | null>(null);
  const [developmentBuild, setDevelopmentBuild] = useState(false);
  const [browserUrl, setBrowserUrl] = useState<string | null>(null);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [fullScreen, setFullScreen] = useState(false);
  const workspaceRef = useRef(workspace);
  const navigationSession = useRef(crypto.randomUUID());
  const navigationRef = useRef(INITIAL_NAVIGATION);
  const [navigation, setNavigation] = useState(INITIAL_NAVIGATION);

  const updateWorkspace = useCallback((next: WorkspaceDto | null) => {
    workspaceRef.current = next;
    setWorkspace(next);
  }, []);

  const replaceCurrentRoute = useCallback((hash: string) => {
    if (window.location.hash === hash) return;
    window.history.replaceState(
      stampNavigationState(window.history.state, navigationSession.current, navigationRef.current.index, hash),
      '',
      hash,
    );
  }, []);

  const navigateTo = useCallback((hash: string, mode: NavigationMode) => {
    if (mode === 'replace') replaceCurrentRoute(hash);
    else if (window.location.hash !== hash) window.location.hash = hash;
  }, [replaceCurrentRoute]);

  useEffect(() => {
    const hash = window.location.hash || '#/home';
    window.history.replaceState(
      stampNavigationState(window.history.state, navigationSession.current, 0, hash),
      '',
      hash,
    );
  }, []);

  useEffect(() => {
    void window.bridge.hasPat().then(hasPat => setStatus(hasPat ? 'ready' : 'needs-pat'));
    const stopBrowser = window.bridge.onInAppOpenRequest(({ url }) => setBrowserUrl(url));
    const stopFullScreen = window.bridge.onFullScreenChange(setFullScreen);
    void window.bridge.getFullScreen().then(setFullScreen);
    void window.bridge.isDevelopmentBuild().then(setDevelopmentBuild);
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
        updateWorkspace(null);
        setInitialWorkspacePrNumber(null);
        setInitialIssueNumber(null);
        setInitialAgentTaskId(null);
        const pullRequest = pullRequestFromRoute(window.location.hash);
        setInitialPr(pullRequest ?? undefined);
        setPage(pullRequest ? 'pulls' : pageFromRoute(window.location.hash));
        return;
      }
      const current = ++request;
      setPage('workspaces');
      setInitialPr(undefined);
      setWorkspaceTab(workspaceLocation.tab ?? 'home');
      setInitialWorkspacePrNumber(workspaceLocation.pullRequestNumber);
      setInitialIssueNumber(workspaceLocation.issueNumber);
      setInitialAgentTaskId(workspaceLocation.agentTaskId);
      if (workspaceRef.current?.id === workspaceLocation.workspaceId) return;
      updateWorkspace(null);
      void window.bridge.getWorkspace(workspaceLocation.workspaceId)
        .then(details => {
          if (request === current) updateWorkspace(details.summary);
        })
        .catch(() => {
          if (request === current) {
            updateWorkspace(null);
            setWorkspaceTab('home');
            setInitialWorkspacePrNumber(null);
            setInitialIssueNumber(null);
            setInitialAgentTaskId(null);
            replaceCurrentRoute('#/workspaces');
          }
        });
    };
    const applyHistoryRoute = () => {
      const hash = window.location.hash || '#/home';
      const index = navigationIndex(window.history.state, navigationSession.current, hash);
      const next = index === null
        ? pushNavigation(navigationRef.current)
        : popNavigation(navigationRef.current, index);
      if (index === null) {
        window.history.replaceState(
          stampNavigationState(window.history.state, navigationSession.current, next.index, hash),
          '',
          hash,
        );
      }
      navigationRef.current = next;
      setNavigation(next);
      applyRoute();
    };
    applyRoute();
    window.addEventListener('hashchange', applyHistoryRoute);
    return () => {
      request++;
      window.removeEventListener('hashchange', applyHistoryRoute);
    };
  }, [replaceCurrentRoute, status, updateWorkspace]);

  useEffect(() => {
    if (status !== 'ready') return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.metaKey && !event.ctrlKey && !event.altKey && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setPaletteOpen(open => !open);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [status]);

  useEffect(() => {
    if (status !== 'ready' || browserUrl !== null) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      const direction = navigationShortcutDirection(event);
      if (direction === 0) return;
      event.preventDefault();
      if (direction < 0 && canGoBack(navigationRef.current)) window.history.back();
      if (direction > 0 && canGoForward(navigationRef.current)) window.history.forward();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [browserUrl, status]);

  const selectGlobalPullRequest = useCallback((reference: { repo: string; number: number }, mode: NavigationMode) => {
    const slash = reference.repo.indexOf('/');
    if (slash < 1) return;
    navigateTo(pullRequestRoute({
      owner: reference.repo.slice(0, slash),
      repo: reference.repo.slice(slash + 1),
      number: reference.number,
    }), mode);
  }, [navigateTo]);

  const selectWorkspacePullRequest = useCallback((number: number, mode: NavigationMode) => {
    const current = workspaceRef.current;
    if (current) navigateTo(workspacePullRequestRoute(current.id, number), mode);
  }, [navigateTo]);

  // Agent tasks are a development-only preview until the surface is real.
  const selectAgentTask = useCallback((taskId: string | null, mode: NavigationMode) => {
    const current = workspaceRef.current;
    if (!current) return;
    navigateTo(taskId === null
      ? workspaceRoute(current.id, 'tasks')
      : workspaceAgentTaskRoute(current.id, taskId), mode);
  }, [navigateTo]);

  const selectWorkspaceIssue = useCallback((number: number, mode: NavigationMode) => {
    const current = workspaceRef.current;
    if (current) navigateTo(workspaceIssueRoute(current.id, number), mode);
  }, [navigateTo]);

  const openRecentRow = (row: RecentRowDto) => {
    setPaletteOpen(false);
    const reference = parseItemKey(row.itemKey);
    const repo = splitRepo(reference?.repo);
    if (row.type === 'PULL_REQUEST' && reference && repo) {
      window.location.hash = row.workspaceId
        ? workspacePullRequestRoute(row.workspaceId, reference.number)
        : pullRequestRoute({ owner: repo.owner, repo: repo.name, number: reference.number });
      // Land on the thread the user wrote in, not the top of the item.
      scrollToWrittenText(row.textId);
      return;
    }
    if (row.type === 'ISSUE' && reference && row.workspaceId) {
      window.location.hash = workspaceIssueRoute(row.workspaceId, reference.number);
      return;
    }
    if (row.workspaceId) {
      window.location.hash = workspaceRoute(row.workspaceId, row.type === 'COMMIT' ? 'commits' : 'tasks');
    }
  };

  const openRecentWordHit = (hit: RecentWordHitDto) => {
    setPaletteOpen(false);
    const reference = parseItemKey(hit.itemKey);
    const repo = splitRepo(reference?.repo);
    if (!reference || !repo || hit.type !== 'PULL_REQUEST') return;
    window.location.hash = pullRequestRoute({ owner: repo.owner, repo: repo.name, number: reference.number });
    scrollToWrittenText(hit.id);
  };

  if (status === 'checking') return <LogoLoading />;
  if (status === 'needs-pat') {
    return <OnboardingScreen onSaved={() => setStatus('ready')} />;
  }

  const openPr = (owner: string, repo: string, number?: number) => {
    setInitialPr(number === undefined ? undefined : { repo: `${owner}/${repo}`, number });
    updateWorkspace(null);
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
    if (next === workspaceTab) return;
    setWorkspaceTab(next);
    setInitialWorkspacePrNumber(null);
    setInitialIssueNumber(null);
    if (workspace) {
      window.location.hash = workspaceRoute(workspace.id, next);
    }
  };

  return (
    <div className="app-shell">
      <AppSidebar
        page={page}
        canGoBack={canGoBack(navigation)}
        canGoForward={canGoForward(navigation)}
        onBack={() => window.history.back()}
        onForward={() => window.history.forward()}
        onNavigate={next => {
          if (next === page && workspace === null) return;
          setPage(next);
          updateWorkspace(null);
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
        {page === 'recent' && <RecentPage onOpenRow={openRecentRow} />}
        {page === 'pulls' && (
          <PullsScreen
            initialPr={initialPr}
            browserOpen={browserUrl !== null}
            onSelectPullRequest={selectGlobalPullRequest}
          />
        )}
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
            onWorkspaceChanged={updateWorkspace}
            initialPullRequestNumber={initialWorkspacePrNumber}
            initialIssueNumber={initialIssueNumber}
            initialAgentTaskId={initialAgentTaskId}
            developmentBuild={developmentBuild}
            browserOpen={browserUrl !== null}
            onSelectPullRequest={selectWorkspacePullRequest}
            onSelectAgentTask={selectAgentTask}
            onSelectIssue={selectWorkspaceIssue}
            onRemoved={() => { window.location.hash = '#/workspaces'; }}
          />
        )}
        {page === 'settings' && <SettingsShell />}
      </main>
      {paletteOpen && (
        <RecentPalette
          onClose={() => setPaletteOpen(false)}
          onOpenRow={openRecentRow}
          onOpenWordHit={openRecentWordHit}
        />
      )}
      {browserUrl !== null && (
        <InAppBrowser url={browserUrl} fullScreen={fullScreen} onClose={() => setBrowserUrl(null)} />
      )}
      {browserUrl === null && <FindBar />}
    </div>
  );
}
