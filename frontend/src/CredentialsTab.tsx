import { useEffect, useState } from 'react';
import {
  CREDENTIAL_TEMPLATES,
  type AiProviderInfo,
  type AiSettingsDto,
  type CredentialDto,
  type CredentialTemplate,
  type CredentialType,
} from './types';

type Props = {
  onFirstCredentialAdded?: () => void;
  /** Restrict the editor + list to a single credential type. When set, the
   *  AI provider selector is also hidden. Used by per-type pages once they
   *  land in M4–M6. */
  filterType?: CredentialType;
};

function formatTimestamp(iso: string | null): string {
  if (!iso) return 'never';
  const d = new Date(iso);
  const diffMs = Date.now() - d.getTime();
  const mins = Math.round(diffMs / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
}

function templateFor(type: CredentialType, name: string): CredentialTemplate | undefined {
  return CREDENTIAL_TEMPLATES.find(t => t.type === type && t.name === name);
}

const DEFAULT_INSTANCE_NAME = 'default api';

type EditorProps = {
  templates: CredentialTemplate[];
  existing?: CredentialDto;
  onSave: (
    type: CredentialType,
    name: string,
    instanceName: string,
    value: string,
    label: string | null,
    notes: string | null,
  ) => Promise<void>;
  onCancel: () => void;
};

function CredentialEditor({ templates, existing, onSave, onCancel }: EditorProps) {
  // When editing an existing row, lock to its (type, name); otherwise default
  // to the first template offered.
  const initialKey = existing
    ? `${existing.type}/${existing.name}`
    : `${templates[0]?.type ?? 'ACCOUNT'}/${templates[0]?.name ?? 'github'}`;
  const [templateKey, setTemplateKey] = useState(initialKey);
  const [instanceName, setInstanceName] = useState(existing?.instanceName ?? DEFAULT_INSTANCE_NAME);
  const [value, setValue] = useState('');
  const [label, setLabel] = useState(existing?.label ?? '');
  const [notes, setNotes] = useState(existing?.notes ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [tType, tName] = templateKey.split('/') as [CredentialType, string];
  const template = templateFor(tType, tName);

  const handleSave = async () => {
    if (!value.trim()) {
      setError('Value must not be blank.');
      return;
    }
    const finalInstance = instanceName.trim() || DEFAULT_INSTANCE_NAME;
    setSaving(true);
    setError(null);
    try {
      await onSave(tType, tName, finalInstance, value.trim(), label.trim() || null, notes.trim() || null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSaving(false);
    }
  };

  return (
    <div className="credentials-editor">
      <h3 className="credentials-editor__title">{existing ? 'Update credential' : 'Add credential'}</h3>
      <div className="credentials-editor__field">
        <label className="credentials-editor__label">Type</label>
        <select
          className="credentials-editor__select"
          value={templateKey}
          onChange={e => setTemplateKey(e.target.value)}
          disabled={!!existing}
        >
          {templates.map(t => (
            <option key={`${t.type}/${t.name}`} value={`${t.type}/${t.name}`}>
              {t.displayName}
            </option>
          ))}
        </select>
        {template && <p className="credentials-editor__hint">{template.usageDescription}</p>}
      </div>
      <div className="credentials-editor__field">
        <label className="credentials-editor__label">Name</label>
        <input
          className="credentials-editor__input"
          type="text"
          value={instanceName}
          onChange={e => setInstanceName(e.target.value)}
          placeholder={DEFAULT_INSTANCE_NAME}
          disabled={!!existing}
        />
        <p className="credentials-editor__hint">
          Lets you keep multiple keys for the same provider — e.g. "personal" and "work". Must be unique
          within {tType === 'AI' ? 'this AI provider' : 'this credential type+name'}. Defaults to "{DEFAULT_INSTANCE_NAME}".
        </p>
      </div>
      <div className="credentials-editor__field">
        <label className="credentials-editor__label">Value</label>
        <input
          className="credentials-editor__input"
          type="password"
          value={value}
          onChange={e => setValue(e.target.value)}
          placeholder={existing ? 'Paste a new value to replace the current one' : 'Paste the key or URL'}
          autoFocus
        />
        <p className="credentials-editor__hint">
          Stored encrypted with AES-256-GCM on this machine. Only a masked preview is shown after save.
        </p>
      </div>
      <div className="credentials-editor__field">
        <label className="credentials-editor__label">Label (optional)</label>
        <input
          className="credentials-editor__input"
          type="text"
          value={label}
          onChange={e => setLabel(e.target.value)}
          placeholder="e.g. Work account"
        />
      </div>
      <div className="credentials-editor__field">
        <label className="credentials-editor__label">Notes (optional)</label>
        <textarea
          className="credentials-editor__textarea"
          value={notes}
          onChange={e => setNotes(e.target.value)}
          rows={2}
          placeholder="e.g. scopes: repo, read:user"
        />
      </div>
      {error && <p className="credentials-editor__error">{error}</p>}
      <div className="credentials-editor__actions">
        <button className="button button--primary" onClick={handleSave} disabled={saving} type="button">
          {saving ? 'Saving…' : existing ? 'Update' : 'Add'}
        </button>
        <button className="button button--secondary" onClick={onCancel} disabled={saving} type="button">
          Cancel
        </button>
      </div>
    </div>
  );
}

function CredentialsTab({ onFirstCredentialAdded, filterType }: Props) {
  const [credentials, setCredentials] = useState<CredentialDto[]>([]);
  const [providers, setProviders] = useState<AiProviderInfo[]>([]);
  const [aiSettings, setAiSettingsState] = useState<AiSettingsDto>({ provider: 'claude', model: '' });
  const [editing, setEditing] = useState<CredentialDto | 'new' | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const templates = filterType
    ? CREDENTIAL_TEMPLATES.filter(t => t.type === filterType)
    : CREDENTIAL_TEMPLATES;

  // Defensive: also filter client-side so a stale backend (e.g. running an
  // older build before the ?type= filter existed) can't leak rows of the
  // wrong type into a per-type page like AI review → Credentials.
  const visibleCredentials = filterType
    ? credentials.filter(c => c.type === filterType)
    : credentials;

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [creds, provs, settings] = await Promise.all([
        window.bridge.listCredentials(filterType),
        window.bridge.listAiProviders(),
        window.bridge.getAiSettings(),
      ]);
      setCredentials(creds);
      setProviders(provs);
      setAiSettingsState(settings);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [filterType]);

  const handleSave = async (
    type: CredentialType,
    name: string,
    instanceName: string,
    value: string,
    label: string | null,
    notes: string | null,
  ) => {
    const wasEmpty = credentials.length === 0;
    await window.bridge.upsertCredential({ type, name, instanceName, value, label, notes });
    setEditing(null);
    await load();
    if (wasEmpty && onFirstCredentialAdded) onFirstCredentialAdded();
  };

  const handleDelete = async (type: CredentialType, name: string, instanceName: string) => {
    const tpl = templateFor(type, name);
    const label = tpl?.displayName ?? `${type.toLowerCase()} / ${name}`;
    if (!confirm(`Delete the stored ${label} ("${instanceName}")?`)) return;
    await window.bridge.deleteCredential(type, name, instanceName);
    await load();
  };

  const handleProviderChange = async (providerId: string) => {
    const updated = await window.bridge.setAiSettings(providerId, aiSettings.model || null);
    setAiSettingsState(updated);
    setProviders(await window.bridge.listAiProviders());
  };

  const handleModelChange = async (model: string) => {
    const updated = await window.bridge.setAiSettings(aiSettings.provider, model);
    setAiSettingsState(updated);
  };

  const showProviderPicker = !filterType || filterType === 'AI';

  return (
    <div className="credentials-tab">
      {editing && (
        <CredentialEditor
          templates={templates}
          existing={editing === 'new' ? undefined : editing}
          onSave={handleSave}
          onCancel={() => setEditing(null)}
        />
      )}

      {!editing && (
        <>
          <div className="credentials-tab__header">
            <div>
              <h3 className="credentials-tab__title">Stored keys</h3>
              <p className="credentials-tab__subtitle">
                All keys are encrypted at rest on this machine. The backend never logs or returns the raw value.
              </p>
            </div>
            <button className="button button--primary" onClick={() => setEditing('new')} type="button">
              + Add credential
            </button>
          </div>

          {loading && <p className="credentials-tab__state">Loading…</p>}
          {error && <p className="credentials-tab__error">{error}</p>}

          {!loading && !error && visibleCredentials.length === 0 && (
            <div className="credentials-tab__empty">
              <p>No credentials saved yet.</p>
              <p>Start by adding your GitHub PAT so the app can sync your pull requests.</p>
            </div>
          )}

          {!loading && !error && visibleCredentials.length > 0 && (
            <ul className="credentials-list">
              {visibleCredentials.map(c => {
                const tpl = templateFor(c.type, c.name);
                const cssTag = `${c.type}-${c.name}`.toLowerCase().replace(/[^a-z0-9-]/g, '-');
                return (
                  <li key={c.id} className="credentials-row">
                    <div className="credentials-row__main">
                      <div className="credentials-row__heading">
                        <span className={`credentials-row__kind credentials-row__kind--${cssTag}`}>
                          {tpl?.displayName ?? `${c.type} / ${c.name}`}
                        </span>
                        <span className="credentials-row__instance">"{c.instanceName}"</span>
                        {c.label && <span className="credentials-row__label">{c.label}</span>}
                      </div>
                      <div className="credentials-row__preview">
                        <code>{c.preview}</code>
                      </div>
                      {tpl?.usageDescription && (
                        <div className="credentials-row__usage">{tpl.usageDescription}</div>
                      )}
                      {c.notes && <div className="credentials-row__notes">{c.notes}</div>}
                      <div className="credentials-row__timestamps">
                        Added {formatTimestamp(c.createdAt)} · updated {formatTimestamp(c.updatedAt)} · last used {formatTimestamp(c.lastUsedAt)}
                      </div>
                    </div>
                    <div className="credentials-row__actions">
                      <button
                        className="button button--secondary button--small"
                        onClick={() => setEditing(c)}
                        type="button"
                      >
                        Replace
                      </button>
                      <button
                        className="button button--danger button--small"
                        onClick={() => handleDelete(c.type, c.name, c.instanceName)}
                        type="button"
                      >
                        Delete
                      </button>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}

          {showProviderPicker && !loading && !error && providers.length > 0 && (
            <section className="credentials-tab__section">
              <h3 className="credentials-tab__title">Active LLM provider</h3>
              <p className="credentials-tab__subtitle">
                Picks which model drafts your AI PR reviews. Only providers with a configured key can be activated.
              </p>
              <div className="credentials-provider-grid">
                {providers.map(p => (
                  <label
                    key={p.providerId}
                    className={`credentials-provider${p.active ? ' credentials-provider--active' : ''}${p.configured ? '' : ' credentials-provider--disabled'}`}
                  >
                    <input
                      type="radio"
                      name="llm-provider"
                      value={p.providerId}
                      checked={p.active}
                      disabled={!p.configured}
                      onChange={() => handleProviderChange(p.providerId)}
                    />
                    <span className="credentials-provider__name">{p.displayName}</span>
                    <span className="credentials-provider__status">
                      {p.configured ? (p.active ? 'Active' : 'Ready') : 'No key'}
                    </span>
                  </label>
                ))}
              </div>
              <div className="credentials-editor__field">
                <label className="credentials-editor__label">Model</label>
                <input
                  className="credentials-editor__input"
                  type="text"
                  value={aiSettings.model}
                  placeholder={
                    aiSettings.provider === 'deepseek' ? 'e.g. deepseek-chat'
                    : aiSettings.provider === 'openai' ? 'e.g. gpt-4o'
                    : 'e.g. claude-opus-4-7'
                  }
                  onBlur={e => void handleModelChange(e.target.value)}
                  onChange={e => setAiSettingsState(s => ({ ...s, model: e.target.value }))}
                />
                <p className="credentials-editor__hint">
                  {aiSettings.provider === 'deepseek'
                    ? 'DeepSeek models: deepseek-chat (general), deepseek-reasoner (chain-of-thought). Leave blank for deepseek-chat.'
                    : aiSettings.provider === 'claude'
                    ? 'Claude models: claude-opus-4-7, claude-sonnet-4-6, claude-haiku-4-5. Leave blank for claude-opus-4-7.'
                    : 'Leave blank to use the provider\'s default model.'}
                </p>
              </div>
            </section>
          )}
        </>
      )}
    </div>
  );
}

export default CredentialsTab;
