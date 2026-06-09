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
import SettingsSidebar from './SettingsSidebar';
import type { SettingsSection } from './types';

import AccountPage from './pages/AccountPage';
import AppearancePage from './pages/AppearancePage';
import AiReviewPage from './pages/AiReviewPage';
import CredentialsPage from './pages/CredentialsPage';
import EmailSettingsPage from './pages/EmailPage';
import HelpPage from './pages/HelpPage';
import IntegrationsPage from './pages/IntegrationsPage';
import LocalAiPage from './pages/LocalAiPage';
import ConceptsPage from './pages/ConceptsPage';
import SavedViewsPage from './pages/SavedViewsPage';
import SkillsPage from './pages/SkillsPage';
import TeamsPage from './pages/TeamsPage';
import WatchedReposPage from './pages/WatchedReposPage';
import WorkspaceMemoryPage from './pages/WorkspaceMemoryPage';

type Props = {
  section: SettingsSection;
  onSelectSection: (section: SettingsSection) => void;
  /** Forwarded to AccountPage so the Disconnect button lands the user back on first-run setup. */
  onClearPat?: () => void;
  /** Forwarded to TeamsPage so a team-row click navigates to the top-level TeamDetailPage. */
  onOpenTeam?: (id: number) => void;
  /** Forwarded to WorkspaceMemoryPage so back-link chips in the
   *  memory proposal banner can navigate to the source thread. */
  onOpenThread?: (threadId: string) => void;
};

function SettingsShell({ section, onSelectSection, onClearPat, onOpenTeam, onOpenThread }: Props) {
  // 'github-token' is kept in the section union so existing onboarding
  // deep links resolve cleanly; the Credentials → Git PAT tab owns the
  // PAT now, so we alias the old id at render time.
  const resolved = section === 'github-token' ? 'credentials' : section;
  return (
    <section className="settings-shell">
      <div className="settings-shell__layout">
        <SettingsSidebar active={resolved} onSelect={onSelectSection} />
        <div className="settings-shell__content">
          {resolved === 'account' && <AccountPage onClearPat={onClearPat} />}
          {resolved === 'appearance' && <AppearancePage />}
          {resolved === 'credentials' && <CredentialsPage />}
          {resolved === 'teams' && <TeamsPage onOpenTeam={onOpenTeam} />}
          {resolved === 'ai-review' && <AiReviewPage />}
          {resolved === 'local-ai' && <LocalAiPage />}
          {resolved === 'skills' && <SkillsPage />}
          {resolved === 'saved-views' && <SavedViewsPage />}
          {resolved === 'concepts' && <ConceptsPage />}
          {resolved === 'watched-repos' && <WatchedReposPage />}
          {resolved === 'workspace-memory' && <WorkspaceMemoryPage onOpenThread={onOpenThread} />}
          {resolved === 'integrations' && <IntegrationsPage />}
          {resolved === 'email' && <EmailSettingsPage />}
          {resolved === 'help' && <HelpPage />}
        </div>
      </div>
    </section>
  );
}

export default SettingsShell;
