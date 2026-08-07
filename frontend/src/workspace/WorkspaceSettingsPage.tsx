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
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { Ds4StateDto, WorkspaceCardDto, WorkModelOptionsDto } from '../types';
import WorkspaceRelationsSettings from './WorkspaceRelationsSettings';
import {
  workspaceApi,
  type WorkspaceAutomationStatusDto,
  type WorkspaceCreationDto,
  type WorkspaceMemoryDto,
  type WorkspaceRepositoryDto,
  type WorkspaceSettingsDto,
} from './workspaceApi';
import {
  type AgentChoice,
  choiceClass,
  choiceGlyph,
  choicesFrom,
  choiceText,
  normalizeChoice,
  selectableChoice,
} from './agentChoices';

const AUTOMATION_REFRESH_MS = 30_000;

export type WorkspaceSettingsSection =
  | 'general' | 'relations' | 'agents' | 'notifications' | 'sync' | 'automation' | 'memory' | 'danger';

/** A session-kind row left empty runs on the workspace default. */
const INHERIT = '';

type StoredSettings = {
  /** The engine every session in this workspace runs on unless the
   *  session kind below overrides it. Threads, tasks, and stages cannot
   *  change it — they only dial reasoning effort. */
  defaultModel: string;
  /** Per-session-kind overrides. Empty string = inherit defaultModel. */
  planModel: string;
  devModel: string;
  reviewModel: string;
  ciFixModel: string;
  perSessionCap: number;
  dailyCap: number;
  pauseAtCap: boolean;
  maxRunningTasks: number | null;
  syncSeconds: number;
  brainBudget: number;
  distillMinutes: number;
  notifyQuestions: boolean;
  notifyReviews: boolean;
  notifyCi: boolean;
  notifyCompletions: boolean;
  qualityScanEnabled: boolean;
  remoteIssueIntakeEnabled: boolean;
};

