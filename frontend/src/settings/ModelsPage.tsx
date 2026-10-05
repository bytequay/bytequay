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
import { LogIn, Plus, RefreshCw } from 'lucide-react';
import type { AgentModelSettingDto, AgentModelType, ModelSettingsDto } from '../types';
import { isClaudeAccount, isDeepSeekKeyLane, providerStatusLabel } from './model';
import { AddAction, Dialog, SelectPill, SettingsCard, SettingsRow, StatusDot, dotTone, type PillOption } from './primitives';

/** Chosen when Review should follow Development instead of naming its own model. */
export const INHERIT_VALUE = '__inherit__';

/** Saved OpenAI keys are kept, but nothing runs on them yet. */
export const API_KEY_REASON = 'Only DeepSeek keys can run attended agent tasks right now.';

export type ModelChoice = { configId: string; model: string; effort: string | null };

/**
 * Development and Review defaults use CLI lanes. DeepSeek keys are chosen in the
 * task composer; the other saved keys remain disabled here.
 */
export function modelOptions(settings: ModelSettingsDto): PillOption[] {
  const options: PillOption[] = [];
  for (const provider of settings.providers.filter(candidate => !isDeepSeekKeyLane(candidate.id))) {
    for (const model of provider.models) {
      options.push({
        value: `${provider.id}::${model.id}`,
        label: model.name,
        note: provider.label,
        group: provider.label,
      });
    }
  }
  for (const key of settings.apiKeys.filter(candidate => candidate.provider !== 'DeepSeek')) {
    for (const model of key.models) {
      options.push({
        value: `${key.id}::${model}`,
        label: model,
        note: key.alias,
        group: `${key.provider} · ${key.alias}`,
        disabled: true,
        disabledReason: API_KEY_REASON,
      });
    }
  }
  return options;
}

export function optionValue(configId: string, model: string): string {
  return `${configId}::${model}`;
}

function splitOption(value: string): { configId: string; model: string } {
  const separator = value.indexOf('::');
  return { configId: value.slice(0, separator), model: value.slice(separator + 2) };
}

/** The efforts the chosen model advertises; empty means the model takes no effort. */
export function effortsFor(settings: ModelSettingsDto, configId: string, model: string): string[] {
  return settings.providers.find(provider => provider.id === configId)
    ?.models.find(option => option.id === model)
    ?.efforts ?? [];
}

/**
 * Keeps the current effort when the newly chosen model still offers it, and
 * otherwise falls back to that model's highest, which is what the prototype
 * specifies rather than silently clearing it.
 */
export function effortAfterModelChange(
  settings: ModelSettingsDto,
  configId: string,
  model: string,
  currentEffort: string | null,
): string | null {
  const efforts = effortsFor(settings, configId, model);
  if (efforts.length === 0) return null;
  if (currentEffort !== null && efforts.includes(currentEffort)) return currentEffort;
  return efforts[efforts.length - 1];
}

export function typeLabel(type: AgentModelType): string {
  return type === 'DEVELOPMENT' ? 'Development' : 'Review';
}

/**
 * The model and effort pills for one agent type. Kept separate from the row so
 * the workspace page can put them beside its own override controls without
 * repeating the type's name.
 */
export function AgentModelControls({ agent, settings, inheritLabel, onChoose, onInherit, onAddKey }: {
  agent: AgentModelSettingDto;
  settings: ModelSettingsDto;
  /** Offered as the first option when this type may follow another; absent for Development. */
  inheritLabel?: string;
  onChoose: (choice: ModelChoice) => void;
  onInherit?: () => void;
  onAddKey?: () => void;
}) {
  const inheriting = inheritLabel !== undefined && !agent.explicit;
  const efforts = effortsFor(settings, agent.configId, agent.model);
  const options = modelOptions(settings);
  const pillOptions = inheritLabel === undefined
    ? options
    : [{ value: INHERIT_VALUE, label: inheritLabel }, ...options];

  return (
    <>
      <SelectPill
        wide
        ariaLabel={`${typeLabel(agent.type)} model`}
        value={inheriting ? INHERIT_VALUE : optionValue(agent.configId, agent.model)}
        label={inheriting ? (inheritLabel ?? '') : isClaudeAccount(agent.configId) ? `${agent.modelLabel} · ${agent.label}` : agent.modelLabel}
        options={pillOptions}
        footer={onAddKey === undefined ? undefined : (
          <button type="button" className="setw-card-foot-link setw-menu-item" onClick={onAddKey}>
            <Plus size={13} strokeWidth={2.2} color="#0969da" />
            <span className="setw-menu-label" style={{ color: '#0969da' }}>Add API key</span>
          </button>
        )}
        onSelect={value => {
          if (value === INHERIT_VALUE) {
            onInherit?.();
            return;
          }
          const { configId, model } = splitOption(value);
          onChoose({ configId, model, effort: effortAfterModelChange(settings, configId, model, agent.effort ?? null) });
        }}
      />
      {!inheriting && efforts.length > 0 && (
        <SelectPill
          quiet
          ariaLabel={`${typeLabel(agent.type)} effort`}
          value={agent.effort ?? ''}
          label={agent.effort ?? 'default'}
          options={efforts.map(effort => ({ value: effort, label: effort }))}
          onSelect={effort => onChoose({ configId: agent.configId, model: agent.model, effort })}
        />
      )}
    </>
  );
}

