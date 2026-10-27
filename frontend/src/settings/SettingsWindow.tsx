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
import { clock } from '@bytequay/ui/displayText';
import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  DevelopmentAutomationDto,
  AgentModelType,
  AgentSkillDto,
  AppPreferencesDto,
  GitHubAccountDto,
  ModelSettingsDto,
  QuietHoursDto,
  WorkspaceDto,
  WorkspaceSettingsDto,
} from '../types';
import DeleteWorkspaceDialog from '../workspaces/DeleteWorkspaceDialog';
import UnwatchWorkspaceDialog from '../workspaces/UnwatchWorkspaceDialog';
import ModelsPage, { type ModelChoice } from './ModelsPage';
import ProviderSignInDialog from '../agents/ProviderSignInDialog';
import SkillsPage from './SkillsPage';
import ShortcutsPage from './ShortcutsPage';
import WorkflowPage from './WorkflowPage';
import FeaturesPage from './FeaturesPage';
import GitHubPage from './GitHubPage';
import JiraPage from './JiraPage';
import { GooglePage } from '../integrations/google';
import { LabPage } from '@bytequay/lab/renderer';
import { openLab } from '../lab';
import { AppearancePage, BrowserPage, GeneralPage } from './PersonalPages';
import {
  WorkspaceAdvancedPage,
  WorkspaceAgentsPage,
  WorkspaceRepositoryPage,
  WorkspaceTokenPage,
} from './WorkspacePages';
import { isWorkspaceSection, type SettingsSection } from './routes';
import type { ClaudeLogin } from './model';
import '../css/settings-window.css';

