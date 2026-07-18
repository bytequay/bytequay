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
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import type { WorkspaceCardDto, WorkModelOptionsDto } from '../types';
import {
  workspaceApi,
  type WorkspaceCreationDto,
  type WorkspaceMemoryDto,
  type WorkspaceRepositoryDto,
  type WorkspaceSettingsDto,
} from './workspaceApi';

export type WorkspaceSettingsSection =
  | 'general' | 'agents' | 'notifications' | 'sync' | 'memory' | 'danger';

type StoredSettings = {
  planModel: string;
  devModel: string;
  reviewModel: string;
  ciFixModel: string;
  perSessionCap: number;
  dailyCap: number;
  pauseAtCap: boolean;
  syncSeconds: number;
  brainBudget: number;
  distillMinutes: number;
  notifyQuestions: boolean;
  notifyReviews: boolean;
  notifyCi: boolean;
  notifyCompletions: boolean;
};

const defaults: StoredSettings = {
  planModel: 'cli:claude-code',
  devModel: 'cli:claude-code',
  reviewModel: 'cli:claude-code',
  ciFixModel: 'cli:codex',
  perSessionCap: 1,
  dailyCap: 10,
  pauseAtCap: true,
  syncSeconds: 60,
  brainBudget: 8000,
  distillMinutes: 30,
  notifyQuestions: true,
  notifyReviews: true,
  notifyCi: true,
  notifyCompletions: false,
};

