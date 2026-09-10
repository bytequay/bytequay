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
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Bot,
  ChevronDown,
  ChevronLeft,
  CircleUser,
  KeyRound,
  Network,
  Search,
  Settings as SettingsIcon,
  Sparkles,
  SquareLibrary,
  Sun,
  Tags,
  TriangleAlert,
} from 'lucide-react';
import type {
  AgentModelType,
  AgentSkillDto,
  AppPreferencesDto,
  CodeGraphBaselineDto,
  CodeGraphMode,
  CodeGraphRepositoryComparisonDto,
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
import {
  SETTINGS_ENTRIES,
  SETTINGS_GROUPS,
  type SettingsSection,
  isWorkspaceSection,
  matchesSearch,
  settingsLocationFromRoute,
  settingsRoute,
} from './routes';
import '../css/settings-window.css';

/** Which page the window reopens on. Personal, this Mac, not worth a backend row. */
const LAST_SECTION_KEY = 'bytequay.settings.section';

const ICONS: Record<SettingsSection, typeof SettingsIcon> = {
  general: SettingsIcon,
  appearance: Sun,
  models: Sparkles,
  skills: SquareLibrary,
  'code-graph': Network,
  github: CircleUser,
  'ws-repository': Tags,
  'ws-agents': Bot,
  'ws-token': KeyRound,
  'ws-advanced': TriangleAlert,
};

function readLastSection(): SettingsSection {
  try {
    const stored = window.localStorage.getItem(LAST_SECTION_KEY);
    if (stored !== null && SETTINGS_ENTRIES.some(entry => entry.section === stored)) {
      return stored as SettingsSection;
    }
  }
  catch { /* a private window has no storage; the default is as good an answer */ }
  return 'models';
}

function clockTime(at: Date): string {
  return at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export default function SettingsWindow({ section, workspaceId, onBack }: {
  section: SettingsSection | null;
  /** The workspace a deep link named, or null to use whichever the picker last chose. */
  workspaceId: string | null;
  onBack: () => void;
}) {
  const [query, setQuery] = useState('');
  const [current, setCurrent] = useState<SettingsSection>(section ?? readLastSection());
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[]>([]);
  const [pickedWorkspace, setPickedWorkspace] = useState<string | null>(workspaceId);
  const [wsMenuOpen, setWsMenuOpen] = useState(false);
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
  const [comparison, setComparison] = useState<CodeGraphRepositoryComparisonDto[]>([]);
  const [account, setAccount] = useState<GitHubAccountDto | null>(null);
  const [login, setLogin] = useState<string | null>(null);
  const [quietHours, setQuietHours] = useState<QuietHoursDto | null>(null);
  const [copied, setCopied] = useState(false);
  const [wsSettings, setWsSettings] = useState<WorkspaceSettingsDto | null>(null);
  const [wsBusy, setWsBusy] = useState(false);
  const [unwatching, setUnwatching] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const report = useCallback((failure: unknown) => {
    setError(failure instanceof Error ? failure.message : String(failure));
  }, []);

  // A deep link wins over the remembered page; a bare `#/settings` keeps what is open.
  useEffect(() => { if (section !== null) setCurrent(section); }, [section]);
  useEffect(() => { if (workspaceId !== null) setPickedWorkspace(workspaceId); }, [workspaceId]);
  useEffect(() => {
    try { window.localStorage.setItem(LAST_SECTION_KEY, current); }
    catch { /* storage is a convenience here, never a requirement */ }
  }, [current]);

  const go = useCallback((next: SettingsSection, workspace?: string | null) => {
    setCurrent(next);
    setError(null);
    window.location.hash = settingsRoute(next, isWorkspaceSection(next) ? (workspace ?? pickedWorkspace) : null);
  }, [pickedWorkspace]);

  useEffect(() => {
    void window.bridge.getAppVersion().then(value => setVersion(value.version)).catch(() => {});
    void window.bridge.listWorkspaces().then(setWorkspaces).catch(() => {});
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
    if (current !== 'models' && current !== 'ws-agents') return;
    loadModels();
  }, [current, loadModels]);

  useEffect(() => {
    if (current !== 'skills') return;
    void window.bridge.getAgentSkills().then(setSkills).catch(report);
  }, [current, report]);

  useEffect(() => {
    if (current !== 'code-graph') return;
    void window.bridge.getCodeGraphSetting().then(value => setGraphMode(value.mode)).catch(report);
    void window.bridge.getCodeGraphBaseline().then(setBaseline).catch(() => {});
    void window.bridge.getCodeGraphComparison().then(value => setComparison(value.repositories)).catch(() => {});
  }, [current, report]);

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
    if (current !== 'github' && current !== 'ws-token') return;
    loadAccount();
    void window.bridge.getQuietHours().then(setQuietHours).catch(() => {});
  }, [current, loadAccount]);

  const workspace = workspaces.find(candidate => candidate.id === pickedWorkspace) ?? workspaces[0] ?? null;

  const loadWorkspaceSettings = useCallback(() => {
    if (workspace === null) return;
    window.bridge.getWorkspaceSettings(workspace.id).then(setWsSettings).catch(report);
  }, [report, workspace]);

  useEffect(() => {
    if (!isWorkspaceSection(current) || workspace === null) return;
    loadWorkspaceSettings();
  }, [current, loadWorkspaceSettings, workspace]);

  const visible = useMemo(
    () => SETTINGS_ENTRIES.filter(entry => matchesSearch(entry, query)),
    [query],
  );

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
      .then(loadWorkspaceSettings)
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
    <div className="setw">
      <aside className="setw-side">
        <button type="button" className="setw-back" onClick={onBack}>
          <ChevronLeft size={15} strokeWidth={2} color="#8e8e93" />
          <span>Back to app</span>
        </button>
        <div className="setw-search">
          <div className="setw-search-field">
            <Search size={13} strokeWidth={2.2} color="#a1a1a6" />
            <input
              value={query}
              placeholder="Search settings"
              aria-label="Search settings"
              onChange={event => setQuery(event.target.value)}
            />
          </div>
        </div>
        <nav className="setw-nav" aria-label="Settings categories">
          {SETTINGS_GROUPS.map(group => {
            const entries = visible.filter(entry => entry.group === group);
            if (entries.length === 0) return null;
            return (
              <div key={group}>
                <div className="setw-group">{group}</div>
                {group === 'Workspace' && (
                  <div className="setw-ws">
                    <button
                      type="button"
                      className="setw-ws-button"
                      aria-label="Workspace being edited"
                      aria-expanded={wsMenuOpen}
                      disabled={workspaces.length === 0}
                      onClick={() => setWsMenuOpen(open => !open)}
                    >
                      <span className="setw-avatar" aria-hidden="true">{(workspace?.name ?? '?').slice(0, 1)}</span>
                      <span className="setw-ws-name">
                        {workspace === null ? 'No workspace' : `${workspace.selectedOwner}/${workspace.selectedRepo}`}
                      </span>
                      <ChevronDown size={12} strokeWidth={2.4} color="#8e8e93" />
                    </button>
                    {wsMenuOpen && (
                      <div className="setw-menu is-left" role="listbox" style={{ top: 34, width: 252 }}>
                        {workspaces.map(candidate => (
                          <button
                            type="button"
                            key={candidate.id}
                            role="option"
                            aria-selected={candidate.id === workspace?.id}
                            className="setw-menu-item"
                            onClick={() => {
                              // Switching the workspace must not leave the page the user is on.
                              setPickedWorkspace(candidate.id);
                              setWsSettings(null);
                              setWsMenuOpen(false);
                              if (isWorkspaceSection(current)) go(current, candidate.id);
                            }}
                          >
                            <span className="setw-avatar" aria-hidden="true">{candidate.name.slice(0, 1)}</span>
                            <span className="setw-menu-label">{candidate.selectedOwner}/{candidate.selectedRepo}</span>
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                )}
                {entries.map(entry => {
                  const Icon = ICONS[entry.section];
                  return (
                    <button
                      type="button"
                      key={entry.section}
                      className={`setw-item${entry.section === current ? ' is-current' : ''}`}
                      aria-current={entry.section === current ? 'page' : undefined}
                      onClick={() => go(entry.section)}
                    >
                      <Icon size={16} strokeWidth={1.9} color="#6e6e73" />
                      <span>{entry.label}</span>
                    </button>
                  );
                })}
              </div>
            );
          })}
          {visible.length === 0 && <div className="setw-empty">Nothing matches “{query}”.</div>}
        </nav>
      </aside>

      <main className="setw-main">
        <div className="setw-scroll">
          {current === 'general' && preferences !== null && (
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
          {current === 'appearance' && preferences !== null && (
            <AppearancePage preferences={preferences} error={error} onChange={savePreferences} />
          )}
          {current === 'models' && models !== null && (
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
          {current === 'skills' && (
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
            />
          )}
          {current === 'code-graph' && graphMode !== null && (
            <CodeGraphPage
              mode={graphMode}
              baseline={baseline}
              comparison={comparison}
              error={error}
              onMode={mode => {
                setGraphMode(mode);
                window.bridge.saveCodeGraphSetting({ mode }).then(value => setGraphMode(value.mode)).catch(report);
              }}
            />
          )}
          {current === 'github' && preferences !== null && (
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

          {isWorkspaceSection(current) && workspace === null && (
            <div className="setw-page">
              <h1>Workspace</h1>
              <div className="setw-row-empty">Watch a repository to get workspace settings.</div>
            </div>
          )}
          {current === 'ws-repository' && workspace !== null && (
            <WorkspaceRepositoryPage
              workspace={workspace}
              settings={wsSettings}
              error={error}
              onAddRemote={(name, url) => {
                window.bridge.addWorkspaceRemote(workspace.id, name, url).then(loadWorkspaceSettings).catch(report);
              }}
              onRemoveRemote={(remote: WorkspaceRemoteDto) => {
                // Unlinking an upstream is a different operation from deleting a remote, even
                // though both rows say Remove.
                const removed: Promise<unknown> = remote.role === 'UPSTREAM'
                  ? window.bridge.setWorkspaceUpstream(workspace.id, null)
                  : window.bridge.removeWorkspaceRemote(workspace.id, remote.name);
                removed.then(loadWorkspaceSettings).catch(report);
              }}
              onSaveTarget={branch => {
                window.bridge.setWorkspaceCommitsTarget(workspace.id, branch).then(loadWorkspaceSettings).catch(report);
              }}
              onResetTarget={() => {
                window.bridge.clearWorkspaceCommitsTarget(workspace.id).then(loadWorkspaceSettings).catch(report);
              }}
            />
          )}
          {current === 'ws-agents' && workspace !== null && (
            <WorkspaceAgentsPage
              workspace={workspace}
              settings={wsSettings}
              models={models}
              checking={checking}
              checkedAt={checkedAt}
              error={error}
              onChoose={chooseWorkspaceModel}
              onUseGlobal={type => {
                window.bridge.clearWorkspaceAgentModel(workspace.id, type).then(loadWorkspaceSettings).catch(report);
              }}
              onCheck={loadModels}
            />
          )}
          {current === 'ws-token' && workspace !== null && (
            <WorkspaceTokenPage
              workspace={workspace}
              settings={wsSettings}
              login={login}
              copied={copied}
              error={error}
              onSave={token => {
                window.bridge.saveWorkspaceGitHubToken(workspace.id, token).then(loadWorkspaceSettings).catch(report);
              }}
              onDelete={() => {
                window.bridge.deleteWorkspaceGitHubToken(workspace.id).then(loadWorkspaceSettings).catch(report);
              }}
              onCopy={() => copyToken(window.bridge.copyWorkspaceGitHubToken(workspace.id))}
            />
          )}
          {current === 'ws-advanced' && workspace !== null && (
            <WorkspaceAdvancedPage
              workspace={workspace}
              busy={wsBusy}
              error={error}
              onUnwatch={() => { setError(null); setUnwatching(true); }}
              onDelete={() => setDeleting(true)}
            />
          )}
        </div>
      </main>

      {unwatching && workspace !== null && (
        <UnwatchWorkspaceDialog
          workspace={workspace}
          busy={wsBusy}
          error={error}
          onConfirm={() => {
            setWsBusy(true);
            window.bridge.removeWorkspace(workspace.id)
              .then(() => {
                setUnwatching(false);
                setPickedWorkspace(null);
                return window.bridge.listWorkspaces().then(setWorkspaces);
              })
              .catch(report)
              .finally(() => setWsBusy(false));
          }}
          onCancel={() => setUnwatching(false)}
        />
      )}
      {deleting && workspace !== null && (
        <DeleteWorkspaceDialog
          workspace={workspace}
          onDeleted={() => {
            setDeleting(false);
            setPickedWorkspace(null);
            void window.bridge.listWorkspaces().then(setWorkspaces);
          }}
          onCancel={() => setDeleting(false)}
        />
      )}
    </div>
  );
}

export { settingsLocationFromRoute };
