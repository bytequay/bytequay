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
import { ModelChip } from '../settings/SettingsShell';
import type {
  ModelSettingsDto,
  WorkspaceDto,
  WorkspaceRemoteDto,
  WorkspaceSettingsDto,
} from '../types';
import '../css/settings.css';
import '../css/workspaces.css';

export default function WorkspaceSettings({ workspace, onWorkspaceChanged, onRemoved }: {
  workspace: WorkspaceDto;
  onWorkspaceChanged: (workspace: WorkspaceDto) => void;
  onRemoved: () => void;
}) {
  const [settings, setSettings] = useState<WorkspaceSettingsDto | null>(null);
  const [models, setModels] = useState<ModelSettingsDto | null>(null);
  const [globalLogin, setGlobalLogin] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const report = (failure: unknown) =>
    setError(failure instanceof Error ? failure.message : String(failure));

  const load = useCallback(() => {
    window.bridge.getWorkspaceSettings(workspace.id)
      .then(value => {
        setSettings(value);
        setError(null);
      })
      .catch(failure => setError(failure instanceof Error ? failure.message : String(failure)));
  }, [workspace.id]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    void window.bridge.getModelSettings().then(setModels).catch(() => {});
    void window.bridge.getUserProfile()
      .then(profile => setGlobalLogin(profile.login))
      .catch(() => {});
  }, []);

  const remove = async () => {
    if (!window.confirm(`Remove ${workspace.name} from ByteQuay?`)) return;
    setError(null);
    try {
      await window.bridge.removeWorkspace(workspace.id);
      onRemoved();
    }
    catch (failure) {
      report(failure);
    }
  };

  return (
    <div className="ws-tab-page">
      <h1>Workspace settings</h1>
      <p className="sets-sub">
        Applies only to <strong>{workspace.name}</strong>. Anything set here overrides your
        global settings.
      </p>
      {error && <div className="sets-error" role="alert">{error}</div>}
      <div className="wss-cards">
        <WorkspaceCard
          workspace={workspace}
          onWorkspaceChanged={onWorkspaceChanged}
          onError={report}
        />
        <RemoteSourcesCard
          workspace={workspace}
          remotes={settings?.remotes ?? null}
          onChanged={load}
          onUnlinkUpstream={async () => {
            const changed = await window.bridge.setWorkspaceUpstream(workspace.id, null);
            onWorkspaceChanged(changed);
            load();
          }}
          onError={report}
        />
        <AgentModelCard
          workspaceId={workspace.id}
          settings={settings}
          models={models}
          onChanged={load}
          onError={report}
        />
        <GitHubTokenCard
          workspaceId={workspace.id}
          settings={settings}
          globalLogin={globalLogin}
          onChanged={load}
          onError={report}
        />
        <section className="sets-card wss-danger">
          <div className="sets-row">
            <div className="sets-row-main">
              <div className="sets-name">Remove workspace</div>
              <div className="sets-row-sub">
                Stops watching this repository and removes its workspace record.
              </div>
            </div>
            <button type="button" className="sets-btn is-danger" onClick={() => { void remove(); }}>
              Remove…
            </button>
          </div>
        </section>
      </div>
    </div>
  );
}