export default function WorkspaceSettingsPage({
  workspace, workspaceId, section = 'general', onSelectSection, onOpenMemory,
}: {
  workspace: WorkspaceCardDto;
  workspaceId: string;
  section?: WorkspaceSettingsSection;
  onSelectSection?: (section: WorkspaceSettingsSection) => void;
  onOpenMemory?: () => void;
}) {
  const [settings, setSettings] = useState<StoredSettings>(defaults);
  const [displayName, setDisplayName] = useState(workspace.name);
  const [nameDraft, setNameDraft] = useState(workspace.name);
  const [repo, setRepo] = useState<WorkspaceRepositoryDto | null>(null);
  const [modelOptions, setModelOptions] = useState<WorkModelOptionsDto | null>(null);
  const [memory, setMemory] = useState<WorkspaceMemoryDto | null>(null);
  const [saved, setSaved] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [refreshingModels, setRefreshingModels] = useState(false);
  const [reclone, setReclone] = useState<WorkspaceCreationDto | null>(null);
  const [actionMessage, setActionMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setDisplayName(workspace.name);
    setNameDraft(workspace.name);
    void Promise.all([
      workspaceApi.repository(workspaceId),
      workspaceApi.settings(workspaceId),
      workspaceApi.workModelOptions(),
    ])
      .then(([repository, persisted, options]) => {
        if (cancelled) return;
        setRepo(repository);
        setSettings(coerceSettingsChoices(fromDto(persisted), choicesFrom(options)));
        setModelOptions(options);
      })
      .catch(reason => {
        if (!cancelled) setActionMessage(reason instanceof Error ? reason.message : String(reason));
      });
    return () => { cancelled = true; };
  }, [workspace.name, workspaceId]);

  useEffect(() => {
    if (section !== 'memory' || memory !== null) return;
    let cancelled = false;
    void workspaceApi.memory(workspaceId)
      .then(next => { if (!cancelled) setMemory(next); })
      .catch(reason => {
        if (!cancelled) setActionMessage(reason instanceof Error ? reason.message : String(reason));
      });
    return () => { cancelled = true; };
  }, [memory, section, workspaceId]);

  useEffect(() => {
    if (reclone === null || reclone.state === 'ready' || reclone.state === 'failed') return;
    const timer = window.setTimeout(() => {
      void workspaceApi.creation(reclone.id)
        .then(operation => {
          setReclone(operation);
          if (operation.state === 'ready') {
            setActionMessage('Re-clone complete. The previous checkout was retained as a backup.');
            void workspaceApi.repository(workspaceId).then(setRepo);
          }
          else if (operation.state === 'failed') {
            setActionMessage(operation.errorMessage ?? 'Re-clone failed.');
          }
        })
        .catch(reason => setActionMessage(reason instanceof Error ? reason.message : String(reason)));
    }, 700);
    return () => window.clearTimeout(timer);
  }, [reclone, workspaceId]);

  const update = <K extends keyof StoredSettings>(key: K, value: StoredSettings[K]) => {
    setSettings(current => ({ ...current, [key]: value }));
    setSaved(false);
  };
  const save = async () => {
    setActionMessage(null);
    try {
      if (nameDraft.trim() !== displayName) {
        const renamed = await workspaceApi.rename(workspaceId, nameDraft);
        setDisplayName(renamed.name);
        setNameDraft(renamed.name);
      }
      const persisted = await workspaceApi.saveSettings(workspaceId, toDto(settings));
      setSettings(fromDto(persisted));
      setSaved(true);
      window.setTimeout(() => setSaved(false), 1400);
    }
    catch (reason) {
      setActionMessage(reason instanceof Error ? reason.message : String(reason));
    }
  };
  const repoName = repo?.fullName ?? workspace.name;
  const agentChoices = useMemo(() => choicesFrom(modelOptions), [modelOptions]);
  const refreshModelOptions = async () => {
    setRefreshingModels(true);
    try {
      const nextOptions = await workspaceApi.refreshWorkModelOptions();
      setModelOptions(nextOptions);
      setSettings(current => coerceSettingsChoices(current, choicesFrom(nextOptions)));
    }
    catch (reason) {
      setActionMessage(reason instanceof Error ? reason.message : String(reason));
    }
    finally {
      setRefreshingModels(false);
    }
  };
  const visualFrame = typeof document === 'undefined'
    ? undefined
    : document.documentElement.dataset.workspaceVisualFrame;

  return (
    <section className="wu-page wu-settings">
      <header className="wu-page-header">
        <div className="wu-page-heading"><h1>Settings</h1><span>{displayName}</span></div>
      </header>
      <div className="wu-settings__layout">
        <nav className="wu-settings__nav">
          {([
            ['general', 'General'],
            ['agents', 'Agents'],
            ['notifications', 'Notifications'],
            ['sync', 'Sync'],
            ['memory', 'Memory'],
            ['danger', 'Danger zone'],
          ] as const).map(([key, label]) => (
            <div key={key} className={section === key ? 'active' : ''}
              role="button" tabIndex={0}
              onClick={() => onSelectSection?.(key)}
              onKeyDown={event => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  onSelectSection?.(key);
                }
              }}>{label}</div>
          ))}
        </nav>
        <main className="wu-settings__content">
          {section === 'general' && (
            <>
              <SettingsCard title="Repository">
                <SettingRow label="Workspace name" detail="Shown in the sidebar, switcher, and workspace cards.">
                  <label className="wu-text-input">
                    <input value={nameDraft} onChange={event => {
                      setNameDraft(event.target.value);
                      setSaved(false);
                    }} />
                  </label>
                </SettingRow>
                <SettingRow label="GitHub repository" detail="The sole repository owned by this workspace.">
                  <code>{repoName}</code>
                </SettingRow>
                <SettingRow label="Default branch" detail="Used for comparisons, new tasks, and cherry-picks.">
                  <code>{repo?.defaultBaseBranch ?? 'auto'}</code>
                </SettingRow>
              </SettingsCard>
              <SettingsCard title="Workspace">
                <SettingRow label="Workspace ID" detail="Stable internal identifier.">
                  <code>{workspaceId}</code>
                </SettingRow>
              </SettingsCard>
            </>
          )}
          {section === 'agents' && (
            <>
              <SettingsCard title="Defaults per session kind" subtitle="threads can override per run">
                <ModelRow label="Deep reasoning for specs & plans" tone="plan" value={settings.planModel}
                  choices={agentChoices}
                  onRefresh={() => { void refreshModelOptions(); }}
                  refreshing={refreshingModels}
                  onChange={value => update('planModel', value)} />
                <ModelRow label="Code writing & tests" tone="dev" value={settings.devModel}
                  choices={agentChoices}
                  onRefresh={() => { void refreshModelOptions(); }}
                  refreshing={refreshingModels}
                  onChange={value => update('devModel', value)} />
                <ModelRow label="PR review rounds" tone="review" value={settings.reviewModel}
                  choices={agentChoices}
                  onRefresh={() => { void refreshModelOptions(); }}
                  refreshing={refreshingModels}
                  onChange={value => update('reviewModel', value)} />
                <ModelRow label="Cheap loops on red builds" tone="ci-fix" value={settings.ciFixModel}
                  choices={agentChoices}
                  onRefresh={() => { void refreshModelOptions(); }}
                  refreshing={refreshingModels}
                  onChange={value => update('ciFixModel', value)} />
              </SettingsCard>
              <SettingsCard title="Budget caps">
                <NumberRow label="Per session" prefix="$" value={settings.perSessionCap}
                  onChange={value => update('perSessionCap', value)} />
                <NumberRow label="Per day, this workspace" prefix="$" value={settings.dailyCap}
                  onChange={value => update('dailyCap', value)} />
                <ToggleRow label="Pause at cap" detail="Sessions pause and ask instead of stopping dead"
                  checked={settings.pauseAtCap} onChange={value => update('pauseAtCap', value)} />
              </SettingsCard>
            </>
          )}
          {section === 'notifications' && (
            <SettingsCard title="Notify me about">
              <ToggleRow label="Agent questions" detail="Questions always remain actionable."
                checked={settings.notifyQuestions} disabled onChange={() => {}} />
              <ToggleRow label="Review and publish gates" detail="Approval is always explicit."
                checked={settings.notifyReviews} disabled onChange={() => {}} />
              <ToggleRow label="CI failures" detail="Only failures tied to this workspace."
                checked={settings.notifyCi} onChange={value => update('notifyCi', value)} />
              <ToggleRow label="Successful completions" detail="Quiet by default."
                checked={settings.notifyCompletions} onChange={value => update('notifyCompletions', value)} />
            </SettingsCard>
          )}
          {section === 'sync' && (
            <SettingsCard title="Repository sync">
              <NumberRow label="Refresh cadence" suffix="seconds" value={settings.syncSeconds}
                onChange={value => update('syncSeconds', value)} />
              <SettingRow label="Fetch now" detail="Fetches and prunes. It never pulls or pushes.">
                <button type="button" className="wu-icon-button" disabled={syncing}
                  onClick={() => {
                    setSyncing(true);
                    void window.bridge.workspaceApi({
                      path: `/api/workspaces/${encodeURIComponent(workspaceId)}/refresh`,
                      method: 'POST',
                    }).finally(() => setSyncing(false));
                  }}>{syncing ? 'Refreshing…' : 'Refresh'}</button>
              </SettingRow>
            </SettingsCard>
          )}
          {section === 'memory' && (
            <SettingsCard title="Brain and distillation">
              <NumberRow label="Brain character budget" suffix="characters" value={settings.brainBudget}
                onChange={value => update('brainBudget', value)} />
              <NumberRow label="Distill cadence" suffix="minutes" value={settings.distillMinutes}
                onChange={value => update('distillMinutes', value)} />
              <SettingRow label="Context audience" detail="Matching KB entries may be read by all session kinds.">
                <span className="wu-audience-chips"><i>plan</i><i>dev</i><i>review</i><i>ci-fix</i></span>
              </SettingRow>
              <SettingRow label="Workspace memory" detail={memory === null
                ? 'Loading memory summary…'
                : `${memory.characters.toLocaleString()} / ${memory.characterBudget.toLocaleString()} characters · ${memory.blocks.length} brain blocks · ${memory.knowledge.length} KB entries`}>
                <button type="button" className="wu-icon-button" onClick={onOpenMemory}>
                  View memory
                </button>
              </SettingRow>
            </SettingsCard>
          )}
          {section === 'danger' && (
            <SettingsCard title="Danger zone" danger>
              <DangerRow title="Pause all sessions"
                detail="Asks every running session in this workspace to pause at its next safe boundary."
                action="Pause all" onClick={() => {
                  void workspaceApi.pauseAll(workspaceId)
                    .then(result => setActionMessage(`${result.paused} session${result.paused === 1 ? '' : 's'} paused.`))
                    .catch(reason => setActionMessage(reason instanceof Error ? reason.message : String(reason)));
                }} />
              <DangerRow title="Re-clone workspace"
                detail="Clones into a new directory, verifies it, then swaps atomically and retains the old clone."
                action={reclone !== null && reclone.state !== 'ready' && reclone.state !== 'failed'
                  ? `${reclone.stageMessage ?? 'Re-cloning'} · ${reclone.progressCurrent}/${reclone.progressTotal}`
                  : reclone?.state === 'failed' ? 'Retry re-clone' : 'Re-clone…'}
                disabled={reclone !== null && reclone.state !== 'ready' && reclone.state !== 'failed'}
                onClick={() => {
                  setActionMessage(null);
                  const request = reclone?.state === 'failed'
                    ? workspaceApi.retryCreation(reclone.id)
                    : workspaceApi.reclone(workspaceId);
                  void request
                    .then(setReclone)
                    .catch(reason => setActionMessage(reason instanceof Error ? reason.message : String(reason)));
                }} />
              <DangerRow title="Detach workspace"
                detail="Stops watching without deleting the clone, trunks, tasks, or history."
                action="Detach…" destructive onClick={() => {
                  void workspaceApi.detach(workspaceId)
                    .then(() => setActionMessage('Workspace detached. Its clone and history were kept.'))
                    .catch(reason => setActionMessage(reason instanceof Error ? reason.message : String(reason)));
                }} />
            </SettingsCard>
          )}
          {visualFrame === '6c' ? (
            <span className="wu-settings__source-note">
              Notifications section carries the mute rules from <a>3j</a> · Sync = cadence,
              watched branches, PR/issue scope · Memory = char budget, distill interval,
              KB permissions · Danger zone = pause all agents, re-clone, detach.
            </span>
          ) : (
            <footer className="wu-settings__save">
              <span>{actionMessage ?? (saved ? 'Saved' : 'Changes stay local to this workspace.')}</span>
              <button type="button" className="wu-primary-button" onClick={() => { void save(); }}>
                {saved ? 'Saved ✓' : 'Save changes'}
              </button>
            </footer>
          )}
        </main>
      </div>
    </section>
  );
}

