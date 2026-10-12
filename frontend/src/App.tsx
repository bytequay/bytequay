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
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import AppSidebar, { type AppPage } from './AppSidebar';
import BrowserRail from './BrowserRail';
import FindBar from './FindBar';
import GoogleScreen from './google/GoogleScreen';
import { useGoogle } from './google/useGoogle';
import JiraScreen, { JiraTicketLayer } from './jira/JiraScreen';
import { JIRA_CHANGED, anyJiraConnected, type JiraConnection } from './jira/contracts';
import HomePage from './home/HomePage';
import TeamsPage from './teams/TeamsPage';
import { loadHandled } from './home/attention';
import { loadPinned } from './home/pinned';
import { pullRequestFromRoute, pullRequestRoute, zoomReference, ZOOM_REFERENCE, type ZoomedReference } from './githubReference';
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
import { recentItemTarget } from './recent/routes';
import SettingsWindow from './settings/SettingsWindow';
import {
  isWorkspaceSection,
  readLastSection,
  rememberSection,
  settingsLocationFromRoute,
  settingsRoute,
  type SettingsSection,
} from './settings/routes';
import type { InAppBrowserState, RecentRowDto, RecentWordHitDto, WorkspaceDto } from './types';
import WorkspaceIssuesScreen from './workspaces/WorkspaceIssuesScreen';
import WorkspacesPage from './workspaces/WorkspacesPage';
import WorkspaceShell from './workspaces/WorkspaceShell';
import WorkspaceItemLayer from './workspaces/WorkspaceItemLayer';
import { LayerCovered } from './Zoom';
import {
  workspaceIssueRoute,
  workspaceIssueTaskRoute,
  workspaceAgentTaskRoute,
  workspaceLocationFromRoute,
  workspacePullRequestRoute,
  workspaceRoute,
  type WorkspaceTab,
} from './workspaces/model';

type Status = 'checking' | 'needs-pat' | 'ready';

function layerKey(layer: ZoomedReference): string {
  if (layer.kind === 'work') return `work:${layer.workspaceId}:${layer.itemId}`;
  if (layer.kind === 'task') return `task:${layer.workspaceId}:${layer.taskId}`;
  return layer.kind === 'jira'
    ? `jira:${layer.connectionId}/${layer.cloudId}/${layer.issueId}`
    : `${layer.kind}:${layer.workspaceId ?? ''}:${layer.repo}#${layer.number}`;
}

function pageFromRoute(hash: string): AppPage {
  if (hash === '#/jira' || hash.startsWith('#/jira?')) return 'jira';
  if (settingsLocationFromRoute(hash) !== null) return 'settings';
  const match = /^#\/(home|recent|pulls|workspaces|teams|google)$/.exec(hash);
  return match ? match[1] as AppPage : 'home';
}