function WorkspaceCard({ workspace, onWorkspaceChanged, onError }: {
  workspace: WorkspaceDto;
  onWorkspaceChanged: (workspace: WorkspaceDto) => void;
  onError: (failure: unknown) => void;
}) {
  const [name, setName] = useState(workspace.name);

  useEffect(() => setName(workspace.name), [workspace.name]);

  const saveName = async () => {
    const trimmed = name.trim();
    if (!trimmed || trimmed === workspace.name) {
      setName(workspace.name);
      return;
    }
    try {
      onWorkspaceChanged(await window.bridge.renameWorkspace(workspace.id, trimmed));
    }
    catch (failure) {
      setName(workspace.name);
      onError(failure);
    }
  };

  return (
    <section className="sets-card">
      <div className="sets-card-head"><span className="sets-card-title">Workspace</span></div>
      <div className="wss-grid">
        <span className="wss-label">Workspace name</span>
        <input
          value={name}
          className="wss-input"
          aria-label="Workspace name"
          onChange={event => setName(event.target.value)}
          onBlur={() => { void saveName(); }}
          onKeyDown={event => { if (event.key === 'Enter') event.currentTarget.blur(); }}
        />
        <span className="wss-label">Local path</span>
        <input value={workspace.localRepositoryPath} readOnly className="wss-input is-wide sets-mono" aria-label="Local path" />
        <span className="wss-label">Default branch</span>
        <input value={workspace.defaultBranch} readOnly className="wss-input is-narrow sets-mono" aria-label="Default branch" />
      </div>
    </section>
  );
}

function RemoteSourcesCard({ workspace, remotes, onChanged, onUnlinkUpstream, onError }: {
  workspace: WorkspaceDto;
  remotes: WorkspaceRemoteDto[] | null;
  onChanged: () => void;
  onUnlinkUpstream: () => Promise<void>;
  onError: (failure: unknown) => void;
}) {
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);

  const add = async () => {
    setBusy(true);
    try {
      await window.bridge.addWorkspaceRemote(workspace.id, name.trim(), url.trim());
      setName('');
      setUrl('');
      onChanged();
    }
    catch (failure) {
      onError(failure);
    }
    finally {
      setBusy(false);
    }
  };

  const remove = async (remote: WorkspaceRemoteDto) => {
    setBusy(true);
    try {
      if (remote.role === 'UPSTREAM') {
        await onUnlinkUpstream();
      }
      else {
        await window.bridge.removeWorkspaceRemote(workspace.id, remote.name);
        onChanged();
      }
    }
    catch (failure) {
      onError(failure);
    }
    finally {
      setBusy(false);
    }
  };

  return (
    <section className="sets-card">
      <div className="sets-card-head">
        <span className="sets-card-title">Remote sources</span>
        <span className="sets-card-hint">
          extra remotes for browsing history and cherry-picking — like{' '}
          <span className="sets-mono">git remote add name url</span>
        </span>
      </div>
      {(remotes ?? []).map(remote => (
        <div className="sets-row" key={remote.name}>
          <div className="sets-row-main">
            <div className="sets-row-title">
              <span className="sets-name sets-mono">{remote.name}</span>
              {remote.role === 'ORIGIN' && (
                <span className="sets-badge is-neutral">YOUR FORK · PUSH TARGET</span>
              )}
              {remote.role === 'UPSTREAM' && (
                <span className="sets-badge is-neutral">LINKED UPSTREAM</span>
              )}
            </div>
            <div className="sets-row-sub sets-mono">{remote.url}</div>
          </div>
          {remote.role !== 'ORIGIN' && (
            <button
              type="button"
              className="sets-link"
              disabled={busy}
              onClick={() => { void remove(remote); }}
            >
              Remove
            </button>
          )}
        </div>
      ))}
      {remotes === null && <div className="sets-row sets-row-sub">Loading remotes…</div>}
      <div className="wss-remote-form">
        <label>
          Name
          <input
            value={name}
            placeholder="e.g. oss"
            className="sets-mono"
            onChange={event => setName(event.target.value)}
          />
        </label>
        <label className="sets-grow">
          Repository URL
          <input
            value={url}
            placeholder="https://github.com/owner/repo.git"
            className="sets-mono"
            onChange={event => setUrl(event.target.value)}
          />
        </label>
        <button
          type="button"
          className="sets-btn"
          disabled={busy || !name.trim() || !url.trim()}
          onClick={() => { void add(); }}
        >
          Add remote
        </button>
      </div>
      <div className="sets-card-note">
        Every source appears in the Commits tab&apos;s source switcher and can supply
        cherry-pick commits. <span className="sets-mono">origin</span> can&apos;t be removed —
        it&apos;s where pushes go.
      </div>
    </section>
  );
}