function SettingsCard({ title, subtitle, children, danger = false }: {
  title: string;
  subtitle?: string;
  children: ReactNode;
  danger?: boolean;
}) {
  return (
    <section className={`wu-settings-card${danger ? ' danger' : ''}`}>
      <h2 className={subtitle === undefined ? undefined : 'with-subtitle'}>
        <span>{title}</span>
        {subtitle !== undefined && <small>{subtitle}</small>}
      </h2>
      <div>{children}</div>
    </section>
  );
}

function SettingRow({ label, detail, children }: {
  label: string;
  detail?: string;
  children: ReactNode;
}) {
  return (
    <div className="wu-setting-row">
      <span><strong>{label}</strong>{detail && <small>{detail}</small>}</span>
      <div>{children}</div>
    </div>
  );
}

type AgentChoice = {
  value: string;
  label: string;
  detail: string;
  disabled?: boolean;
};

function ModelRow({ label, tone, value, choices, refreshing, onRefresh, onChange }: {
  label: string;
  tone: string;
  value: string;
  choices: AgentChoice[];
  refreshing: boolean;
  onRefresh: () => void;
  onChange: (value: string) => void;
}) {
  const selected = selectableChoice(value, choices);
  return (
    <div className="wu-setting-row wu-model-row">
      <span className={`wu-kind-chip ${tone}`}>{tone === 'ci-fix' ? 'ci fix' : tone}</span>
      <span className="wu-model-row__description">{label}</span>
      <label className="wu-model-picker">
        <span className={choiceClass(selected)}>{choiceGlyph(selected)}</span>
        <select value={selected} onChange={event => onChange(event.target.value)}>
          {choices.map(choice => (
            <option key={choice.value} value={choice.value} disabled={choice.disabled}>
              {choice.label}{choice.disabled ? ' (not available)' : choice.detail.length === 0 ? '' : ` · ${choice.detail}`}
            </option>
          ))}
        </select>
        <svg viewBox="0 0 24 24" aria-hidden><path d="m6 9 6 6 6-6" /></svg>
      </label>
      <button type="button" className="wu-model-refresh" disabled={refreshing} onClick={onRefresh}>
        {refreshing ? 'Checking…' : 'Check'}
      </button>
    </div>
  );
}