const defaults: StoredSettings = {
  defaultModel: 'cli:claude-code',
  planModel: INHERIT,
  devModel: INHERIT,
  reviewModel: INHERIT,
  ciFixModel: INHERIT,
  perSessionCap: 100,
  dailyCap: 500,
  pauseAtCap: true,
  maxRunningTasks: null,
  syncSeconds: 60,
  brainBudget: 8000,
  distillMinutes: 30,
  notifyQuestions: true,
  notifyReviews: true,
  notifyCi: true,
  notifyCompletions: false,
  qualityScanEnabled: false,
  remoteIssueIntakeEnabled: false,
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
  const [localAiState, setLocalAiState] = useState<Ds4StateDto | null>(null);
  const [memory, setMemory] = useState<WorkspaceMemoryDto | null>(null);
  const [automation, setAutomation] = useState<WorkspaceAutomationStatusDto | null>(null);
  const [saved, setSaved] = useState(false);
  const [maxRunningTasksDraft, setMaxRunningTasksDraft] = useState('');
  const [syncing, setSyncing] = useState(false);
  const [refreshingModels, setRefreshingModels] = useState(false);
  const [reclone, setReclone] = useState<WorkspaceCreationDto | null>(null);
  const [actionMessage, setActionMessage] = useState<string | null>(null);
  const activeWorkspaceId = useRef(workspaceId);
  const maxRunningTasksDraftRef = useRef('');
  const settingsEditRevision = useRef(0);
  const parsedMaxRunningTasks = parseNullablePositiveInteger(maxRunningTasksDraft);
  const maxRunningTasksValid = parsedMaxRunningTasks !== undefined;

  useEffect(() => {
    activeWorkspaceId.current = workspaceId;
    settingsEditRevision.current += 1;
    maxRunningTasksDraftRef.current = '';
    setMaxRunningTasksDraft('');
    setSaved(false);
    setActionMessage(null);
  }, [workspaceId]);

  useEffect(() => {
    let cancelled = false;
    setDisplayName(workspace.name);
    setNameDraft(workspace.name);
    void Promise.all([
      workspaceApi.repository(workspaceId),
      workspaceApi.settings(workspaceId),
      workspaceApi.workModelOptions(),
      window.bridge.getDs4Status(),
    ])
      .then(([repository, persisted, options, localAi]) => {
        if (cancelled) return;
        setRepo(repository);
        const nextSettings = coerceSettingsChoices(
          fromDto(persisted),
          choicesFrom(options, localAi.state),
        );
        const nextMaxRunningTasksDraft = formatNullableInteger(nextSettings.maxRunningTasks);
        maxRunningTasksDraftRef.current = nextMaxRunningTasksDraft;
        setMaxRunningTasksDraft(nextMaxRunningTasksDraft);
        setSettings(nextSettings);
        setModelOptions(options);
        setLocalAiState(localAi.state);
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
    if (section !== 'automation') return;
    let cancelled = false;
    setAutomation(null);
    void workspaceApi.automation(workspaceId)
      .then(next => { if (!cancelled) setAutomation(next); })
      .catch(reason => {
        if (!cancelled) setActionMessage(reason instanceof Error ? reason.message : String(reason));
      });
    return () => { cancelled = true; };
  }, [section, workspaceId]);

  useEffect(() => {
    if (section !== 'automation') return;
    let cancelled = false;
    const timer = window.setInterval(() => {
      void workspaceApi.automation(workspaceId)
        .then(next => { if (!cancelled) setAutomation(next); })
        .catch(() => { /* keep the last known health snapshot */ });
    }, AUTOMATION_REFRESH_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [section, workspaceId]);

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
    settingsEditRevision.current += 1;
    setSettings(current => ({ ...current, [key]: value }));
    setSaved(false);
  };
  const updateMaxRunningTasks = (draft: string) => {
    settingsEditRevision.current += 1;
    maxRunningTasksDraftRef.current = draft;
    setMaxRunningTasksDraft(draft);
    setSaved(false);
    const parsed = parseNullablePositiveInteger(draft);
    if (parsed !== undefined) {
      setSettings(current => ({ ...current, maxRunningTasks: parsed }));
    }
  };
  const save = async () => {
    const editRevisionAtSave = ++settingsEditRevision.current;
    setSaved(false);
    const workspaceIdAtSave = workspaceId;
    const maxRunningTasksDraftAtSave = maxRunningTasksDraftRef.current;
    const saveIsCurrent = () => activeWorkspaceId.current === workspaceIdAtSave
      && settingsEditRevision.current === editRevisionAtSave;
    if (parseNullablePositiveInteger(maxRunningTasksDraftAtSave) === undefined) {
      setSaved(false);
      return;
    }
    setActionMessage(null);
    try {
      if (nameDraft.trim() !== displayName) {
        const renamed = await workspaceApi.rename(workspaceIdAtSave, nameDraft);
        if (saveIsCurrent()) {
          setDisplayName(renamed.name);
          setNameDraft(renamed.name);
        }
        else return;
      }
      const persisted = await workspaceApi.saveSettings(workspaceIdAtSave, toDto(settings));
      const saveCurrent = saveIsCurrent();
      const maxRunningTasksUnchanged = maxRunningTasksDraftRef.current === maxRunningTasksDraftAtSave;
      if (saveCurrent && maxRunningTasksUnchanged) {
        const nextSettings = fromDto(persisted);
        const nextMaxRunningTasksDraft = formatNullableInteger(nextSettings.maxRunningTasks);
        maxRunningTasksDraftRef.current = nextMaxRunningTasksDraft;
        setMaxRunningTasksDraft(nextMaxRunningTasksDraft);
        setSettings(nextSettings);
      }
      if (saveCurrent && section === 'automation') {
        void workspaceApi.automation(workspaceIdAtSave).then(next => {
          if (saveIsCurrent()) setAutomation(next);
        }).catch(() => {});
      }
      if (saveCurrent && maxRunningTasksUnchanged) {
        setSaved(true);
        window.setTimeout(() => {
          if (saveIsCurrent()) setSaved(false);
        }, 1400);
      }
    }
    catch (reason) {
      if (saveIsCurrent()) {
        setActionMessage(reason instanceof Error ? reason.message : String(reason));
      }
    }
  };
  const repoName = repo?.fullName ?? workspace.name;
  const agentChoices = useMemo(() => choicesFrom(modelOptions, localAiState), [localAiState, modelOptions]);
  const refreshModelOptions = async () => {
    setRefreshingModels(true);
    try {
      const [nextOptions, localAi] = await Promise.all([
        workspaceApi.refreshWorkModelOptions(),
        window.bridge.getDs4Status(),
      ]);
      settingsEditRevision.current += 1;
      setSaved(false);
      setModelOptions(nextOptions);
      setLocalAiState(localAi.state);
      setSettings(current => coerceSettingsChoices(current, choicesFrom(nextOptions, localAi.state)));
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
            ['relations', 'Relations'],
            ['agents', 'Agents'],
            ['notifications', 'Notifications'],
            ['sync', 'Sync'],
            ['automation', 'Automation'],
            ['memory', 'Memory'],
            ['danger', 'Danger zone'],
          ] as const).map(([key, label]) => {
            const disabled = key === 'notifications' || key === 'memory';
            return (
              <div key={key} className={`${section === key ? 'active' : ''}${disabled ? ' disabled' : ''}`}
                role="button" aria-disabled={disabled} tabIndex={disabled ? -1 : 0}
                title={disabled ? 'Still in progress' : undefined}
                onClick={() => { if (!disabled) onSelectSection?.(key); }}
                onKeyDown={event => {
                  if (!disabled && (event.key === 'Enter' || event.key === ' ')) {
                    event.preventDefault();
                    onSelectSection?.(key);
                  }
                }}>{label}</div>
            );
          })}
        </nav>
        <main className="wu-settings__content">
          {section === 'general' && (
            <>
              <SettingsCard title="Repository">
                <SettingRow label="Workspace name" detail="Shown in the sidebar, switcher, and workspace cards.">
                  <label className="wu-text-input">
                    <input value={nameDraft} onChange={event => {
                      settingsEditRevision.current += 1;
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
              <SettingsCard title="Engine" subtitle="every session in this workspace runs on this">
                <ModelRow label="Workspace default" tone="default" value={settings.defaultModel}
                  choices={agentChoices}
                  onRefresh={() => { void refreshModelOptions(); }}
                  refreshing={refreshingModels}
                  onChange={value => update('defaultModel', value)} />
              </SettingsCard>
              <SettingsCard
                title="Overrides per session kind"
                subtitle="leave on Workspace default unless a kind needs its own engine"
              >
                <ModelRow label="Deep reasoning for specs & plans" tone="plan" value={settings.planModel}
                  allowInherit
                  inherited={settings.defaultModel}
                  choices={agentChoices}
                  onRefresh={() => { void refreshModelOptions(); }}
                  refreshing={refreshingModels}
                  onChange={value => update('planModel', value)} />
                <ModelRow label="Code writing & tests" tone="dev" value={settings.devModel}
                  allowInherit
                  inherited={settings.defaultModel}
                  choices={agentChoices}
                  onRefresh={() => { void refreshModelOptions(); }}
                  refreshing={refreshingModels}
                  onChange={value => update('devModel', value)} />
                <ModelRow label="PR review rounds" tone="review" value={settings.reviewModel}
                  allowInherit
                  inherited={settings.defaultModel}
                  choices={agentChoices}
                  onRefresh={() => { void refreshModelOptions(); }}
                  refreshing={refreshingModels}
                  onChange={value => update('reviewModel', value)} />
                <ModelRow label="Cheap loops on red builds" tone="ci-fix" value={settings.ciFixModel}
                  allowInherit
                  inherited={settings.defaultModel}
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
              <SettingsCard title="Task concurrency">
                <NullableIntegerRow label="Max running tasks"
                  detail="Maximum distinct tasks executing across all trunks. Empty uses the app default."
                  placeholder="App default"
                  draft={maxRunningTasksDraft}
                  valid={maxRunningTasksValid}
                  onChange={updateMaxRunningTasks} />
              </SettingsCard>
            </>
          )}
          {section === 'relations' && (
            <WorkspaceRelationsSettings workspaceId={workspaceId} repoName={repoName} />
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
          {section === 'automation' && (
            <>
              <SettingsCard title="Local quality scan" subtitle="clean code and performance">
                <ToggleRow
                  label="Scan this workspace"
                  detail="Periodically inspects the verified local checkout. Confident findings become approval-gated GitHub issue proposals; uncertain findings ask before entering the local backlog."
                  checked={settings.qualityScanEnabled}
                  disabled={!settings.qualityScanEnabled
                    && (automation === null || !automation.qualityScan.eligible)}
                  onChange={value => update('qualityScanEnabled', value)} />
                <AutomationHealth
                  status={automation?.qualityScan ?? null}
                  metrics={[['Findings proposed', automation?.qualityScan.findingsProposed ?? 0]]} />
              </SettingsCard>
              <SettingsCard title="Remote issue intake" subtitle="triage and safe implementation">
                <ToggleRow
                  label="Watch new GitHub issues"
                  detail="Triages new issues through durable V2 tasks. High-confidence, low-risk, small fixes start locally; every push and pull request remains approval-gated."
                  checked={settings.remoteIssueIntakeEnabled}
                  disabled={!settings.remoteIssueIntakeEnabled
                    && (automation === null || !automation.remoteIssueIntake.eligible)}
                  onChange={value => update('remoteIssueIntakeEnabled', value)} />
                <AutomationHealth
                  status={automation?.remoteIssueIntake ?? null}
                  metrics={[
                    ['Issues examined', automation?.remoteIssueIntake.issuesExamined ?? 0],
                    ['Tasks queued', automation?.remoteIssueIntake.tasksQueued ?? 0],
                    ['Implementations started', automation?.remoteIssueIntake.implementationsStarted ?? 0],
                  ]} />
              </SettingsCard>
            </>
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
          {section === 'relations' ? null : (
            <footer className="wu-settings__save">
              <span>{!maxRunningTasksValid
                ? 'Max running tasks must be empty or a whole number greater than zero.'
                : actionMessage ?? (saved ? 'Saved' : 'Changes stay local to this workspace.')}</span>
              <button type="button" className="wu-primary-button" disabled={!maxRunningTasksValid}
                onClick={() => { void save(); }}>
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

function ModelRow({
  label, tone, value, inherited, choices, refreshing, allowInherit = false, onRefresh, onChange,
}: {
  label: string;
  tone: string;
  value: string;
  /** Account-level default for this session kind, when known. */
  inherited?: string;
  choices: AgentChoice[];
  refreshing: boolean;
  /** Offer "Workspace default" as the first option, and keep an empty
   *  stored value meaning exactly that. */
  allowInherit?: boolean;
  onRefresh: () => void;
  onChange: (value: string) => void;
}) {
  const inheriting = allowInherit && value === INHERIT;
  const selected = inheriting ? INHERIT : selectableChoice(value, choices);
  const selectedChoice = choices.find(choice => choice.value === selected);
  const overridden = !inheriting && inherited !== undefined && normalizeChoice(inherited) !== selected;
  return (
    <div className="wu-setting-row wu-model-row">
      <span className={`wu-kind-chip ${tone}`}>{tone === 'ci-fix' ? 'ci fix' : tone}</span>
      <span className="wu-model-row__description">
        {label}
        {overridden && <em className="wu-override" title="Differs from the account default in Settings → AI">override</em>}
      </span>
      <label className="wu-model-picker">
        <span className={`wu-model-picker__glyph ${choiceClass(selected)}`}>{choiceGlyph(selected)}</span>
        <span className="wu-model-picker__value">
          {inheriting
            ? 'Workspace default'
            : selectedChoice === undefined ? selected : choiceText(selectedChoice)}
        </span>
        <select aria-label={`${label} model`} value={selected} onChange={event => onChange(event.target.value)}>
          {allowInherit && <option value={INHERIT}>Workspace default</option>}
          {choices.map(choice => (
            <option key={choice.value} value={choice.value} disabled={choice.disabled}>
              {choiceText(choice)}
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

function coerceSettingsChoices(value: StoredSettings, choices: AgentChoice[]): StoredSettings {
  // An inheriting row stays inheriting — only a real pick gets repaired
  // when the engine it names has been uninstalled or signed out.
  const keepInherit = (stored: string) =>
    stored === INHERIT ? INHERIT : selectableChoice(stored, choices);
  return {
    ...value,
    defaultModel: selectableChoice(value.defaultModel, choices),
    planModel: keepInherit(value.planModel),
    devModel: keepInherit(value.devModel),
    reviewModel: keepInherit(value.reviewModel),
    ciFixModel: keepInherit(value.ciFixModel),
  };
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

function NullableIntegerRow({ label, detail, placeholder, draft, valid, onChange }: {
  label: string;
  detail: string;
  placeholder: string;
  draft: string;
  valid: boolean;
  onChange: (draft: string) => void;
}) {
  return (
    <SettingRow label={label}
      detail={valid ? detail : 'Enter a whole number greater than zero, or leave empty.'}>
      <label className="wu-number-input">
        <input type="text" inputMode="numeric" aria-label={label} aria-invalid={!valid}
          placeholder={placeholder} value={draft}
          onChange={event => onChange(event.target.value)} />
      </label>
    </SettingRow>
  );
}

function parseNullablePositiveInteger(draft: string): number | null | undefined {
  if (draft === '') return null;
  if (!/^[1-9]\d*$/.test(draft)) return undefined;
  const parsed = Number(draft);
  return Number.isSafeInteger(parsed) ? parsed : undefined;
}

function formatNullableInteger(value: number | null): string {
  return value === null ? '' : String(value);
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
        aria-label={label}
        disabled={disabled}
        className={`wu-switch${checked ? ' on' : ''}`} onClick={() => onChange(!checked)}>
        <i />
      </button>
    </SettingRow>
  );
}

type AutomationJobStatus = WorkspaceAutomationStatusDto['qualityScan']
  | WorkspaceAutomationStatusDto['remoteIssueIntake'];

function AutomationHealth({ status, metrics }: {
  status: AutomationJobStatus | null;
  metrics: Array<readonly [string, number]>;
}) {
  if (status === null) {
    return <SettingRow label="Run status"><span role="status">Loading…</span></SettingRow>;
  }
  return (
    <>
      <SettingRow label="Run status" detail={status.reason ?? undefined}>
        <span role="status">{status.running ? 'Running' : status.enabled ? 'Enabled' : 'Disabled'}</span>
      </SettingRow>
      <SettingRow label="Last run">
        <span>{status.lastRunAt === null
          ? 'Not run yet'
          : `${formatAutomationTime(status.lastRunAt, 'Unknown')} · ${formatOutcome(status.lastOutcome)}`}</span>
      </SettingRow>
      <SettingRow label="Next run">
        <span>{status.running
          ? 'In progress'
          : status.enabled
            ? formatAutomationTime(status.expectedNextRunAt, 'Scheduling…')
            : 'Disabled'}</span>
      </SettingRow>
      {metrics.map(([label, value]) => (
        <SettingRow key={label} label={label}><span>{value}</span></SettingRow>
      ))}
      {status.lastError !== null && (
        <SettingRow label="Last error"><span role="alert">{status.lastError}</span></SettingRow>
      )}
    </>
  );
}

function formatAutomationTime(value: string | null, fallback: string): string {
  if (value === null) return fallback;
  const time = new Date(value);
  if (Number.isNaN(time.getTime())) return fallback;
  return time.toLocaleString(undefined, {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}

function formatOutcome(value: AutomationJobStatus['lastOutcome']): string {
  if (value === null) return 'Unknown';
  return value.charAt(0) + value.slice(1).toLowerCase();
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

/** `inherited` is the account-level default set; a workspace key that was
 *  never chosen falls back to it before the hardcoded baseline. */
function fromDto(value: WorkspaceSettingsDto): StoredSettings {
  return {
    // What an unset workspace actually resolves to: the catalog's first CLI
    // agent. This used to display the account-level *dev* engine while CI-fix
    // work resolved through a different account field that defaulted to codex,
    // so the page said one thing and the runs did another.
    defaultModel: choiceOrInherit(value.providers.default) || defaults.defaultModel,
    planModel: choiceOrInherit(value.providers.plan),
    devModel: choiceOrInherit(value.providers.dev),
    reviewModel: choiceOrInherit(value.providers.review),
    ciFixModel: choiceOrInherit(value.providers['ci-fix']),
    perSessionCap: value.sessionCapUsd,
    dailyCap: value.dailyCapUsd,
    pauseAtCap: value.pauseAtCap,
    maxRunningTasks: value.maxRunningTasks ?? null,
    syncSeconds: value.syncSeconds,
    brainBudget: value.brainBudgetChars,
    distillMinutes: value.distillMinutes,
    notifyQuestions: true,
    notifyReviews: true,
    notifyCi: value.notifyCi,
    notifyCompletions: value.notifyCompletions,
    qualityScanEnabled: value.qualityScanEnabled ?? false,
    remoteIssueIntakeEnabled: value.remoteIssueIntakeEnabled ?? false,
  };
}

/** A stored provider value, or {@link INHERIT} when it is absent/blank. */
function choiceOrInherit(value: string | undefined): string {
  return value === undefined || value.trim() === '' ? INHERIT : normalizeChoice(value);
}

function toDto(value: StoredSettings): WorkspaceSettingsDto {
  return {
    sessionCapUsd: value.perSessionCap,
    dailyCapUsd: value.dailyCap,
    pauseAtCap: value.pauseAtCap,
    maxRunningTasks: value.maxRunningTasks,
    syncSeconds: value.syncSeconds,
    brainBudgetChars: value.brainBudget,
    distillMinutes: value.distillMinutes,
    kbAudiences: ['plan', 'dev', 'review', 'ci-fix'],
    providers: {
      default: value.defaultModel,
      plan: value.planModel,
      dev: value.devModel,
      review: value.reviewModel,
      'ci-fix': value.ciFixModel,
    },
    notifyCi: value.notifyCi,
    notifyCompletions: value.notifyCompletions,
    qualityScanEnabled: value.qualityScanEnabled,
    remoteIssueIntakeEnabled: value.remoteIssueIntakeEnabled,
  };
}
