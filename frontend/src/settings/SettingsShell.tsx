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
import { useCallback, useEffect, useState } from 'react';
import { relativeTime } from '../relativeTime';
import type {
  GitHubAccountDto,
  ModelApiKeyDto,
  ModelOptionDto,
  ModelProviderDto,
  ModelSettingsDto,
  QuietHoursDto,
} from '../types';
import { RETENTION_DAYS as RECENT_RETENTION_DAYS } from '../recent/model';
import {
  formatMinuteOfDay,
  MINUTE_OPTIONS,
  providerAvailable,
  providerStatusLabel,
} from './model';
import '../css/settings.css';

export default function SettingsShell() {
  const [version, setVersion] = useState('');

  useEffect(() => {
    void window.bridge.getAppVersion().then(value => setVersion(value.version));
  }, []);

  return (
    <main className="sets-page">
      <h1>Settings</h1>
      <ModelsCard />
      <GitHubAccountCard />
      <QuietHoursCard />
      <RecentCard />
      <footer className="sets-footer">ByteQuay {version}</footer>
    </main>
  );
}

function ModelsCard() {
  const [settings, setSettings] = useState<ModelSettingsDto | null>(null);
  const [checkedAt, setCheckedAt] = useState<string | null>(null);
  const [checking, setChecking] = useState(true);
  const [addingKey, setAddingKey] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setChecking(true);
    window.bridge.getModelSettings()
      .then(value => {
        setSettings(value);
        setCheckedAt(new Date().toISOString());
        setError(null);
      })
      .catch(failure => setError(failure instanceof Error ? failure.message : String(failure)))
      .finally(() => setChecking(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  const apply = (update: Promise<ModelSettingsDto>) => {
    setError(null);
    update
      .then(value => setSettings(value))
      .catch(failure => setError(failure instanceof Error ? failure.message : String(failure)));
  };

  return (
    <section className="sets-card">
      <div className="sets-card-head">
        <span className="sets-card-title">Models &amp; credentials</span>
      </div>
      {error && <div className="sets-error" role="alert">{error}</div>}
      <div className="sets-card-subhead">CLI providers — detected automatically</div>
      {(settings?.providers ?? []).map(provider => (
        <ProviderRow
          key={provider.id}
          provider={provider}
          checking={checking}
          checkedAt={checkedAt}
          onRecheck={load}
          onSelect={(model, effort) =>
            apply(window.bridge.updateModelSelection(provider.id, model, effort))}
          onSetMain={() => apply(window.bridge.setMainModel(provider.id))}
        />
      ))}
      {settings === null && (
        <div className="sets-row"><span className="sets-badge is-neutral"><span className="sets-spinner" />Checking…</span></div>
      )}
      <div className="sets-card-subhead">API keys</div>
      {(settings?.apiKeys ?? []).map(apiKey => (
        <ApiKeyRow
          key={apiKey.id}
          apiKey={apiKey}
          onSelect={model => apply(window.bridge.updateModelSelection(apiKey.id, model, null))}
          onSetMain={() => apply(window.bridge.setMainModel(apiKey.id))}
          onRemove={() => apply(window.bridge.removeModelApiKey(apiKey.alias))}
        />
      ))}
      {addingKey && settings ? (
        <AddKeyForm
          keyProviders={settings.keyProviders}
          onSave={(provider, alias, key) => {
            apply(window.bridge.addModelApiKey(provider, alias, key));
            setAddingKey(false);
          }}
          onCancel={() => setAddingKey(false)}
        />
      ) : (
        <div className="sets-row sets-add-row">
          <button type="button" className="sets-link" onClick={() => setAddingKey(true)}>
            + Add API key
          </button>
        </div>
      )}
    </section>
  );
}

function ProviderRow({ provider, checking, checkedAt, onRecheck, onSelect, onSetMain }: {
  provider: ModelProviderDto;
  checking: boolean;
  checkedAt: string | null;
  onRecheck: () => void;
  onSelect: (model: string, effort: string | null) => void;
  onSetMain: () => void;
}) {
  const status = providerStatusLabel(provider.readiness, checking);
  const available = !checking && providerAvailable(provider.readiness);
  return (
    <div className="sets-row">
      <div className="sets-row-main">
        <div className="sets-row-title">
          <span className="sets-name">{provider.label} CLI</span>
          {checking ? (
            <span className="sets-badge is-neutral"><span className="sets-spinner" />Checking…</span>
          ) : (
            <span className={`sets-badge ${status.tone}`}>{status.label}</span>
          )}
        </div>
        <div className="sets-row-sub">
          {checking
            ? 'Verifying the CLI and its login'
            : provider.readiness === 'READY'
              ? <>
                {provider.version ? `v${provider.version} · ` : ''}
                checked {checkedAt ? relativeTime(checkedAt) : 'just now'}
                {' · '}
                <button type="button" className="sets-link" onClick={onRecheck}>Check again</button>
              </>
              : <>
                {provider.version ? `v${provider.version} · ` : ''}
                {provider.diagnostic}
                {' · '}
                <button type="button" className="sets-link" onClick={onRecheck}>Check again</button>
              </>}
        </div>
      </div>
      <ModelChip
        label={modelLabel(provider.models, provider.selectedModel)}
        detail={provider.selectedEffort}
        disabled={!available}
        models={provider.models}
        selectedModel={provider.selectedModel}
        selectedEffort={provider.selectedEffort}
        onSelect={onSelect}
      />
      {provider.main
        ? <span className="sets-main-badge">MAIN</span>
        : <button type="button" className="sets-link sets-nowrap" onClick={onSetMain}>Set as main</button>}
    </div>
  );
}

function ApiKeyRow({ apiKey, onSelect, onSetMain, onRemove }: {
  apiKey: ModelApiKeyDto;
  onSelect: (model: string) => void;
  onSetMain: () => void;
  onRemove: () => void;
}) {
  const added = new Date(apiKey.createdAt);
  return (
    <div className="sets-row">
      <div className="sets-row-main">
        <div className="sets-row-title">
          <span className="sets-name">{apiKey.alias}</span>
          <span className="sets-badge is-neutral">{apiKey.provider}</span>
        </div>
        <div className="sets-row-sub sets-mono">
          {apiKey.maskedKey}
          <span className="sets-row-sub-plain">
            {' · added '}
            {added.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}
            {' · '}
            <button type="button" className="sets-link" onClick={onRemove}>Remove</button>
          </span>
        </div>
      </div>
      <ModelChip
        label={apiKey.selectedModel}
        models={apiKey.models.map((name): ModelOptionDto => ({ id: name, name, description: '', efforts: [] }))}
        selectedModel={apiKey.selectedModel}
        onSelect={model => onSelect(model)}
      />
      {apiKey.main
        ? <span className="sets-main-badge">MAIN</span>
        : <button type="button" className="sets-link sets-nowrap" onClick={onSetMain}>Set as main</button>}
    </div>
  );
}

export function modelLabel(models: ModelOptionDto[], id: string): string {
  return models.find(model => model.id === id)?.name ?? id;
}

export function ModelChip({ label, detail, disabled, models, selectedModel, selectedEffort, onSelect }: {
  label: string;
  detail?: string;
  disabled?: boolean;
  models: ModelOptionDto[];
  selectedModel: string;
  selectedEffort?: string;
  onSelect: (model: string, effort: string | null) => void;
}) {
  const [open, setOpen] = useState(false);
  const efforts = models.find(model => model.id === selectedModel)?.efforts ?? [];

  useEffect(() => {
    if (!open) return;
    const close = () => setOpen(false);
    window.addEventListener('mousedown', close);
    return () => window.removeEventListener('mousedown', close);
  }, [open]);

  return (
    <div className="sets-chip-wrap" onMouseDown={event => event.stopPropagation()}>
      <button
        type="button"
        className="sets-chip"
        disabled={disabled}
        onClick={() => setOpen(value => !value)}
      >
        {label}
        {detail && <span className="sets-chip-detail">{detail}</span>}
        <span className="sets-caret">⌄</span>
      </button>
      {open && (
        <div className="sets-menu">
          {models.map(model => (
            <button
              type="button"
              key={model.id}
              className={`sets-menu-item${model.id === selectedModel ? ' is-current' : ''}`}
              onClick={() => {
                onSelect(
                  model.id,
                  selectedEffort && model.efforts.includes(selectedEffort)
                    ? selectedEffort
                    : model.defaultEffort ?? null,
                );
                setOpen(false);
              }}
            >
              <span className="sets-menu-name">
                {model.name}
                {model.id === selectedModel && <span className="sets-check">✓</span>}
              </span>
              {model.description && <span className="sets-menu-desc">{model.description}</span>}
            </button>
          ))}
          {efforts.length > 0 && (
            <>
              <div className="sets-menu-divider" />
              <div className="sets-menu-label">Effort</div>
              {efforts.map(effort => (
                <button
                  type="button"
                  key={effort}
                  className={`sets-menu-item${effort === selectedEffort ? ' is-current' : ''}`}
                  onClick={() => {
                    onSelect(selectedModel, effort);
                    setOpen(false);
                  }}
                >
                  <span className="sets-menu-name">
                    {effort}
                    {effort === selectedEffort && <span className="sets-check">✓</span>}
                  </span>
                </button>
              ))}
            </>
          )}
        </div>
      )}
    </div>
  );
}

function AddKeyForm({ keyProviders, onSave, onCancel }: {
  keyProviders: Array<{ name: string; models: string[] }>;
  onSave: (provider: string, alias: string, key: string) => void;
  onCancel: () => void;
}) {
  const [provider, setProvider] = useState(keyProviders[0]?.name ?? '');
  const [alias, setAlias] = useState('');
  const [key, setKey] = useState('');
  return (
    <div className="sets-key-form">
      <div className="sets-key-form-row">
        <label>
          Provider
          <select value={provider} onChange={event => setProvider(event.target.value)}>
            {keyProviders.map(entry => (
              <option key={entry.name} value={entry.name}>{entry.name}</option>
            ))}
          </select>
        </label>
        <label className="sets-grow">
          Alias
          <input
            value={alias}
            placeholder="personal-deepseek"
            onChange={event => setAlias(event.target.value)}
          />
        </label>
        <label className="sets-grow-more">
          API key
          <input
            type="password"
            value={key}
            className="sets-mono"
            onChange={event => setKey(event.target.value)}
          />
        </label>
        <button
          type="button"
          className="sets-btn-primary"
          disabled={!provider || !alias.trim() || !key.trim()}
          onClick={() => onSave(provider, alias.trim(), key)}
        >
          Save
        </button>
        <button type="button" className="sets-btn" onClick={onCancel}>Cancel</button>
      </div>
      <div className="sets-card-note">
        The alias is how the key appears everywhere — pick something you&apos;ll recognize.
        Keys are stored encrypted locally and masked after saving.
      </div>
    </div>
  );
}

function GitHubAccountCard() {
  const [account, setAccount] = useState<GitHubAccountDto | null>(null);
  const [login, setLogin] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [pat, setPat] = useState('');
  const [saving, setSaving] = useState(false);
  const [copied, setCopied] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(() => {
    void window.bridge.getGitHubAccount().then(value => {
      setAccount(value);
      if (value.configured) {
        window.bridge.getUserProfile()
          .then(profile => setLogin(profile.login))
          .catch(() => setLogin(null));
      }
    });
  }, []);

  useEffect(() => { load(); }, [load]);

  const save = async () => {
    if (!pat.trim()) return;
    setSaving(true);
    setMessage(null);
    try {
      await window.bridge.savePat(pat.trim());
      setPat('');
      setEditing(false);
      load();
    }
    catch (failure) {
      setMessage(failure instanceof Error ? failure.message : String(failure));
    }
    finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!window.confirm('Delete the stored GitHub token?')) return;
    try {
      await window.bridge.deleteGitHubToken();
      setLogin(null);
      load();
    }
    catch (failure) {
      setMessage(failure instanceof Error ? failure.message : String(failure));
    }
  };

  const copy = async () => {
    try {
      await window.bridge.copyGitHubToken();
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    }
    catch (failure) {
      setMessage(failure instanceof Error ? failure.message : String(failure));
    }
  };

  const configured = account?.configured ?? false;
  return (
    <section className="sets-card">
      <div className="sets-card-head"><span className="sets-card-title">GitHub account</span></div>
      <div className="sets-card-body">
        <div className="sets-row-title">
          {configured
            ? <span className="sets-badge is-ok">✓ Connected</span>
            : <span className="sets-badge is-neutral">Not connected</span>}
          {configured && (
            <span className="sets-row-sub-inline">
              {login ? <>as <strong>{login}</strong> · </> : null}personal access token
            </span>
          )}
        </div>
        {configured && !editing && account?.maskedToken && (
          <div className="sets-token-row">
            <span className="sets-token sets-mono">{account.maskedToken}</span>
            <button type="button" className="sets-btn" onClick={() => { void copy(); }}>
              {copied ? 'Copied' : 'Copy'}
            </button>
            <button type="button" className="sets-btn" onClick={() => setEditing(true)}>Update…</button>
            <button type="button" className="sets-btn is-danger" onClick={() => { void remove(); }}>
              Delete
            </button>
          </div>
        )}
        {(!configured || editing) && (
          <div className="sets-token-row">
            <input
              type="password"
              value={pat}
              placeholder="github_pat_…"
              className="sets-token-input sets-mono"
              aria-label="GitHub personal access token"
              onChange={event => setPat(event.target.value)}
            />
            <button
              type="button"
              className="sets-btn-primary"
              disabled={saving || !pat.trim()}
              onClick={() => { void save(); }}
            >
              {saving ? 'Saving…' : 'Save token'}
            </button>
            {editing && <button type="button" className="sets-btn" onClick={() => setEditing(false)}>Cancel</button>}
          </div>
        )}
        {message && <div className="sets-error" role="alert">{message}</div>}
      </div>
    </section>
  );
}

function RecentCard() {
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const clear = () => {
    setError(null);
    window.bridge.clearRecent()
      .then(() => {
        setConfirming(false);
      })
      .catch(failure => setError(failure instanceof Error ? failure.message : String(failure)));
  };

  return (
    <section className="sets-card">
      <div className="sets-card-head">
        <span className="sets-card-title">Recent</span>
        <span className="sets-card-hint">this Mac only — never synced</span>
      </div>
      {error && <div className="sets-error" role="alert">{error}</div>}
      <div className="sets-row">
        <div className="sets-row-main">
          <div className="sets-row-title">What you touched is kept for {RECENT_RETENTION_DAYS} days</div>
        </div>
      </div>
      <div className="sets-row">
        <div className="sets-row-main">
          <div className="sets-row-title">Clear history</div>
        </div>
        {confirming ? (
          <>
            <button type="button" className="sets-btn is-danger" onClick={clear}>Clear everything</button>
            <button type="button" className="sets-btn" onClick={() => setConfirming(false)}>Cancel</button>
          </>
        ) : (
          <button type="button" className="sets-btn is-danger" onClick={() => setConfirming(true)}>
            Clear history
          </button>
        )}
      </div>
    </section>
  );
}

function QuietHoursCard() {
  const [quietHours, setQuietHours] = useState<QuietHoursDto | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void window.bridge.getQuietHours().then(setQuietHours).catch(() => {});
  }, []);

  const update = (next: QuietHoursDto) => {
    setQuietHours(next);
    window.bridge.saveQuietHours(next)
      .then(saved => setQuietHours(saved))
      .catch(failure => setError(failure instanceof Error ? failure.message : String(failure)));
  };

  if (quietHours === null) return null;
  return (
    <section className="sets-card">
      <div className="sets-card-head"><span className="sets-card-title">GitHub quiet hours</span></div>
      <div className="sets-quiet-row">
        <button
          type="button"
          role="switch"
          aria-checked={quietHours.enabled}
          aria-label="Pause GitHub refresh during quiet hours"
          className={`sets-toggle${quietHours.enabled ? ' is-on' : ''}`}
          onClick={() => update({ ...quietHours, enabled: !quietHours.enabled })}
        >
          <span className="sets-toggle-knob" />
        </button>
        <span className="sets-quiet-label">Pause GitHub refresh during quiet hours</span>
        <select
          value={quietHours.startMinute}
          aria-label="Quiet hours start"
          disabled={!quietHours.enabled}
          onChange={event => update({ ...quietHours, startMinute: Number(event.target.value) })}
        >
          {MINUTE_OPTIONS.map(minute => (
            <option key={minute} value={minute}>{formatMinuteOfDay(minute)}</option>
          ))}
        </select>
        <span className="sets-quiet-to">to</span>
        <select
          value={quietHours.endMinute}
          aria-label="Quiet hours end"
          disabled={!quietHours.enabled}
          onChange={event => update({ ...quietHours, endMinute: Number(event.target.value) })}
        >
          {MINUTE_OPTIONS.map(minute => (
            <option key={minute} value={minute}>{formatMinuteOfDay(minute)}</option>
          ))}
        </select>
      </div>
      {error && <div className="sets-error" role="alert">{error}</div>}
    </section>
  );
}