function choicesFrom(options: WorkModelOptionsDto | null): AgentChoice[] {
  const choices: AgentChoice[] = [
    { value: 'cli:claude-code', label: 'Claude CLI', detail: cliDetail(options, 'claude-code'), disabled: cliDisabled(options, 'claude-code') },
    { value: 'cli:codex', label: 'Codex CLI', detail: cliDetail(options, 'codex'), disabled: cliDisabled(options, 'codex') },
  ];
  options?.apiProviders.forEach(provider => {
    provider.accounts.forEach(account => {
      choices.push({
        value: `api:${provider.id}:${account.name}`,
        label: 'API',
        detail: `${provider.displayName} · ${account.name}${account.isDefault ? ' · default' : ''}`,
      });
    });
  });
  choices.push({ value: 'local', label: 'Local', detail: 'local runtime' });
  return choices;
}

function selectableChoice(value: string, choices: AgentChoice[]): string {
  const normalized = choices.some(choice => choice.value === value) ? value : normalizeChoice(value);
  const current = choices.find(choice => choice.value === normalized);
  if (current !== undefined && !current.disabled) return normalized;
  return choices.find(choice => !choice.disabled)?.value ?? normalized;
}

function coerceSettingsChoices(value: StoredSettings, choices: AgentChoice[]): StoredSettings {
  return {
    ...value,
    planModel: selectableChoice(value.planModel, choices),
    devModel: selectableChoice(value.devModel, choices),
    reviewModel: selectableChoice(value.reviewModel, choices),
    ciFixModel: selectableChoice(value.ciFixModel, choices),
  };
}