function AgentModelCard({ workspaceId, settings, models, onChanged, onError }: {
  workspaceId: string;
  settings: WorkspaceSettingsDto | null;
  models: ModelSettingsDto | null;
  onChanged: () => void;
  onError: (failure: unknown) => void;
}) {
  const [picking, setPicking] = useState(false);

  useEffect(() => {
    if (!picking) return;
    const close = () => setPicking(false);
    window.addEventListener('mousedown', close);
    return () => window.removeEventListener('mousedown', close);
  }, [picking]);

  if (settings === null) return null;
  const override = settings.agentModelOverride ?? null;
  const main = settings.mainModel;

  const set = (configId: string, model: string, effort: string | null) => {
    setPicking(false);
    window.bridge.setWorkspaceAgentModel(workspaceId, configId, model, effort)
      .then(onChanged)
      .catch(onError);
  };

  const revert = () => {
    window.bridge.clearWorkspaceAgentModel(workspaceId).then(onChanged).catch(onError);
  };

  const overrideConfig = override === null
    ? null
    : models?.providers.find(provider => provider.id === override.configId)
      ?? null;

  const pickerMenu = picking && models && (
    <div className="sets-menu wss-model-menu" onMouseDown={event => event.stopPropagation()}>
      {models.providers.map(provider => (
        <div key={provider.id}>
          <div className="sets-menu-label">{provider.label}</div>
          {provider.models.map(model => (
            <button
              type="button"
              key={model.id}
              className="sets-menu-item"
              onClick={() => set(
                provider.id,
                model.id,
                model.efforts.includes(provider.selectedEffort)
                  ? provider.selectedEffort
                  : model.defaultEffort ?? null,
              )}
            >
              <span className="sets-menu-name">{model.name}</span>
              {model.description && <span className="sets-menu-desc">{model.description}</span>}
            </button>
          ))}
        </div>
      ))}
      {models.apiKeys.map(apiKey => (
        <div key={apiKey.id}>
          <div className="sets-menu-label">{apiKey.provider} · {apiKey.alias}</div>
          {apiKey.models.map(model => (
            <button
              type="button"
              key={model}
              className="sets-menu-item"
              onClick={() => set(apiKey.id, model, null)}
            >
              <span className="sets-menu-name">{model}</span>
            </button>
          ))}
        </div>
      ))}
    </div>
  );

  return (
    <section className="sets-card">
      <div className="sets-card-head">
        <span className="sets-card-title">Agent model</span>
        <span className="sets-card-hint">used for every agent task in this workspace</span>
      </div>
      <div className="sets-row">
        <div className="sets-row-main">
          <div className="sets-row-title">
            <span className="sets-name">
              {override
                ? `${override.label} · ${override.modelLabel}${override.effort ? ` · ${override.effort}` : ''}`
                : `${main.label} · ${main.modelLabel}${main.effort ? ` · ${main.effort}` : ''}`}
            </span>
            {override
              ? <span className="sets-badge wss-override">WORKSPACE OVERRIDE</span>
              : <span className="sets-badge is-neutral">GLOBAL DEFAULT</span>}
          </div>
          <div className="sets-row-sub">
            {override
              ? `Overrides the global main model (${main.label} · ${main.modelLabel}${main.effort ? ` · ${main.effort}` : ''}) in this workspace only`
              : 'The global main model from Settings applies here'}
          </div>
        </div>
        {override && overrideConfig && (
          <ModelChip
            label={override.modelLabel}
            detail={override.effort ?? undefined}
            models={overrideConfig.models}
            selectedModel={override.model}
            selectedEffort={override.effort ?? undefined}
            onSelect={(model, effort) => set(override.configId, model, effort)}
          />
        )}
        {override
          ? <button type="button" className="sets-link sets-nowrap" onClick={revert}>Revert to global</button>
          : (
            <div className="sets-chip-wrap" onMouseDown={event => event.stopPropagation()}>
              <button
                type="button"
                className="sets-btn"
                onClick={() => setPicking(value => !value)}
              >
                Override for this workspace…
              </button>
              {pickerMenu}
            </div>
          )}
      </div>
      {override && (
        <div className="sets-card-note">
          The picker lists every available provider and configured API key — the same set as
          global settings.
        </div>
      )}
    </section>
  );
}