export default function SettingsWindow({ section, workspace, onWorkspacesChanged }: {
  section: SettingsSection;
  /** Resolved by the shell, because its sidebar owns the workspace picker. */
  workspace: WorkspaceDto | null;
  /** Renaming, unwatching or deleting changes the list the picker shows. */
  onWorkspacesChanged: () => void;
}) {
  const [automation, setAutomation] = useState<DevelopmentAutomationDto | null>(null);
  const automationSaves = useRef<Promise<unknown>>(Promise.resolve());
  const automationRequest = useRef(0);
  const [error, setError] = useState<string | null>(null);
  const [signingIn, setSigningIn] = useState<{ laneId: string; providerName: string } | null>(null);

  const [preferences, setPreferences] = useState<AppPreferencesDto | null>(null);
  const preferenceSaves = useRef<Promise<unknown>>(Promise.resolve());
  const preferenceRequest = useRef(0);
  const [version, setVersion] = useState('');
  const [clearing, setClearing] = useState(false);
  const modelWorkspaceId = section === 'ws-agents' ? workspace?.id : undefined;
  const modelScopeRef = useRef(modelWorkspaceId);
  modelScopeRef.current = modelWorkspaceId;
  const [modelsScope, setModelsScope] = useState<string | undefined>(undefined);
  const [models, setModels] = useState<ModelSettingsDto | null>(null);
  useEffect(() => { if (models !== null) window.dispatchEvent(new Event('bytequay:models-changed')); }, [models]);
  const [checking, setChecking] = useState(false);
  const checkedAt = models?.checkedAt ? clock(new Date(models.checkedAt)) : null;
  const modelRequest = useRef(0);
  const modelSaves = useRef<Promise<unknown>>(Promise.resolve());
  const [skills, setSkills] = useState<AgentSkillDto[] | null>(null);
  const [skillsBusy, setSkillsBusy] = useState(false);
  const [account, setAccount] = useState<GitHubAccountDto | null>(null);
  const [login, setLogin] = useState<string | null>(null);
  const [quietHours, setQuietHours] = useState<QuietHoursDto | null>(null);
  const [quietHoursBusy, setQuietHoursBusy] = useState(false);
  const quietHoursSaving = useRef(false);
  const quietHoursRequest = useRef(0);
  const [copied, setCopied] = useState(false);
  const [wsSettings, setWsSettings] = useState<WorkspaceSettingsDto | null>(null);
  const workspaceRequest = useRef(0);
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

  const loadModels = useCallback((force = false) => {
    const request = ++modelRequest.current;
    const receive = (value: ModelSettingsDto) => {
      if (request !== modelRequest.current) return;
      setModels(value);
      setModelsScope(modelWorkspaceId);
    };
    setError(null);
    setChecking(true);
    // Paint saved choices before waiting for CLI startup, authentication, or model discovery.
    return modelSaves.current.then(() => window.bridge.getModelSettings(modelWorkspaceId))
      .then(receive)
      .catch(failure => { if (request === modelRequest.current) report(failure); })
      .then(() => window.bridge.refreshModelSettings(force, modelWorkspaceId))
      .then(receive)
      .catch(failure => { if (request === modelRequest.current) report(failure); })
      .finally(() => { if (request === modelRequest.current) setChecking(false); });
  }, [report, modelWorkspaceId]);

  useEffect(() => {
    if (section !== 'skills') return;
    void window.bridge.getAgentSkills().then(setSkills).catch(report);
  }, [section, report]);

  useEffect(() => {
    if (section !== 'workflow') return;
    let active = true;
    const request = automationRequest.current;
    void automationSaves.current.then(() => window.bridge.getDevelopmentAutomation())
      .then(value => { if (active && request === automationRequest.current) setAutomation(value); })
      .catch(failure => { if (active && request === automationRequest.current) report(failure); });
    return () => { active = false; };
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
  }, [section, loadAccount]);

  useEffect(() => {
    if (section !== 'general' && section !== 'features' || quietHoursSaving.current) return;
    const request = ++quietHoursRequest.current;
    void window.bridge.getQuietHours().then(value => { if (request === quietHoursRequest.current) setQuietHours(value); }).catch(report);
    return () => { quietHoursRequest.current += 1; };
  }, [section, report]);

  const workspaceId_ = workspace?.id ?? null;

  /**
   * Re-reads the workspace after something on the page changed it. It leaves the
   * section values on screen while the answer is in flight, because the page is
   * already showing this workspace.
   */
  const refreshWorkspaceSettings = useCallback(() => {
    const id = workspaceRef.current;
    if (id === null) return;
    const request = ++workspaceRequest.current;
    window.bridge.getWorkspaceSettings(id)
      .then(value => {
        if (workspaceRef.current !== id || request !== workspaceRequest.current) return;
        setWsSettings(value);
        window.dispatchEvent(new Event('bytequay:models-changed'));
      })
      .catch(failure => { if (workspaceRef.current === id && request === workspaceRequest.current) report(failure); });
  }, [report]);

  useEffect(() => { workspaceRef.current = workspaceId_; }, [workspaceId_]);

  useEffect(() => {
    if (section !== 'models' && section !== 'ws-agents' && section !== 'workflow') return;
    let active = true;
    void loadModels().then(() => { if (active && section === 'ws-agents') refreshWorkspaceSettings(); });
    return () => { active = false; modelRequest.current += 1; };
  }, [section, loadModels, refreshWorkspaceSettings]);

  // Clearing and loading live in one effect keyed on the workspace: separating them is how
  // re-picking the workspace already open blanked the page with nothing left to refill it.
  useEffect(() => {
    if (!isWorkspaceSection(section) || workspaceId_ === null) return;
    setWsSettings(null);
    refreshWorkspaceSettings();
    return () => { workspaceRequest.current += 1; };
  }, [section, refreshWorkspaceSettings, workspaceId_]);

  const saveModels = (save: () => Promise<ModelSettingsDto>) => {
    const request = ++modelRequest.current;
    const scope = modelWorkspaceId;
    setChecking(false);
    setError(null);
    // Keep writes ordered and prevent a provider read started before this edit from reverting it.
    modelSaves.current = modelSaves.current.then(save)
      .then(value => {
        if (request !== modelRequest.current || modelScopeRef.current !== scope) return;
        setModels(value);
        setModelsScope(scope);
      })
      .catch(async (failure: unknown) => {
        if (modelScopeRef.current !== scope) return;
        report(failure);
        if (request !== modelRequest.current) return;
        try {
          const saved = await window.bridge.getModelSettings(scope);
          if (request !== modelRequest.current || modelScopeRef.current !== scope) return;
          setModels(saved);
          setModelsScope(scope);
        }
        catch { /* Keep the last displayed choices beside the save error until the user retries. */ }
      });
  };

  const chooseWorkspaceModel = (type: AgentModelType, choice: ModelChoice) => {
    if (workspace === null) return;
    workspaceRequest.current += 1;
    setError(null);
    window.bridge.setWorkspaceAgentModel(workspace.id, type, choice.configId, choice.model, choice.effort)
      .then(refreshWorkspaceSettings)
      .catch(report);
  };

  const addClaudeAccount = async (label: string, login: ClaudeLogin) => {
    setError(null);
    const scope = modelWorkspaceId;
    const updated = 'email' in login
      ? await window.bridge.addClaudeAccount(label, login.email, scope)
      : await window.bridge.addClaudeGateway(label, login.gatewayUrl, login.token, scope);
    if (modelScopeRef.current !== scope) return;
    modelRequest.current += 1;
    setChecking(false);
    setModels(updated);
    setModelsScope(scope);
    refreshWorkspaceSettings();
    const account = updated.providers.find(provider => provider.label === `Claude · ${label}`);
    if (account !== undefined && 'email' in login) setSigningIn({ laneId: account.id, providerName: account.label });
  };

  const removeClaudeAccount = async (laneId: string) => {
    setError(null);
    const scope = modelWorkspaceId;
    await window.bridge.removeClaudeAccount(laneId);
    const updated = await window.bridge.getModelSettings(scope);
    if (modelScopeRef.current !== scope) return;
    modelRequest.current += 1;
    setChecking(false);
    setModels(updated);
    refreshWorkspaceSettings();
  };

  const restrictClaudeAccount = async (laneId: string) => {
    if (modelWorkspaceId === undefined) return;
    const scope = modelWorkspaceId;
    const updated = await window.bridge.restrictClaudeAccount(laneId, scope);
    if (modelScopeRef.current !== scope) return;
    modelRequest.current += 1;
    setChecking(false);
    setModels(updated);
    refreshWorkspaceSettings();
  };

  const savePreferences = (next: AppPreferencesDto) => {
    const request = ++preferenceRequest.current;
    setPreferences(next);
    setError(null);
    preferenceSaves.current = preferenceSaves.current.then(() => window.bridge.saveAppPreferences(next))
      .then(saved => { if (request === preferenceRequest.current) setPreferences(saved); })
      .catch(async (failure: unknown) => {
        if (request !== preferenceRequest.current) return;
        report(failure);
        try {
          const saved = await window.bridge.getAppPreferences();
          if (request === preferenceRequest.current) setPreferences(saved);
        }
        catch { /* Keep the failed selection visible beside its error until the user retries. */ }
      });
  };

  const copyToken = (copy: Promise<unknown>) => {
    copy.then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    }).catch(report);
  };

  return (
    <>
    {signingIn !== null && (
      <ProviderSignInDialog
        laneId={signingIn.laneId}
        providerName={signingIn.providerName}
        bridge={window.bridge}
        onClose={signedIn => {
          setSigningIn(null);
          // Signing a lane in can change which provider the global default resolves to, so the
          // workspace rows that show that default have to be re-read too.
          if (signedIn) void loadModels(true).then(refreshWorkspaceSettings);
        }}
      />
    )}
    <div className="setw-scroll">
          {section === 'general' && preferences !== null && (
            <GeneralPage
              preferences={preferences}
              quietHours={quietHours}
              quietHoursBusy={quietHoursBusy}
              version={version}
              clearing={clearing}
              error={error}
              onChange={savePreferences}
              onQuietHours={next => {
                if (quietHoursSaving.current) return;
                quietHoursSaving.current = true;
                quietHoursRequest.current += 1;
                setQuietHoursBusy(true);
                setError(null);
                window.bridge.saveQuietHours(next).then(setQuietHours).catch(report).finally(() => { quietHoursSaving.current = false; setQuietHoursBusy(false); });
              }}
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
          {section === 'browser' && <BrowserPage />}
          {section === 'shortcuts' && <ShortcutsPage />}
          {section === 'models' && (models === null || modelsScope !== modelWorkspaceId) && (
            <div className="setw-page">
              <h1>Models</h1>
              {error !== null && <div className="setw-error" role="alert">{error}</div>}
              <div className="setw-row-empty">Loading saved models…</div>
              {error !== null && <button type="button" className="setw-btn" onClick={() => loadModels(true)}>Try again</button>}
            </div>
          )}
          {section === 'models' && models !== null && modelsScope === modelWorkspaceId && (
            <ModelsPage
              settings={models}
              onAddClaudeAccount={addClaudeAccount}
              onRemoveClaudeAccount={removeClaudeAccount}
              checking={checking}
              checkedAt={checkedAt}
              error={error}
              onCheck={() => loadModels(true)}
              onSignIn={providerId => setSigningIn({
                laneId: providerId,
                providerName: models.providers.find(provider => provider.id === providerId)?.label ?? providerId,
              })}
              onChoose={(type, choice) => saveModels(() => window.bridge.setAgentModel(type, choice.configId, choice.model, choice.effort))}
              onInherit={type => saveModels(() => window.bridge.clearAgentModel(type))}
              onAddKey={(provider, alias, key) => saveModels(() => window.bridge.addModelApiKey(provider, alias, key))}
              onRemoveKey={alias => saveModels(() => window.bridge.removeModelApiKey(alias))}
            />
          )}
          {section === 'features' && <FeaturesPage quietHours={quietHours} />}
          {section === 'workflow' && <WorkflowPage value={automation} providers={modelsScope === modelWorkspaceId ? models?.providers ?? [] : []} error={error}
            preferences={preferences} onPreferences={savePreferences}
            onChange={next => {
              const request = ++automationRequest.current;
              setAutomation(next);
              setError(null);
              // Saves run in order, so a quick drag across the slider ends saved at the stop it ended on.
              automationSaves.current = automationSaves.current.then(() => window.bridge.saveDevelopmentAutomation(next))
                .then(saved => { if (request === automationRequest.current) setAutomation(saved); })
                .catch(async (failure: unknown) => {
                  if (request !== automationRequest.current) return;
                  report(failure);
                  try {
                    const saved = await window.bridge.getDevelopmentAutomation();
                    if (request === automationRequest.current) setAutomation(saved);
                  }
                  catch { /* Keep the failed selection visible beside its error until the user retries. */ }
                });
            }} />}
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
                return saved.then(setSkills).finally(() => setSkillsBusy(false));
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
          {section === 'jira' && <JiraPage />}
          {section === 'google' && <GooglePage />}
          {section === 'lab' && <LabPage onOpen={openLab} />}
          {section === 'github' && preferences !== null && (
            <GitHubPage
              account={account}
              login={login}
              preferences={preferences}
              copied={copied}
              error={error}
              onSaveToken={token => { window.bridge.savePat(token).then(loadAccount).catch(report); }}
              onDisconnect={() => { window.bridge.deleteGitHubToken().then(loadAccount).catch(report); }}
              onCopy={() => copyToken(window.bridge.copyGitHubToken())}
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
              key={workspace.id}
              workspace={workspace}
              settings={wsSettings}
              error={error}
              onUnlinkUpstream={() => {
                window.bridge.setWorkspaceUpstream(workspace.id, null).then(refreshWorkspaceSettings).catch(report);
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
              key={workspace.id}
              workspace={workspace}
              settings={wsSettings}
              models={modelsScope === modelWorkspaceId ? models : null}
              checking={checking}
              checkedAt={checkedAt}
              error={error}
              onChoose={chooseWorkspaceModel}
              onUseGlobal={type => {
                workspaceRequest.current += 1;
                setError(null);
                window.bridge.clearWorkspaceAgentModel(workspace.id, type).then(refreshWorkspaceSettings).catch(report);
              }}
              onCheck={() => { void loadModels(true).then(refreshWorkspaceSettings); }}
              onSignIn={providerId => setSigningIn({
                laneId: providerId,
                providerName: models?.providers.find(provider => provider.id === providerId)?.label ?? providerId,
              })}
              onAddClaudeAccount={addClaudeAccount}
              onRemoveClaudeAccount={removeClaudeAccount}
              onRestrictClaudeAccount={restrictClaudeAccount}
            />
          )}
          {section === 'ws-token' && workspace !== null && (
            <WorkspaceTokenPage
              key={workspace.id}
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