function cliDisabled(options: WorkModelOptionsDto | null, id: string): boolean {
  const agent = options?.cliAgents.find(row => row.id === id);
  return agent === undefined ? true : !agent.installed;
}

function cliDetail(options: WorkModelOptionsDto | null, id: string): string {
  const agent = options?.cliAgents.find(row => row.id === id);
  if (agent === undefined) return 'checking…';
  if (!agent.installed) return 'not installed';
  return agent.authed ? 'available' : 'installed';
}

function normalizeChoice(value: string): string {
  if (value.startsWith('cli:') || value.startsWith('api:') || value === 'local') return value;
  const lower = value.toLowerCase();
  if (lower.includes('codex') || lower.includes('gpt')) return 'cli:codex';
  if (lower.includes('claude')) return 'cli:claude-code';
  return 'local';
}

function choiceClass(value: string): string {
  if (value.startsWith('cli:claude')) return 'claude';
  if (value.startsWith('cli:codex') || value.startsWith('api:openai')) return 'gpt';
  if (value.startsWith('api:')) return 'api';
  return 'local';
}

function choiceGlyph(value: string): string {
  if (value.startsWith('cli:claude')) return 'C';
  if (value.startsWith('cli:codex')) return 'X';
  if (value.startsWith('api:')) return 'A';
  return 'L';
}