/** One agent type as a full settings row, which is how the global Models page draws it. */
export function AgentModelRow({ agent, subtitle, ...controls }: {
  agent: AgentModelSettingDto;
  settings: ModelSettingsDto;
  subtitle?: string;
  inheritLabel?: string;
  onChoose: (choice: ModelChoice) => void;
  onInherit?: () => void;
  onAddKey?: () => void;
}) {
  return (
    <SettingsRow title={typeLabel(agent.type)} subtitle={subtitle}>
      <AgentModelControls agent={agent} {...controls} />
    </SettingsRow>
  );
}

export default function ModelsPage({ settings, checkedAt, checking, error, onCheck, onSignIn, onChoose, onInherit, onAddKey, onRemoveKey }: {
  settings: ModelSettingsDto;
  checkedAt: string | null;
  checking: boolean;
  error: string | null;
  onCheck: () => void;
  /** Opens that lane's sign-in terminal; absent where no terminal can be opened. */
  onSignIn?: (providerId: string) => void;
  onChoose: (type: AgentModelType, choice: ModelChoice) => void;
  onInherit: (type: AgentModelType) => void;
  onAddKey: (provider: string, alias: string, key: string) => void;
  onRemoveKey: (alias: string) => void;
}) {
  const [addingKey, setAddingKey] = useState(false);
  const globalSettings = { ...settings, providers: settings.providers.filter(provider => !isClaudeAccount(provider.id)) };
  const development = settings.agents.find(agent => agent.type === 'DEVELOPMENT');
  const review = settings.agents.find(agent => agent.type === 'REVIEW');

  return (
    <div className="setw-page">
      <h1>Models</h1>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}

      <div className="setw-section"><span className="setw-section-title">Default models</span></div>
      <SettingsCard>
        {development !== undefined && (
          <AgentModelRow
            agent={development}
            settings={globalSettings}
            subtitle="Task turns and CI Autofix"
            onChoose={choice => onChoose('DEVELOPMENT', choice)}
            onAddKey={() => setAddingKey(true)}
          />
        )}
        {review !== undefined && (
          <AgentModelRow
            agent={review}
            settings={globalSettings}
            subtitle="Pull-request reviews and discussions"
            inheritLabel="Same as Development"
            onChoose={choice => onChoose('REVIEW', choice)}
            onInherit={() => onInherit('REVIEW')}
            onAddKey={() => setAddingKey(true)}
          />
        )}
      </SettingsCard>

      <div className="setw-footnote">Used by all workspaces unless changed in Model overrides.</div>

      <div className="setw-section"><span className="setw-section-title">Providers</span></div>
      <SettingsCard footer={<AddAction label="Add API key" onClick={() => setAddingKey(true)} />}>
        {globalSettings.providers.length === 0 && (
          <SettingsRow title={checking ? 'Checking providers…' : 'Providers have not been checked yet'}>
            <button type="button" className="setw-btn" disabled={checking} onClick={onCheck}>Check</button>
          </SettingsRow>
        )}
        {globalSettings.providers.filter(provider => !isDeepSeekKeyLane(provider.id)).map(provider => {
          const status = providerStatusLabel(provider.readiness, checking, provider.id);
          return (
            <SettingsRow
              key={provider.id}
              title={provider.id === 'claude' ? 'Claude Code CLI · Default account' : `${provider.label} CLI`}
              subtitle={
                <span className="setw-status">
                  <StatusDot tone={dotTone(status.tone)} />
                  <span>
                    {status.label}
                    {provider.id === 'claude' && (provider.readiness === 'READY' || provider.readiness === 'DEGRADED') && provider.diagnostic
                      ? ` · ${provider.diagnostic}` : ''}
                    {provider.version ? ` · v${provider.version}` : ''}
                  </span>
                </span>
              }
            >
              {onSignIn !== undefined && provider.readiness === 'AUTHENTICATION_REQUIRED' && (
                <button type="button" className="setw-btn" disabled={checking} onClick={() => onSignIn(provider.id)}>
                  <LogIn size={12} strokeWidth={2.2} color="#6e6e73" />
                  <span>Sign in</span>
                </button>
              )}
              <button type="button" className="setw-btn" disabled={checking} onClick={onCheck}>
                <RefreshCw size={12} strokeWidth={2.2} color="#6e6e73" />
                <span>{checking ? 'Checking…' : 'Check'}</span>
              </button>
            </SettingsRow>
          );
        })}
        {settings.apiKeys.map(key => {
          const lane = settings.providers.find(provider => isDeepSeekKeyLane(provider.id) && provider.label === `${key.provider} · ${key.alias}`);
          const status = lane === undefined ? null : providerStatusLabel(lane.readiness, checking, lane.id);
          return <SettingsRow
            key={key.id}
            title={`${key.provider} · ${key.alias}`}
            subtitle={
              <span className="setw-status">
                <StatusDot tone={status === null ? 'ok' : dotTone(status.tone)} />
                <span className="setw-mono">{key.maskedKey}</span>
                {status !== null && <span title={lane?.diagnostic || undefined}> · {status.label}</span>}
              </span>
            }
          >
            {lane !== undefined && <button type="button" className="setw-btn" disabled={checking} onClick={onCheck}>Check</button>}
            <button type="button" className="setw-btn is-quiet" onClick={() => onRemoveKey(key.alias)}>Remove</button>
          </SettingsRow>;
        })}
        {settings.apiKeys.length === 0 && (
          <SettingsRow
            title="API keys"
            subtitle={<span className="setw-status"><StatusDot tone="idle" /><span>None added</span></span>}
          />
        )}
      </SettingsCard>
      <div className="setw-footnote">
        Models and providers refresh automatically{checkedAt === null ? '' : ` · checked ${checkedAt}`}. Check fetches the latest available models.
      </div>
      <div className="setw-footnote">Claude uses the default account here. To sign in with another account for a repository, open its Model overrides.</div>

      {addingKey && (
        <AddKeyDialog
          keyProviders={settings.keyProviders}
          onCancel={() => setAddingKey(false)}
          onAdd={(provider, alias, key) => { setAddingKey(false); onAddKey(provider, alias, key); }}
        />
      )}
    </div>
  );
}