function GitHubTokenCard({ workspaceId, settings, globalLogin, onChanged, onError }: {
  workspaceId: string;
  settings: WorkspaceSettingsDto | null;
  globalLogin: string | null;
  onChanged: () => void;
  onError: (failure: unknown) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState('');
  const [saving, setSaving] = useState(false);
  const [copied, setCopied] = useState(false);

  if (settings === null) return null;
  const token = settings.githubToken ?? null;

  const save = async () => {
    if (!value.trim()) return;
    setSaving(true);
    try {
      await window.bridge.saveWorkspaceGitHubToken(workspaceId, value.trim());
      setValue('');
      setEditing(false);
      onChanged();
    }
    catch (failure) {
      onError(failure);
    }
    finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!window.confirm('Delete the workspace token and fall back to the global token?')) return;
    try {
      await window.bridge.deleteWorkspaceGitHubToken(workspaceId);
      onChanged();
    }
    catch (failure) {
      onError(failure);
    }
  };

  const copy = async () => {
    try {
      await window.bridge.copyWorkspaceGitHubToken(workspaceId);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    }
    catch (failure) {
      onError(failure);
    }
  };

  return (
    <section className="sets-card">
      <div className="sets-card-head">
        <span className="sets-card-title">GitHub token</span>
        <span className="sets-card-hint">for private forks or a different account</span>
      </div>
      <div className="sets-row">
        <div className="sets-row-main">
          <div className="sets-row-title">
            <span className="sets-name">{token ? 'Workspace token' : 'Using your global token'}</span>
            {token
              ? <span className="sets-badge wss-override">WORKSPACE OVERRIDE</span>
              : <span className="sets-badge is-neutral">GLOBAL DEFAULT</span>}
          </div>
          <div className="sets-row-sub">
            {token
              ? <>This workspace authenticates as <strong>{token.login}</strong> instead of the global token</>
              : <>Requests from this workspace authenticate as <strong>{globalLogin ?? 'the global account'}</strong></>}
          </div>
        </div>
        {!token && !editing && (
          <button type="button" className="sets-btn" onClick={() => setEditing(true)}>
            Use a workspace token…
          </button>
        )}
      </div>
      {token && !editing && (
        <div className="sets-token-row wss-token-row">
          <span className="sets-token sets-mono">{token.maskedToken}</span>
          <button type="button" className="sets-btn" onClick={() => { void copy(); }}>
            {copied ? 'Copied' : 'Copy'}
          </button>
          <button type="button" className="sets-btn" onClick={() => setEditing(true)}>Update…</button>
          <button type="button" className="sets-btn is-danger" onClick={() => { void remove(); }}>
            Delete
          </button>
        </div>
      )}
      {editing && (
        <div className="sets-token-row wss-token-row">
          <input
            type="password"
            value={value}
            placeholder="github_pat_…"
            className="sets-token-input sets-mono"
            aria-label="Workspace GitHub token"
            onChange={event => setValue(event.target.value)}
          />
          <button
            type="button"
            className="sets-btn-primary"
            disabled={saving || !value.trim()}
            onClick={() => { void save(); }}
          >
            {saving ? 'Saving…' : 'Save token'}
          </button>
          <button type="button" className="sets-btn" onClick={() => setEditing(false)}>Cancel</button>
        </div>
      )}
      <div className="sets-card-note">
        {token
          ? 'Deleting the workspace token falls back to the global token. Same rule as global: the full value is never shown.'
          : 'A workspace token shadows the global token for this workspace only.'}
      </div>
    </section>
  );
}