function NumberRow({ label, value, prefix, suffix, onChange }: {
  label: string;
  value: number;
  prefix?: string;
  suffix?: string;
  onChange: (value: number) => void;
}) {
  const [draft, setDraft] = useState(prefix === '$' ? value.toFixed(2) : String(value));
  useEffect(() => {
    setDraft(prefix === '$' ? value.toFixed(2) : String(value));
  }, [prefix, value]);
  return (
    <SettingRow label={label}>
      <label className="wu-number-input">
        {prefix && <span>{prefix}</span>}
        <input type="text" inputMode="decimal" value={draft}
          onChange={event => {
            setDraft(event.target.value);
            const parsed = Number(event.target.value);
            if (Number.isFinite(parsed) && parsed >= 0) onChange(parsed);
          }}
          onBlur={() => setDraft(prefix === '$' ? value.toFixed(2) : String(value))} />
        {suffix && <span>{suffix}</span>}
      </label>
    </SettingRow>
  );
}

function ToggleRow({ label, detail, checked, onChange, disabled = false }: {
  label: string;
  detail: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <SettingRow label={label} detail={detail}>
      <button type="button" role="switch" aria-checked={checked}
        disabled={disabled}
        className={`wu-switch${checked ? ' on' : ''}`} onClick={() => onChange(!checked)}>
        <i />
      </button>
    </SettingRow>
  );
}

function DangerRow({ title, detail, action, destructive = false, disabled = false, onClick }: {
  title: string;
  detail: string;
  action: string;
  destructive?: boolean;
  disabled?: boolean;
  onClick?: () => void;
}) {
  return (
    <SettingRow label={title} detail={detail}>
      <button type="button" className={`wu-icon-button${destructive ? ' destructive' : ''}`}
        disabled={disabled} onClick={onClick}>
        {action}
      </button>
    </SettingRow>
  );
}

function fromDto(value: WorkspaceSettingsDto): StoredSettings {
  return {
    planModel: normalizeChoice(value.providers.plan ?? defaults.planModel),
    devModel: normalizeChoice(value.providers.dev ?? defaults.devModel),
    reviewModel: normalizeChoice(value.providers.review ?? defaults.reviewModel),
    ciFixModel: normalizeChoice(value.providers['ci-fix'] ?? defaults.ciFixModel),
    perSessionCap: value.sessionCapUsd,
    dailyCap: value.dailyCapUsd,
    pauseAtCap: value.pauseAtCap,
    syncSeconds: value.syncSeconds,
    brainBudget: value.brainBudgetChars,
    distillMinutes: value.distillMinutes,
    notifyQuestions: true,
    notifyReviews: true,
    notifyCi: value.notifyCi,
    notifyCompletions: value.notifyCompletions,
  };
}

function toDto(value: StoredSettings): WorkspaceSettingsDto {
  return {
    sessionCapUsd: value.perSessionCap,
    dailyCapUsd: value.dailyCap,
    pauseAtCap: value.pauseAtCap,
    syncSeconds: value.syncSeconds,
    brainBudgetChars: value.brainBudget,
    distillMinutes: value.distillMinutes,
    kbAudiences: ['plan', 'dev', 'review', 'ci-fix'],
    providers: {
      plan: value.planModel,
      dev: value.devModel,
      review: value.reviewModel,
      'ci-fix': value.ciFixModel,
    },
    notifyCi: value.notifyCi,
    notifyCompletions: value.notifyCompletions,
  };
}
