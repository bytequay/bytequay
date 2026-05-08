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
import { Component, useEffect, useState, type ErrorInfo, type ReactNode } from 'react';
import SettingsShell from './settings/SettingsShell';
import NotificationsScreen from './NotificationsScreen';
import TeamDetailPage from './teams/TeamDetailPage';
import TeamHomePage from './teams/TeamHomePage';
import TeamsManagePage from './teams/TeamsManagePage';
import SlackPage from './slack/SlackPage';
import type { SettingsSection } from './settings/types';
import PullRequestList from './PullRequestList';
import HomePage from './HomePage';
import RepoDetailPage from './RepoDetailPage';
import ReposPage from './repos/ReposPage';
import LocalRepoPage from './repos/LocalRepoPage';
import InAppBrowser from './InAppBrowser';
import LogoLoading from './LogoLoading';
import OnboardingScreen from './OnboardingScreen';
import { applyTheme, loadTheme } from './themes';

type Status = 'checking' | 'needs-pat' | 'ready';
type Nav =
  | { view: 'home' }
  | { view: 'my-prs' }
  | { view: 'repo'; owner: string; repo: string; prNumber?: number }
  | { view: 'teams' }
  | { view: 'team'; teamId: number }
  | { view: 'team-kanban'; teamId: number }
  | { view: 'slack' }
  | { view: 'notifications' }
  | { view: 'repos' }
  | { view: 'local-repo'; owner: string; repo: string; initialBranch?: string }
  | { view: 'settings'; section?: SettingsSection };

type GlobalTopbarProps = {
  nav: Nav;
  onNav: (nav: Nav) => void;
};

function GlobalTopbar({ nav, onNav }: GlobalTopbarProps) {
  return (
    <div className="global-topbar">
      <div className="global-topbar__left">
        <button
          className="global-topbar__brand global-topbar__brand--btn"
          onClick={() => onNav({ view: 'home' })}
          title="Home"
          type="button"
        >
          <span>ByteQuay</span>
        </button>
        {nav.view === 'repo' && (
          <button
            className="global-topbar__breadcrumb"
            onClick={() => onNav({ view: 'home' })}
          >
            ← {nav.owner}/{nav.repo}
          </button>
        )}
        {/* Portal target: child screens (e.g. PullRequestList) mount
            their own context-back button here via createPortal so it
            sits next to the brand/breadcrumb without forcing the page
            state up into App. */}
        <div id="global-topbar-extra" className="global-topbar__extra" />
      </div>
      <nav className="global-topbar__nav">
        <button
          className={`global-nav-btn${nav.view === 'home' ? ' global-nav-btn--active' : ''}`}
          onClick={() => onNav({ view: 'home' })}
        >
          Home
        </button>
        <button
          className={`global-nav-btn${nav.view === 'my-prs' ? ' global-nav-btn--active' : ''}`}
          onClick={() => onNav({ view: 'my-prs' })}
        >
          Pull requests
        </button>
        <button
          className={`global-nav-btn${nav.view === 'repos' ? ' global-nav-btn--active' : ''}`}
          onClick={() => onNav({ view: 'repos' })}
          title="Local repos"
        >
          Repos
        </button>
        <button
          className={`global-nav-btn${nav.view === 'slack' ? ' global-nav-btn--active' : ''}`}
          onClick={() => onNav({ view: 'slack' })}
          title="Slack"
        >
          Slack
        </button>
        <button
          className={`global-nav-btn${nav.view === 'notifications' ? ' global-nav-btn--active' : ''}`}
          onClick={() => onNav({ view: 'notifications' })}
          title="Notifications"
        >
          Notifications
        </button>
        <button
          className={`global-nav-btn${nav.view === 'settings' ? ' global-nav-btn--active' : ''}`}
          onClick={() => onNav({ view: 'settings' })}
        >
          Settings
        </button>
      </nav>
    </div>
  );
}

/**
 * Catches render errors in the routed content so the global topbar
 * stays visible — without this, a thrown render unmounts the entire
 * tree and the user sees nothing but white. Surfaces the message and
 * a "Reset" button that returns the user to Home and re-mounts the
 * route, so they can recover without restarting the app. The fallback
 * resets when navigation changes (resetKey prop).
 */
type ErrorBoundaryProps = {
  resetKey: string;
  onReset: () => void;
  children: ReactNode;
};
type ErrorBoundaryState = { error: Error | null };

class RouteErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('Route render error:', error, info);
  }

  componentDidUpdate(prev: ErrorBoundaryProps): void {
    if (prev.resetKey !== this.props.resetKey && this.state.error) {
      this.setState({ error: null });
    }
  }

  render(): ReactNode {
    if (this.state.error) {
      return (
        <div className="route-error">
          <h2>Something went wrong rendering this page.</h2>
          <pre className="route-error__stack">{this.state.error.message}</pre>
          <button
            type="button"
            className="route-error__btn"
            onClick={() => { this.setState({ error: null }); this.props.onReset(); }}
          >
            Go home
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

function App() {
  const [status, setStatus] = useState<Status>('checking');
  const [nav, setNav] = useState<Nav>({ view: 'home' });
  const [fatal, setFatal] = useState<string | null>(null);
  // URL of the in-app browser overlay, or null when closed. Set by the
  // main process whenever a link is clicked in the React UI; cleared by
  // the × button on the InAppBrowser toolbar.
  const [inAppUrl, setInAppUrl] = useState<string | null>(null);

  useEffect(() => {
    applyTheme(loadTheme());
  }, []);

  useEffect(() => {
    const unsub = window.bridge.onInAppOpenRequest(({ url }) => setInAppUrl(url));
    return unsub;
  }, []);

  useEffect(() => {
    const check = async () => {
      if (!window.bridge) {
        setFatal('window.bridge is undefined — preload script did not load.');
        return;
      }
      try {
        const has = await window.bridge.hasPat();
        setStatus(has ? 'ready' : 'needs-pat');
      } catch (e) {
        setFatal(e instanceof Error ? e.message : String(e));
      }
    };
    void check();
  }, []);

  if (fatal) {
    return (
      <div style={{ padding: '2rem', color: '#b00020' }}>
        <h2>Startup error</h2>
        <pre style={{ whiteSpace: 'pre-wrap' }}>{fatal}</pre>
      </div>
    );
  }

  if (status === 'checking') {
    return (
      <div className="app-loading">
        <LogoLoading size={88} />
      </div>
    );
  }

  // First-run: no PAT yet — branded onboarding screen with the
  // animated logo, welcome copy, and a single PAT field.
  if (status === 'needs-pat') {
    return (
      <OnboardingScreen
        onSaved={() => {
          setNav({ view: 'home' });
          setStatus('ready');
        }}
      />
    );
  }

  // Ready: global app shell with persistent topbar
  return (
    <div className="app-shell">
      <GlobalTopbar nav={nav} onNav={setNav} />
      <div className="app-content">
        <RouteErrorBoundary
          resetKey={JSON.stringify(nav)}
          onReset={() => setNav({ view: 'home' })}
        >
        {nav.view === 'home' && (
          <HomePage
            onSelectRepo={(owner, repo, prNumber) => setNav({ view: 'repo', owner, repo, prNumber })}
            onGoToMyPrs={() => setNav({ view: 'my-prs' })}
            onOpenTeam={(teamId) => setNav({ view: 'team', teamId })}
            onGoToTeams={() => setNav({ view: 'teams' })}
          />
        )}
        {nav.view === 'my-prs' && (
          <PullRequestList
            onGoToTeams={() => setNav({ view: 'teams' })}
          />
        )}
        {nav.view === 'repo' && (
          <RepoDetailPage
            owner={nav.owner}
            repo={nav.repo}
            initialPrNumber={nav.prNumber}
            onOpenLocalBranch={(owner, repo, branch) =>
              setNav({ view: 'local-repo', owner, repo, initialBranch: branch })}
          />
        )}
        {nav.view === 'slack' && (
          <SlackPage
            onOpenIntegrationsSettings={() => setNav({ view: 'settings', section: 'integrations' })}
          />
        )}
        {nav.view === 'notifications' && (
          <NotificationsScreen />
        )}
        {nav.view === 'repos' && (
          <ReposPage
            onSelectRepo={(owner, repo) => setNav({ view: 'local-repo', owner, repo })}
          />
        )}
        {nav.view === 'local-repo' && (
          <LocalRepoPage
            owner={nav.owner}
            repo={nav.repo}
            onBack={() => setNav({ view: 'repos' })}
            onSelectPr={(owner, repo, prNumber) => setNav({ view: 'repo', owner, repo, prNumber })}
            initialBranch={nav.initialBranch}
          />
        )}
        {nav.view === 'teams' && (
          <TeamsManagePage
            onOpenTeam={(teamId) => setNav({ view: 'team', teamId })}
            onBack={() => setNav({ view: 'my-prs' })}
          />
        )}
        {nav.view === 'team' && (
          <TeamHomePage
            teamId={nav.teamId}
            onOpenKanban={() => setNav({ view: 'team-kanban', teamId: nav.teamId })}
            onSelectPr={(owner, repo, prNumber) => setNav({ view: 'repo', owner, repo, prNumber })}
            onBack={() => setNav({ view: 'teams' })}
          />
        )}
        {nav.view === 'team-kanban' && (
          <TeamDetailPage
            teamId={nav.teamId}
            onBack={() => setNav({ view: 'team', teamId: nav.teamId })}
          />
        )}
        {nav.view === 'settings' && (
          <SettingsShell
            section={nav.section ?? 'account'}
            onSelectSection={(section) => setNav({ view: 'settings', section })}
            onOpenTeam={(teamId) => setNav({ view: 'team', teamId })}
            onClearPat={async () => {
              await window.bridge.clearPat();
              setNav({ view: 'home' });
              setStatus('needs-pat');
            }}
          />
        )}
        </RouteErrorBoundary>
      </div>
      {inAppUrl && (
        <InAppBrowser url={inAppUrl} onClose={() => setInAppUrl(null)} />
      )}
    </div>
  );
}

export default App;
