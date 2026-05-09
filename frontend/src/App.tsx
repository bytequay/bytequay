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
import RepositoryPage from './repos/RepositoryPage';
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
  | { view: 'repository'; owner: string; repo: string }
  | { view: 'local-repo'; owner: string; repo: string; initialBranch?: string }
  | { view: 'settings'; section?: SettingsSection };

type GlobalTopbarProps = {
  nav: Nav;
  onNav: (nav: Nav) => void;
  /** True while the main window is in macOS native fullscreen — the
   *  inset traffic lights vanish in that state, so we draw a small
   *  brand mark in the otherwise-empty 78px reserve. */
  fullScreen: boolean;
};

/**
 * Static, animation-free version of the LogoOnboarding mark, sized
 * for the topbar. Lives here rather than in its own component because
 * the topbar is the only consumer and the SVG is small enough that a
 * separate file would be more friction than reuse.
 */
function TopbarBrandMark({ size = 30 }: { size?: number }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 100 100"
      width={size}
      height={size}
      role="img"
      aria-label="ByteQuay"
    >
      <rect x="10" y="10" width="80" height="80" rx="14" fill="#7C3AED" />
      <path d="M 10 68 Q 28 60 42 68 T 72 68 L 90 68 L 90 90 L 10 90 Z" fill="#8B5CF6" />
      <line x1="30" y1="26" x2="42" y2="54" stroke="#FFFFFF" strokeWidth="4" strokeLinecap="round" />
      <line x1="44" y1="26" x2="56" y2="54" stroke="#FFFFFF" strokeWidth="4" strokeLinecap="round" opacity="0.7" />
      <line x1="58" y1="26" x2="70" y2="54" stroke="#FFFFFF" strokeWidth="4" strokeLinecap="round" />
    </svg>
  );
}

const BYTEQUAY_REPO_URL = 'https://github.com/chenjian2664/bytequay';

/**
 * Canonical GitHub octocat mark. Inlined as currentColor so it picks
 * up the wordmark slot's text color and adapts to theme tokens.
 */
function GithubMark({ size = 18 }: { size?: number }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 16 16"
      width={size}
      height={size}
      role="img"
      aria-label="ByteQuay on GitHub"
      fill="currentColor"
    >
      <path
        fillRule="evenodd"
        clipRule="evenodd"
        d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.66 7.66 0 014 0c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"
      />
    </svg>
  );
}

function GlobalTopbar({ nav, onNav, fullScreen }: GlobalTopbarProps) {
  return (
    <div className={`global-topbar${fullScreen ? ' global-topbar--fullscreen' : ''}`}>
      {fullScreen && (
        <button
          className="global-topbar__mark"
          onClick={() => onNav({ view: 'home' })}
          title="Home"
          type="button"
        >
          <TopbarBrandMark />
        </button>
      )}
      <div className="global-topbar__left">
        {/* Plain href (no target="_blank") so main.ts's will-navigate
            handler intercepts the click and opens the in-app browser
            overlay — keeps the user inside the app shell with a × to
            close. The wordmark used to route to Home; the right-side
            "Home" nav button still does that, and in fullscreen the
            traffic-light brand mark also routes home. */}
        <a
          className="global-topbar__brand global-topbar__brand--btn"
          href={BYTEQUAY_REPO_URL}
          title="ByteQuay on GitHub"
        >
          <GithubMark />
        </a>
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
  const [fullScreen, setFullScreen] = useState<boolean>(false);

  useEffect(() => {
    applyTheme(loadTheme());
  }, []);

  useEffect(() => {
    const unsub = window.bridge.onInAppOpenRequest(({ url }) => setInAppUrl(url));
    return unsub;
  }, []);

  useEffect(() => {
    const unsub = window.bridge.onFullScreenChange(({ isFullScreen }) => setFullScreen(isFullScreen));
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
      <GlobalTopbar nav={nav} onNav={setNav} fullScreen={fullScreen} />
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
            onSelectRepo={(owner, repo) => setNav({ view: 'repository', owner, repo })}
          />
        )}
        {nav.view === 'repository' && (
          <RepositoryPage
            owner={nav.owner}
            repo={nav.repo}
            onBack={() => setNav({ view: 'repos' })}
            onOpenPrs={(owner, repo) => setNav({ view: 'repo', owner, repo })}
            onOpenIssues={(owner, repo) => setNav({ view: 'repo', owner, repo })}
            onOpenBranches={(owner, repo) => setNav({ view: 'local-repo', owner, repo })}
            onOpenCommits={(owner, repo) => setNav({ view: 'local-repo', owner, repo })}
            onSelectPr={(owner, repo, prNumber) => setNav({ view: 'repo', owner, repo, prNumber })}
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
