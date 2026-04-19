import { Component, useEffect, useState, type ErrorInfo, type ReactNode } from 'react';
import SettingsScreen from './SettingsScreen';
import SettingsShell from './settings/SettingsShell';
import NotificationsScreen from './NotificationsScreen';
import TeamDetailPage from './teams/TeamDetailPage';
import type { SettingsSection } from './settings/types';
import PullRequestList from './PullRequestList';
import HomePage from './HomePage';
import RepoDetailPage from './RepoDetailPage';
import InAppBrowser from './InAppBrowser';
import { applyTheme, loadTheme } from './themes';

type Status = 'checking' | 'needs-pat' | 'ready';
type Nav =
  | { view: 'home' }
  | { view: 'my-prs' }
  | { view: 'repo'; owner: string; repo: string; prNumber?: number }
  | { view: 'team'; teamId: number }
  | { view: 'notifications' }
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
    return <div className="app-loading">Loading…</div>;
  }

  // First-run: no PAT yet — show the setup screen without the global topbar
  if (status === 'needs-pat') {
    return (
      <div className="setup-shell">
        <div className="setup-shell__inner">
          <h1 className="setup-shell__title">ByteQuay</h1>
          <SettingsScreen
            firstRun
            onSaved={() => {
              setNav({ view: 'home' });
              setStatus('ready');
            }}
          />
        </div>
      </div>
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
            onGoToTeams={() => setNav({ view: 'settings', section: 'teams' })}
          />
        )}
        {nav.view === 'my-prs' && (
          <PullRequestList
            onGoToTeams={() => setNav({ view: 'settings', section: 'teams' })}
          />
        )}
        {nav.view === 'repo' && (
          <RepoDetailPage
            owner={nav.owner}
            repo={nav.repo}
            initialPrNumber={nav.prNumber}
          />
        )}
        {nav.view === 'notifications' && (
          <NotificationsScreen />
        )}
        {nav.view === 'team' && (
          <TeamDetailPage
            teamId={nav.teamId}
            onBack={() => setNav({ view: 'settings', section: 'teams' })}
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
