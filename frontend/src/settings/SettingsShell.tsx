import SettingsSidebar from './SettingsSidebar';
import type { SettingsSection } from './types';

import AccountPage from './pages/AccountPage';
import AppearancePage from './pages/AppearancePage';
import AiReviewPage from './pages/AiReviewPage';
import GitHubTokenPage from './pages/GitHubTokenPage';
import HelpPage from './pages/HelpPage';
import IntegrationsPage from './pages/IntegrationsPage';
import TeamsPage from './pages/TeamsPage';
import WatchedReposPage from './pages/WatchedReposPage';

type Props = {
  section: SettingsSection;
  onSelectSection: (section: SettingsSection) => void;
  /** Forwarded to AccountPage so the Disconnect button lands the user back on first-run setup. */
  onClearPat?: () => void;
  /** Forwarded to TeamsPage so a team-row click navigates to the top-level TeamDetailPage. */
  onOpenTeam?: (id: number) => void;
};

function SettingsShell({ section, onSelectSection, onClearPat, onOpenTeam }: Props) {
  return (
    <section className="settings-shell">
      <div className="settings-shell__layout">
        <SettingsSidebar active={section} onSelect={onSelectSection} />
        <div className="settings-shell__content">
          {section === 'account' && <AccountPage onClearPat={onClearPat} />}
          {section === 'appearance' && <AppearancePage />}
          {section === 'github-token' && <GitHubTokenPage />}
          {section === 'teams' && <TeamsPage onOpenTeam={onOpenTeam} />}
          {section === 'ai-review' && <AiReviewPage />}
          {section === 'watched-repos' && <WatchedReposPage />}
          {section === 'integrations' && <IntegrationsPage />}
          {section === 'help' && <HelpPage />}
        </div>
      </div>
    </section>
  );
}

export default SettingsShell;