export default function App() {
  const [status, setStatus] = useState<Status>('checking');
  const [page, setPage] = useState<AppPage>('home');
  const [settingsSection, setSettingsSection] = useState<SettingsSection | null>(null);
  const [settingsWorkspaceId, setSettingsWorkspaceId] = useState<string | null>(null);
  const [settingsWorkspaces, setSettingsWorkspaces] = useState<WorkspaceDto[]>([]);
  const [workspace, setWorkspace] = useState<WorkspaceDto | null>(null);
  const [workspaceTab, setWorkspaceTab] = useState<WorkspaceTab>('home');
  const [relatedWorkspaceId, setRelatedWorkspaceId] = useState<string | null>(null);
  const [jiraConnected, setJiraConnected] = useState(false);
  const { status: googleStatus } = useGoogle();
  const [initialPr, setInitialPr] = useState<{ repo: string; number: number } | undefined>();
  const [initialWorkspacePrNumber, setInitialWorkspacePrNumber] = useState<number | null>(null);
  const [initialIssueNumber, setInitialIssueNumber] = useState<number | null>(null);
  const [sourceIssueNumber, setSourceIssueNumber] = useState<number | null>(null);
  const [initialAgentTaskId, setInitialAgentTaskId] = useState<string | null>(null);
  const [initialCherryPickId, setInitialCherryPickId] = useState<string | null>(null);
  const [initialAgentReviewId, setInitialAgentReviewId] = useState<string | null>(null);
  const [browser, setBrowser] = useState<InAppBrowserState>({ tabs: [], activeTabId: null });
  const [browserError, setBrowserError] = useState<string | null>(null);
  const [railVisible, setRailVisible] = useState(() => {
    try { return localStorage.getItem('bytequay.browser-rail-visible') !== 'false'; }
    catch { return true; }
  });
  const [railExpanded, setRailExpanded] = useState(() => {
    try { return localStorage.getItem('bytequay.browser-rail-expanded') === 'true'; }
    catch { return false; }
  });
  const browserTab = browser.tabs.find(tab => tab.id === browser.activeTabId) ?? null;
  const browserOpen = browserTab !== null;
  const browserAction = useCallback((action: Promise<void>) => {
    setBrowserError(null);
    void action.catch(cause => setBrowserError(cause instanceof Error ? cause.message : 'Unable to update web pages.'));
  }, []);
  const backToApp = useCallback(() => {
    browserAction(window.bridge.hideInAppBrowser());
  }, [browserAction]);
  /** Issues, pull requests and Jira tickets shown over the page, the one opened last on top. */
  const [layers, setLayers] = useState<ZoomedReference[]>([]);
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
    let receivedState = false;
    const stopBrowser = window.bridge.onInAppBrowserState(next => {
      receivedState = true;
      setBrowser(next);
      if (next.activeTabId !== null) {
        setPaletteOpen(false);
      }
    });
    void window.bridge.getInAppBrowserState().then(next => {
      if (!receivedState) setBrowser(next);
    }).catch(cause => setBrowserError(cause instanceof Error ? cause.message : 'Unable to load web pages.'));
    const stopFullScreen = window.bridge.onFullScreenChange(setFullScreen);
    void window.bridge.getFullScreen().then(setFullScreen);
    return () => {
      receivedState = true;
      stopBrowser();
      stopFullScreen();
    };
  }, []);

  useEffect(() => {
    void window.bridge.setPrAttentionHandled(loadHandled()).catch(() => {});
    void window.bridge.setPrAttentionPinned?.(loadPinned().map(item => item.key)).catch(() => {});
    const stopAttention = window.bridge.onPrAttentionUpdated(prs => {
      window.dispatchEvent(new CustomEvent('bytequay:pr-attention-updated', { detail: prs }));
    });
    const stopNotification = window.bridge.onPrNotificationOpen(reference => {
      if (reference.repo.indexOf('/') < 1) return;
      zoomReference({ kind: 'pull', repo: reference.repo, number: reference.number });
    });
    return () => { stopAttention(); stopNotification(); };
  }, []);

  useEffect(() => window.bridge.onAppNoticeOpen?.(target => {
    zoomReference({ kind: 'task', taskId: target.taskId, workspaceId: target.workspaceId });
  }), []);

  useEffect(() => {
    if (status !== 'ready') return;
    let request = 0;
    const reload = () => {
      const current = ++request;
      void window.bridge.listJiraConnections().then(connections => {
        if (current === request) setJiraConnected(anyJiraConnected(connections));
      }).catch(() => {});
    };
    reload();
    window.addEventListener(JIRA_CHANGED, reload);
    window.addEventListener('focus', reload);
    return () => {
      request++;
      window.removeEventListener(JIRA_CHANGED, reload);
      window.removeEventListener('focus', reload);
    };
  }, [status]);

  useEffect(() => {
    const open = (event: Event) => {
      backToApp();
      setPaletteOpen(false);
      const target = (event as CustomEvent<ZoomedReference>).detail;
      // A reference that is already open comes back to the top, as it was, instead of opening twice; a start, such as a Goal card's,
      // still opens its composer.
      setLayers(current => [
        ...current.filter(layer => layerKey(layer) !== layerKey(target)),
        (target.kind === 'jira' && target.start ? undefined : current.find(layer => layerKey(layer) === layerKey(target))) ?? target,
      ]);
    };
    window.addEventListener(ZOOM_REFERENCE, open);
    return () => window.removeEventListener(ZOOM_REFERENCE, open);
  }, [backToApp]);

  useEffect(() => {
    if (status !== 'ready') return;
    let request = 0;
    const applyRoute = () => {
      const settingsLocation = settingsLocationFromRoute(window.location.hash);
      setSettingsSection(settingsLocation?.section ?? null);
      setSettingsWorkspaceId(settingsLocation?.workspaceId ?? null);
      const workspaceLocation = workspaceLocationFromRoute(window.location.hash);
      setRelatedWorkspaceId(workspaceLocation?.relatedWorkspaceId ?? null);
      if (!workspaceLocation) {
        request++;
        updateWorkspace(null);
        setInitialWorkspacePrNumber(null);
        setInitialIssueNumber(null);
        setSourceIssueNumber(null);
        setInitialAgentTaskId(null);
        setInitialAgentReviewId(null);
        setInitialCherryPickId(null);
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
      setSourceIssueNumber(workspaceLocation.sourceIssueNumber ?? null);
      setInitialAgentTaskId(workspaceLocation.agentTaskId);
      setInitialAgentReviewId(workspaceLocation.reviewId ?? null);
      setInitialCherryPickId(workspaceLocation.cherryPickId ?? null);
      if (workspaceRef.current?.id === workspaceLocation.workspaceId) return;
      updateWorkspace(null);
      void window.bridge.getWorkspace(workspaceLocation.workspaceId)
        .then(summary => {
          if (request === current) updateWorkspace(summary);
        })
        .catch(() => {
          if (request === current) {
            updateWorkspace(null);
            setWorkspaceTab('home');
            setInitialWorkspacePrNumber(null);
            setInitialIssueNumber(null);
            setSourceIssueNumber(null);
            setInitialAgentTaskId(null);
            setInitialAgentReviewId(null);
            setInitialCherryPickId(null);
            replaceCurrentRoute('#/workspaces');
          }
        });
    };
    const applyHistoryRoute = () => {
      // Navigating leaves every open layer; the first route of the session must not, or it
      // races the pull request a notification asked to show while the app was still starting.
      setLayers([]);
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
    if (status !== 'ready' || browserOpen) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.metaKey && !event.ctrlKey && !event.altKey && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setPaletteOpen(open => !open);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [status, browserOpen]);

  useEffect(() => {
    if (status !== 'ready' || browserOpen) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      const direction = navigationShortcutDirection(event);
      // Inside a code editor or terminal these keys belong to it (indent, outdent).
      if (direction === 0 || (event.target instanceof Element && event.target.closest('.cm-editor, .xterm') !== null)) return;
      event.preventDefault();
      if (direction < 0 && canGoBack(navigationRef.current)) window.history.back();
      if (direction > 0 && canGoForward(navigationRef.current)) window.history.forward();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [browserOpen, status]);

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

  const closeLayer = (key: string) => setLayers(current => current.filter(layer => layerKey(layer) !== key));

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

  const openRecentItem = async (item: Pick<RecentRowDto, 'type' | 'itemKey' | 'workspaceId' | 'htmlUrl'>, textId: string | null) => {
    setPaletteOpen(false);
    const connections = item.type === 'JIRA' ? await window.bridge.listJiraConnections().catch((): JiraConnection[] => []) : [];
    const target = recentItemTarget(item, connections);
    if (target === null) {
      if (item.type === 'JIRA' && item.htmlUrl) await window.bridge.openInAppBrowser(item.htmlUrl);
      return;
    }
    if (typeof target === 'string') window.location.hash = target;
    else zoomReference(target);
    if (item.type === 'PULL_REQUEST') scrollToWrittenText(textId);
  };
  const openRecentRow = (row: RecentRowDto) => { void openRecentItem(row, row.textId); };
  const openRecentWordHit = (hit: RecentWordHitDto) => { void openRecentItem(hit, hit.id); };

  // A bare `#/settings` reopens whichever page was last open.
  const settingsPage = page === 'settings' ? settingsSection ?? readLastSection() : null;
  const settingsWorkspace = settingsWorkspaces.find(candidate => candidate.id === settingsWorkspaceId)
    ?? settingsWorkspaces[0]
    ?? null;

  useEffect(() => {
    if (settingsPage === null) return;
    rememberSection(settingsPage);
  }, [settingsPage]);

  const loadSettingsWorkspaces = useCallback(() => {
    void window.bridge.listWorkspaces().then(setSettingsWorkspaces).catch(() => {});
  }, []);

  useEffect(() => {
    if (page !== 'settings') return;
    loadSettingsWorkspaces();
  }, [loadSettingsWorkspaces, page]);

  const goToSettings = useCallback((section: SettingsSection, workspaceId?: string | null) => {
    window.location.hash = settingsRoute(
      section,
      isWorkspaceSection(section) ? (workspaceId ?? settingsWorkspaceId ?? settingsWorkspace?.id ?? null) : null,
    );
  }, [settingsWorkspace, settingsWorkspaceId]);

  if (status !== 'ready') {
    return (
      <div className="app-shell">
        <main className="app-content">
          {status === 'checking' ? <LogoLoading /> : <OnboardingScreen onSaved={() => setStatus('ready')} />}
        </main>
      </div>
    );
  }

  const openPr = (owner: string, repo: string, number?: number) => {
    if (number !== undefined) {
      zoomReference({ kind: 'pull', repo: `${owner}/${repo}`, number });
      return;
    }
    setInitialPr(undefined);
    updateWorkspace(null);
    setPage('pulls');
    window.location.hash = '#/pulls';
  };

  const openWorkspace = (workspaceId: string) => {
    window.location.hash = workspaceRoute(workspaceId);
  };

  const inWorkspace = page === 'workspaces' && workspace !== null;
  // Only the tasks tab swaps its whole page for a route-owned task, so clicking that tab with
  // one open returns to the task list instead of doing nothing.
  const taskPageOpen = workspaceTab === 'tasks'
    && (initialAgentTaskId !== null || initialAgentReviewId !== null || sourceIssueNumber !== null);
  const selectWorkspaceTab = (next: WorkspaceTab) => {
    if (next === workspaceTab && !taskPageOpen && relatedWorkspaceId === null) return;
    setWorkspaceTab(next);
    setInitialWorkspacePrNumber(null);
    setInitialIssueNumber(null);
    setSourceIssueNumber(null);
    if (workspace) {
      window.location.hash = workspaceRoute(workspace.id, next);
    }
  };

  return (
    <div className={`browser-workspace${railVisible ? '' : ' is-web-rail-hidden'}`} style={{ '--browser-rail-width': railVisible ? (railExpanded ? '220px' : '40px') : '0px' } as CSSProperties}>
      <BrowserRail state={browser} expanded={railExpanded} error={browserError}
        onToggle={() => setRailExpanded(value => {
          try { localStorage.setItem('bytequay.browser-rail-expanded', String(!value)); } catch { /* preference is optional */ }
          return !value;
        })}
        onApp={backToApp}
        onNewPage={() => browserAction(window.bridge.openInAppBrowser('https://www.google.com/'))}
        onSelect={id => browserAction(window.bridge.selectInAppBrowserTab(id))}
        onClose={id => browserAction(window.bridge.closeInAppBrowserTab(id))}
        onRename={(id, name) => browserAction(window.bridge.renameInAppBrowserTab(id, name))} />
      <div className="app-surfaces" inert={browserOpen}>
    <LayerCovered.Provider value={browserOpen || layers.length > 0}>
    <div className="app-shell" inert={layers.length > 0}>
        <AppSidebar
          page={page}
          jiraConnected={jiraConnected}
          googleConnected={Boolean(googleStatus?.snapshot)}
          browserRailVisible={railVisible}
          onToggleBrowserRail={() => setRailVisible(value => {
            try { localStorage.setItem('bytequay.browser-rail-visible', String(!value)); } catch { /* preference is optional */ }
            return !value;
          })}
          canGoBack={canGoBack(navigation)}
          canGoForward={canGoForward(navigation)}
          onBack={() => window.history.back()}
          onForward={() => window.history.forward()}
          onNavigate={next => {
            // Settings from inside a workspace opens that workspace's own pages, which is
            // what the tab it replaced used to do.
            if (next === 'settings' && workspace !== null) {
              setSettingsWorkspaceId(workspace.id);
              updateWorkspace(null);
              window.location.hash = settingsRoute('ws-repository', workspace.id);
              return;
            }
            if (next === page && workspace === null) return;
            setPage(next);
            updateWorkspace(null);
            window.location.hash = `#/${next}`;
          }}
          settings={settingsPage === null ? undefined : {
            section: settingsPage,
            workspaces: settingsWorkspaces,
            workspaceId: settingsWorkspace?.id ?? null,
            onSection: goToSettings,
            onWorkspace: id => { setSettingsWorkspaceId(id); goToSettings(settingsPage, id); },
            onExit: () => { window.location.hash = '#/home'; },
          }}
          workspace={inWorkspace ? workspace : null}
          workspaceTab={workspaceTab}
          relatedWorkspaceId={relatedWorkspaceId}
          onWorkspaceTab={selectWorkspaceTab}
        />
        <main className="app-content">
          {page === 'home' && (
            <HomePage
              onSelectRepo={openPr}
              onOpenWorkspace={openWorkspace}
              onStartIssue={(workspaceId, issueNumber) => navigateTo(workspaceIssueTaskRoute(workspaceId, issueNumber), 'push')}
            />
          )}
          <RecentPage active={page === 'recent'} onOpenRow={openRecentRow} />
          {page === 'jira' && <JiraScreen />}
          {page === 'google' && <GoogleScreen />}
          {page === 'teams' && <TeamsPage />}
          {page === 'pulls' && (
            <PullsScreen
              initialPr={initialPr}
              browserOpen={browserOpen}
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
              relatedWorkspaceId={relatedWorkspaceId}
              onSelectTab={selectWorkspaceTab}
              onWorkspaceChanged={updateWorkspace}
              initialPullRequestNumber={initialWorkspacePrNumber}
              initialIssueNumber={initialIssueNumber}
              sourceIssueNumber={sourceIssueNumber}
              initialAgentTaskId={initialAgentTaskId}
              initialAgentReviewId={initialAgentReviewId}
              initialCherryPickId={initialCherryPickId}
              browserOpen={browserOpen}
              onSelectPullRequest={selectWorkspacePullRequest}
              onSelectAgentTask={selectAgentTask}
              onSelectIssue={selectWorkspaceIssue}
            />
          )}
          {settingsPage !== null && (
          <SettingsWindow
            section={settingsPage}
            workspace={settingsWorkspace}
            onWorkspacesChanged={() => { setSettingsWorkspaceId(null); loadSettingsWorkspaces(); }}
          />
        )}
      </main>
    </div>
    </LayerCovered.Provider>
      {layers.map((layer, index) => {
        const key = layerKey(layer);
        const covered = index < layers.length - 1;
        return (
          <LayerCovered.Provider key={key} value={browserOpen || covered}>
            <div style={{ display: 'contents' }} inert={covered}>
              {layer.kind === 'pull' && (
                <PullsScreen
                  initialPr={layer}
                  repositoryScope={layer.workspaceId === undefined
                    ? undefined
                    : { workspaceId: layer.workspaceId, repository: layer.repo }}
                  browserOpen={browserOpen}
                  onClose={() => closeLayer(key)}
                />
              )}
              {layer.kind === 'issue' && (
                <WorkspaceIssuesScreen
                  workspaceId={layer.workspaceId}
                  repository={layer.repo}
                  initialIssueNumber={layer.number}
                  onClose={() => closeLayer(key)}
                />
              )}
              {(layer.kind === 'work' || layer.kind === 'task') && <WorkspaceItemLayer target={layer} onClose={() => closeLayer(key)} />}
              {layer.kind === 'jira' && <JiraTicketLayer location={layer} start={layer.start} draft={layer.draft} replaces={layer.replaces} send={layer.send} onClose={() => closeLayer(key)} />}
            </div>
          </LayerCovered.Provider>
        );
      })}
      {paletteOpen && (
        <RecentPalette
          onClose={() => setPaletteOpen(false)}
          onOpenRow={openRecentRow}
          onOpenWordHit={openRecentWordHit}
        />
      )}
      {!browserOpen && <FindBar />}
      </div>
      {browserTab && <InAppBrowser tab={browserTab} fullScreen={fullScreen} railExpanded={railExpanded}
        error={browserError} onError={setBrowserError} onBack={backToApp} />}
    </div>
  );
}
