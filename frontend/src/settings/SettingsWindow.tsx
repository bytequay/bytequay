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
import type {
  AgentModelType,
  AgentSkillDto,
  AppPreferencesDto,
  CodeGraphBaselineDto,
  CodeGraphMode,
  GitHubAccountDto,
  ModelSettingsDto,
  QuietHoursDto,
  WorkspaceDto,
  WorkspaceRemoteDto,
  WorkspaceSettingsDto,
} from '../types';
import DeleteWorkspaceDialog from '../workspaces/DeleteWorkspaceDialog';
import UnwatchWorkspaceDialog from '../workspaces/UnwatchWorkspaceDialog';
import ModelsPage, { type ModelChoice } from './ModelsPage';
import SkillsPage from './SkillsPage';
import CodeGraphPage from './CodeGraphPage';
import GitHubPage from './GitHubPage';
import { AppearancePage, GeneralPage } from './PersonalPages';
import {
  WorkspaceAdvancedPage,
  WorkspaceAgentsPage,
  WorkspaceRepositoryPage,
  WorkspaceTokenPage,
} from './WorkspacePages';
import { isWorkspaceSection, settingsLocationFromRoute, type SettingsSection } from './routes';
import '../css/settings-window.css';

function clockTime(at: Date): string {
  return at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export default function SettingsWindow({ section, workspace, onWorkspacesChanged }: {
  section: SettingsSection;
  /** Resolved by the shell, because its sidebar owns the workspace picker. */
  workspace: WorkspaceDto | null;
  /** Renaming, unwatching or deleting changes the list the picker shows. */
  onWorkspacesChanged: () => void;
}) {
  const [error, setError] = useState<string | null>(null);

  const [preferences, setPreferences] = useState<AppPreferencesDto | null>(null);
  const [version, setVersion] = useState('');
  const [clearing, setClearing] = useState(false);
  const [models, setModels] = useState<ModelSettingsDto | null>(null);
  const [checking, setChecking] = useState(false);
  const [checkedAt, setCheckedAt] = useState<string | null>(null);
  const [skills, setSkills] = useState<AgentSkillDto[] | null>(null);
  const [skillsBusy, setSkillsBusy] = useState(false);
  const [graphMode, setGraphMode] = useState<CodeGraphMode | null>(null);
  const [baseline, setBaseline] = useState<CodeGraphBaselineDto | null>(null);
  const [account, setAccount] = useState<GitHubAccountDto | null>(null);
  const [login, setLogin] = useState<string | null>(null);
  const [quietHours, setQuietHours] = useState<QuietHoursDto | null>(null);
  const [copied, setCopied] = useState(false);
  const [wsSettings, setWsSettings] = useState<WorkspaceSettingsDto | null>(null);
  const [wsBusy, setWsBusy] = useState(false);
  const [unwatching, setUnwatching] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const workspaceRef = useRef<string | null>(null);

  const report = useCallback((failure: unknown) => {
    setError(failure instanceof Error ? failure.message : String(failure));
  }, []);

  useEffect(() => setError(null), [section]);

  useEffect(() => {
    void window.bridge.getAppVersion().then(value => setVersion(value.version)).catch(() => {});
    void window.bridge.getAppPreferences().then(setPreferences).catch(report);
  }, [report]);

  const loadModels = useCallback(() => {
    setChecking(true);
    window.bridge.getModelSettings()
      .then(value => { setModels(value); setCheckedAt(clockTime(new Date())); })
      .catch(report)
      .finally(() => setChecking(false));
  }, [report]);

  useEffect(() => {
    if (section !== 'models' && section !== 'ws-agents') return;
    loadModels();
  }, [section, loadModels]);

  useEffect(() => {
    if (section !== 'skills') return;
    void window.bridge.getAgentSkills().then(setSkills).catch(report);
  }, [section, report]);

  useEffect(() => {
    if (section !== 'code-graph') return;
    void window.bridge.getCodeGraphSetting().then(value => setGraphMode(value.mode)).catch(report);
    void window.bridge.getCodeGraphBaseline().then(setBaseline).catch(() => {});
  }, [section, report]);

  const loadAccount = useCallback(() => {
    void window.bridge.getGitHubAccount()
      .then(value => {
        setAccount(value);
        if (value.configured) {
          window.bridge.getUserProfile().then(profile => setLogin(profile.login)).catch(() => setLogin(null));
        }
        else setLogin(null);
      })
      .catch(report);
  }, [report]);

  useEffect(() => {
    if (section !== 'github' && section !== 'ws-token') return;
    loadAccount();
    void window.bridge.getQuietHours().then(setQuietHours).catch(() => {});
  }, [section, loadAccount]);

    const workspaceId_ = workspace?.id ?? null;

  /**
   * Re-reads the workspace after something on the page changed it. It leaves the
   * section values on screen while the answer is in flight, because the page is
   * already showing this workspace.
   */
  const refreshWorkspaceSettings = useCallback(() => {
    const id = workspaceRef.current;
    if (id === null) return;
    window.bridge.getWorkspaceSettings(id)
      .then(value => { if (workspaceRef.current === id) setWsSettings(value); })
      .catch(report);
  }, [report]);

  useEffect(() => { workspaceRef.current = workspaceId_; }, [workspaceId_]);

  // Clearing and loading live in one effect keyed on the workspace: separating them is how
  // re-picking the workspace already open blanked the page with nothing left to refill it.
  useEffect(() => {
    if (!isWorkspaceSection(section) || workspaceId_ === null) return;
    let active = true;
    setWsSettings(null);
    window.bridge.getWorkspaceSettings(workspaceId_)
      .then(value => { if (active) setWsSettings(value); })
      .catch(failure => { if (active) report(failure); });
    return () => { active = false; };
  }, [section, report, workspaceId_]);

  const chooseModel = (type: AgentModelType, choice: ModelChoice) => {
    setError(null);
    window.bridge.setAgentModel(type, choice.configId, choice.model, choice.effort)
      .then(setModels)
      .catch(report);
  };

  const chooseWorkspaceModel = (type: AgentModelType, choice: ModelChoice) => {
    if (workspace === null) return;
    setError(null);
    window.bridge.setWorkspaceAgentModel(workspace.id, type, choice.configId, choice.model, choice.effort)
      .then(refreshWorkspaceSettings)
      .catch(report);
  };

  const savePreferences = (next: AppPreferencesDto) => {
    setPreferences(next);
    window.bridge.saveAppPreferences(next).then(setPreferences).catch(report);
  };

  const copyToken = (copy: Promise<unknown>) => {
    copy.then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    }).catch(report);
  };

  return (
    <>
    <div className="setw-scroll">
          {section === 'general' && preferences !== null && (
            <GeneralPage
              preferences={preferences}
              version={version}
              clearing={clearing}
              error={error}
              onChange={savePreferences}
              onClearRecent={() => {
                setClearing(true);
                window.bridge.clearRecent()
                  .then(() => window.dispatchEvent(new Event('bytequay:recent-cleared')))
                  .catch(report)
                  .finally(() => setClearing(false));
              }}
            />
          )}
          {section === 'appearance' && preferences !== null && (
            <AppearancePage preferences={preferences} error={error} onChange={savePreferences} />
          )}
          {section === 'models' && models !== null && (
            <ModelsPage
              settings={models}
              checking={checking}
              checkedAt={checkedAt}
              error={error}
              onCheck={loadModels}
              onChoose={chooseModel}
              onInherit={type => {
                setError(null);
                window.bridge.clearAgentModel(type).then(setModels).catch(report);
              }}
              onAddKey={(provider, alias, key) => {
                setError(null);
                window.bridge.addModelApiKey(provider, alias, key).then(setModels).catch(report);
              }}
              onRemoveKey={alias => {
                setError(null);
                window.bridge.removeModelApiKey(alias).then(setModels).catch(report);
              }}
            />
          )}
          {section === 'skills' && (
            <SkillsPage
              skills={skills ?? []}
              busy={skillsBusy}
              error={error}
              onSave={(input, id) => {
                setSkillsBusy(true);
                const saved = id === null
                  ? window.bridge.addAgentSkill(input)
                  : window.bridge.updateAgentSkill(id, input);
                saved.then(setSkills).catch(report).finally(() => setSkillsBusy(false));
              }}
              onToggle={skill => {
                setSkillsBusy(true);
                window.bridge.updateAgentSkill(skill.id, { ...skill, enabled: !skill.enabled })
                  .then(setSkills).catch(report).finally(() => setSkillsBusy(false));
              }}
              onRemove={skill => {
                setSkillsBusy(true);
                window.bridge.removeAgentSkill(skill.id)
                  .then(setSkills).catch(report).finally(() => setSkillsBusy(false));
              }}
            />
          )}
          {section === 'code-graph' && graphMode !== null && (
            <CodeGraphPage
              mode={graphMode}
              baseline={baseline}
              error={error}
              onMode={mode => {
                setGraphMode(mode);
                window.bridge.saveCodeGraphSetting({ mode }).then(value => setGraphMode(value.mode)).catch(report);
              }}
            />
          )}
          {section === 'github' && preferences !== null && (
            <GitHubPage
              account={account}
              login={login}
              quietHours={quietHours}
              preferences={preferences}
              copied={copied}
              error={error}
              onSaveToken={token => { window.bridge.savePat(token).then(loadAccount).catch(report); }}
              onDisconnect={() => { window.bridge.deleteGitHubToken().then(loadAccount).catch(report); }}
              onCopy={() => copyToken(window.bridge.copyGitHubToken())}
              onQuietHours={next => {
                setQuietHours(next);
                window.bridge.saveQuietHours(next).then(setQuietHours).catch(report);
              }}
              onPreferences={savePreferences}
            />
          )}

          {isWorkspaceSection(section) && workspace === null && (
            <div className="setw-page">
              <h1>Workspace</h1>
              <div className="setw-row-empty">Watch a repository to get workspace settings.</div>
            </div>
          )}
          {section === 'ws-repository' && workspace !== null && (
            <WorkspaceRepositoryPage
              workspace={workspace}
              settings={wsSettings}
              error={error}
              onAddRemote={(name, url) => {
                window.bridge.addWorkspaceRemote(workspace.id, name, url).then(refreshWorkspaceSettings).catch(report);
              }}
              onRemoveRemote={(remote: WorkspaceRemoteDto) => {
                // Unlinking an upstream is a different operation from deleting a remote, even
                // though both rows say Remove.
                const removed: Promise<unknown> = remote.role === 'UPSTREAM'
                  ? window.bridge.setWorkspaceUpstream(workspace.id, null)
                  : window.bridge.removeWorkspaceRemote(workspace.id, remote.name);
                removed.then(refreshWorkspaceSettings).catch(report);
              }}
              onSaveTarget={branch => {
                window.bridge.setWorkspaceCommitsTarget(workspace.id, branch).then(refreshWorkspaceSettings).catch(report);
              }}
              onResetTarget={() => {
                window.bridge.clearWorkspaceCommitsTarget(workspace.id).then(refreshWorkspaceSettings).catch(report);
              }}
              onRename={name => {
                window.bridge.renameWorkspace(workspace.id, name).then(onWorkspacesChanged).catch(report);
              }}
            />
          )}
          {section === 'ws-agents' && workspace !== null && (
            <WorkspaceAgentsPage
              workspace={workspace}
              settings={wsSettings}
              models={models}
              checking={checking}
              checkedAt={checkedAt}
              error={error}
              onChoose={chooseWorkspaceModel}
              onUseGlobal={type => {
                window.bridge.clearWorkspaceAgentModel(workspace.id, type).then(refreshWorkspaceSettings).catch(report);
              }}
              onCheck={loadModels}
            />
          )}
          {section === 'ws-token' && workspace !== null && (
            <WorkspaceTokenPage
              workspace={workspace}
              settings={wsSettings}
              login={login}
              copied={copied}
              error={error}
              onSave={token => {
                window.bridge.saveWorkspaceGitHubToken(workspace.id, token).then(refreshWorkspaceSettings).catch(report);
              }}
              onDelete={() => {
                window.bridge.deleteWorkspaceGitHubToken(workspace.id).then(refreshWorkspaceSettings).catch(report);
              }}
              onCopy={() => copyToken(window.bridge.copyWorkspaceGitHubToken(workspace.id))}
            />
          )}
          {section === 'ws-advanced' && workspace !== null && (
            <WorkspaceAdvancedPage
              workspace={workspace}
              busy={wsBusy}
              error={error}
              onUnwatch={() => { setError(null); setUnwatching(true); }}
              onDelete={() => setDeleting(true)}
            />
          )}
    </div>
    {unwatching && workspace !== null && (
        <UnwatchWorkspaceDialog
          workspace={workspace}
          busy={wsBusy}
          error={error}
          onConfirm={() => {
            setWsBusy(true);
            window.bridge.removeWorkspace(workspace.id)
              .then(() => { setUnwatching(false); onWorkspacesChanged(); })
              .catch(report)
              .finally(() => setWsBusy(false));
          }}
          onCancel={() => setUnwatching(false)}
        />
    )}
    {deleting && workspace !== null && (
        <DeleteWorkspaceDialog
          workspace={workspace}
          onDeleted={() => { setDeleting(false); onWorkspacesChanged(); }}
          onCancel={() => setDeleting(false)}
        />
    )}
    </>
  );
}

export { settingsLocationFromRoute };
