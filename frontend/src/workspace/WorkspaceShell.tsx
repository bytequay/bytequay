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
import { useState } from 'react';
import WorkspaceMemoryPage from '../settings/pages/WorkspaceMemoryPage';
import NewThreadDialog from './NewThreadDialog';
import NewWorkspaceDialog from './NewWorkspaceDialog';
import WorkspaceHomePage from './WorkspaceHomePage';
import WorkspaceInsightsPage from './WorkspaceInsightsPage';
import WorkspaceLeftRail from './WorkspaceLeftRail';
import WorkspaceSettingsPage from './WorkspaceSettingsPage';
import WorkspaceThreadsPage from './WorkspaceThreadsPage';

export type WorkspaceSection = 'home' | 'threads' | 'memory' | 'insights' | 'settings';

type Props = {
  section: WorkspaceSection;
  onSelectSection: (section: WorkspaceSection) => void;
  /** Callback for back-link chips inside the memory proposal banner.
   *  Routes to a review/build thread detail at the app level. */
  onOpenThread?: (threadId: string) => void;
  /** Fallback when a section punches out of the shell (e.g. the
   *  Threads section's "Go to threads list" button until we render
   *  ThreadsPage inline in a polish pass). */
  onLeaveShell?: () => void;
  /** Punch from the new-thread dialog into the full create page
   *  (existing ThreadCreatePage) so the user can finish picking
   *  repo + agent + skills. The modal owns the prompt + start-mode
   *  intent; everything else stays on the page. */
  onOpenThreadCreate?: (params: { initialPrompt: string; initialGroupId?: string }) => void;
  /** Open the Phase-9 control bar. Wired here so the left-rail
   *  command-bar placeholder becomes an actual launcher. */
  onOpenControlBar?: () => void;
};

/** Calm-language workspace shell. Sibling of the existing top-level
 *  chrome (Home / PRs / Repos / Email / Threads / Notifications /
 *  Settings) — clicking the "Workspace" entry in the global topbar
 *  mounts this; the user moves between the 5 inner sections via the
 *  left rail. Browse-mode pages stay outside the shell. */
function WorkspaceShell({
  section, onSelectSection, onOpenThread, onLeaveShell, onOpenThreadCreate, onOpenControlBar,
}: Props) {
  const [newThreadOpen, setNewThreadOpen] = useState(false);
  const [newWorkspaceOpen, setNewWorkspaceOpen] = useState(false);

  return (
    <section className="workspace-shell">
      <WorkspaceLeftRail
        active={section}
        onSelect={onSelectSection}
        onOpenNewWorkspace={() => setNewWorkspaceOpen(true)}
        onOpenControlBar={onOpenControlBar}
      />
      <div className="workspace-content">
        {section === 'home' && (
          <WorkspaceHomePage
            onSelectSection={onSelectSection}
            onNewThread={() => setNewThreadOpen(true)}
            onOpenThread={onOpenThread}
          />
        )}
        {section === 'threads' && <WorkspaceThreadsPage onLeaveShell={onLeaveShell} />}
        {section === 'memory' && <WorkspaceMemoryPage onOpenThread={onOpenThread} />}
        {section === 'insights' && <WorkspaceInsightsPage />}
        {section === 'settings' && <WorkspaceSettingsPage />}
      </div>
      {newThreadOpen && (
        <NewThreadDialog
          onClose={() => setNewThreadOpen(false)}
          onContinueFullForm={({ prompt }) => {
            setNewThreadOpen(false);
            // The full create page owns repo + agent + skills + linked
            // PR/issue; the dialog hands off the prompt it captured.
            // start-mode is informational for now — the full page
            // surfaces both modes through its own toggles.
            onOpenThreadCreate?.({ initialPrompt: prompt });
          }}
        />
      )}
      {newWorkspaceOpen && (
        <NewWorkspaceDialog onClose={() => setNewWorkspaceOpen(false)} />
      )}
    </section>
  );
}

export default WorkspaceShell;
