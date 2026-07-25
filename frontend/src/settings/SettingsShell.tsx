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
import AgentRolesPage from './pages/AgentRolesPage';
import ConceptsPage from './pages/ConceptsPage';
import SavedViewsPage from './pages/SavedViewsPage';
import SkillsPage from './pages/SkillsPage';
import WatchedReposPage from './pages/WatchedReposPage';
import WorkspaceMemoryPage from './pages/WorkspaceMemoryPage';

type Props = {
  section: SettingsSection;
  workspaceId?: string | null;
  onSelectSection: (section: SettingsSection) => void;
  /** Forwarded to WorkspaceMemoryPage so back-link chips in the
   *  memory proposal banner can navigate to the source thread. */
  onOpenThread?: (threadId: string) => void;
};

function SettingsShell({ section, workspaceId, onSelectSection, onOpenThread }: Props) {
  // 'github-token' is kept in the section union so existing onboarding
  // deep links resolve cleanly; the Credentials → Git PAT tab owns the
  // PAT now, so we alias the old id at render time.
  const resolved = section === 'github-token' ? 'credentials' : section;
  // Local AI is a tab of the AI page, not a page of its own. The rail
  // entry and every existing 'local-ai' deep link land on the AI page
  // with that tab already open.
  const isAi = resolved === 'ai-review' || resolved === 'local-ai';
  return (
    <section className="sv2">
      <SettingsSidebar active={resolved} onSelect={onSelectSection} />
      <div className="sv2-content">
          {resolved === 'account' && <AccountPage />}
          {resolved === 'appearance' && <AppearancePage />}
          {resolved === 'credentials' && <CredentialsPage />}
          {isAi && <AiReviewPage key={resolved} initialTab={resolved === 'local-ai' ? 'local' : 'defaults'} />}
          {resolved === 'skills' && <SkillsPage />}
          {resolved === 'agent-roles' && <AgentRolesPage />}
          {resolved === 'saved-views' && <SavedViewsPage />}
          {resolved === 'concepts' && <ConceptsPage />}
          {resolved === 'watched-repos' && <WatchedReposPage workspaceId={workspaceId} />}
          {resolved === 'workspace-memory' && (
            <WorkspaceMemoryPage workspaceId={workspaceId ?? undefined} onOpenThread={onOpenThread} />
          )}
          {resolved === 'integrations' && <IntegrationsPage />}
          {resolved === 'email' && <EmailSettingsPage />}
          {resolved === 'help' && <HelpPage />}
      </div>
    </section>
  );
}

export default SettingsShell;