/** Alias plus secret for one provider. The secret never comes back out; only its mask does. */
function AddKeyDialog({ keyProviders, onAdd, onCancel }: {
  keyProviders: Array<{ name: string; models: string[] }>;
  onAdd: (provider: string, alias: string, key: string) => void;
  onCancel: () => void;
}) {
  const [provider, setProvider] = useState(keyProviders[0]?.name ?? '');
  const [alias, setAlias] = useState('');
  const [secret, setSecret] = useState('');
  const ready = provider !== '' && alias.trim() !== '' && secret.trim() !== '';

  return (
    <Dialog
      title="Add API key"
      confirmLabel="Add key"
      confirmDisabled={!ready}
      onConfirm={() => { if (ready) onAdd(provider, alias.trim(), secret.trim()); }}
      onCancel={onCancel}
    >
      <div className="setw-field-label">Provider</div>
      <div className="setw-seg" role="radiogroup" aria-label="API key provider">
        {keyProviders.map(entry => (
          <button
            type="button"
            key={entry.name}
            role="radio"
            aria-checked={entry.name === provider}
            className={entry.name === provider ? 'is-current' : undefined}
            onClick={() => setProvider(entry.name)}
          >
            {entry.name}
          </button>
        ))}
      </div>
      <div className="setw-field-label">Label</div>
      <input value={alias} aria-label="API key label" placeholder="personal" onChange={event => setAlias(event.target.value)} />
      <div className="setw-field-label">Key</div>
      <input
        type="password"
        value={secret}
        aria-label="API key"
        className="setw-mono"
        placeholder="sk-…"
        onChange={event => setSecret(event.target.value)}
      />
      <div className="setw-dialog-hint">
        {provider === 'DeepSeek'
          ? 'Stored encrypted on this Mac · choose this key for an attended task'
          : 'Stored encrypted on this Mac · not used by any feature yet'}
      </div>
    </Dialog>
  );
}
