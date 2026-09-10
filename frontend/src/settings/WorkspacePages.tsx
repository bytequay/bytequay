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
import { RefreshCw } from 'lucide-react';
import type {
  AgentModelType,
  ModelSettingsDto,
  WorkspaceDto,
  WorkspaceRemoteDto,
  WorkspaceSettingsDto,
} from '../types';
import { providerStatusLabel } from './model';
import { AgentModelControls, typeLabel, type ModelChoice } from './ModelsPage';
import { AddAction, Badge, SettingsCard, SettingsRow, StatusDot, dotTone } from './primitives';

/** Every workspace page carries which workspace it edits, because the picker can point anywhere. */
function Eyebrow({ workspace }: { workspace: WorkspaceDto }) {
  return (
    <div className="setw-eyebrow">
      <span className="setw-avatar is-small" aria-hidden="true">{workspace.name.slice(0, 1)}</span>
      <span>{workspace.selectedOwner}/{workspace.selectedRepo}</span>
    </div>
  );
}

export function WorkspaceRepositoryPage({ workspace, settings, error, onAddRemote, onRemoveRemote, onSaveTarget, onResetTarget }: {
  workspace: WorkspaceDto;
  settings: WorkspaceSettingsDto | null;
  error: string | null;
  onAddRemote: (name: string, url: string) => void;
  onRemoveRemote: (remote: WorkspaceRemoteDto) => void;
  onSaveTarget: (branch: string) => void;
  onResetTarget: () => void;
}) {
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [branch, setBranch] = useState<string | null>(null);
  const target = settings?.commitsTarget ?? null;
  const branchValue = branch ?? target?.branch ?? '';

  return (
    <div className="setw-page">
      <Eyebrow workspace={workspace} />
      <h1>Repository</h1>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}

      <div className="setw-section"><span className="setw-section-title">Remotes</span></div>
      <SettingsCard footer={
        <AddAction label={adding ? 'Cancel' : 'Add remote'} onClick={() => setAdding(current => !current)} />
      }>
        {(settings?.remotes ?? []).map(remote => (
          <SettingsRow
            key={remote.name}
            title={<span className="setw-mono">{remote.name}</span>}
            badge={remote.role === 'ORIGIN'
              ? <Badge>your fork · push target</Badge>
              : remote.role === 'UPSTREAM' ? <Badge>linked upstream</Badge> : undefined}
            subtitle={<span className="setw-mono">{remote.url}</span>}
          >
            {remote.role !== 'ORIGIN' && (
              <button type="button" className="setw-btn is-quiet" onClick={() => onRemoveRemote(remote)}>Remove</button>
            )}
          </SettingsRow>
        ))}
        {settings === null && <div className="setw-row-empty">Loading remotes…</div>}
        {adding && (
          <div className="setw-remote-form">
            <input
              className="setw-input setw-mono"
              aria-label="Remote name"
              placeholder="name"
              value={name}
              onChange={event => setName(event.target.value)}
            />
            <input
              className="setw-input setw-mono"
              aria-label="Remote URL"
              placeholder="https://github.com/owner/repo.git"
              value={url}
              onChange={event => setUrl(event.target.value)}
            />
            <button
              type="button"
              className="setw-btn-primary"
              disabled={name.trim() === '' || url.trim() === ''}
              onClick={() => { onAddRemote(name.trim(), url.trim()); setName(''); setUrl(''); setAdding(false); }}
            >
              Add
            </button>
          </div>
        )}
      </SettingsCard>

      <div className="setw-section"><span className="setw-section-title">Commits</span></div>
      <SettingsCard>
        <SettingsRow
          title="Default target branch"
          badge={target?.overridden === false ? <Badge>automatic</Badge> : undefined}
          subtitle={target === null ? undefined : (
            <span className="setw-mono">{target.remote}/{target.branch} · remote picked for you</span>
          )}
        >
          <input
            className="setw-input setw-mono"
            aria-label="Default target branch"
            style={{ width: 150 }}
            value={branchValue}
            onChange={event => setBranch(event.target.value)}
          />
          <button
            type="button"
            className="setw-btn-primary"
            disabled={branchValue.trim() === '' || branchValue === target?.branch}
            onClick={() => { onSaveTarget(branchValue.trim()); setBranch(null); }}
          >
            Save
          </button>
          {target?.overridden === true && (
            <button
              type="button"
              className="setw-link"
              onClick={() => { setBranch(null); onResetTarget(); }}
            >
              Use automatic
            </button>
          )}
        </SettingsRow>
      </SettingsCard>
    </div>
  );
}

export function WorkspaceAgentsPage({
  workspace, settings, models, checking, checkedAt, error, onChoose, onUseGlobal, onCheck,
}: {
  workspace: WorkspaceDto;
  settings: WorkspaceSettingsDto | null;
  models: ModelSettingsDto | null;
  checking: boolean;
  checkedAt: string | null;
  error: string | null;
  onChoose: (type: AgentModelType, choice: ModelChoice) => void;
  onUseGlobal: (type: AgentModelType) => void;
  onCheck: () => void;
}) {
  return (
    <div className="setw-page">
      <Eyebrow workspace={workspace} />
      <h1>Agents</h1>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}

      <div className="setw-section"><span className="setw-section-title">Models for this workspace</span></div>
      <SettingsCard>
        {settings === null || models === null
          ? <div className="setw-row-empty">Loading models…</div>
          : settings.agents.map(agent => (agent.explicit ? (
            <SettingsRow key={agent.type} title={typeLabel(agent.type)} subtitle="Overridden for this workspace">
              <AgentModelControls
                agent={agent}
                settings={models}
                onChoose={choice => onChoose(agent.type, choice)}
              />
              <button type="button" className="setw-link" onClick={() => onUseGlobal(agent.type)}>Use global</button>
            </SettingsRow>
          ) : (
            <SettingsRow
              key={agent.type}
              title={typeLabel(agent.type)}
              subtitle={`${agent.modelLabel}${agent.effort ? ` · ${agent.effort}` : ''}`}
            >
              <Badge>global default</Badge>
              <button
                type="button"
                className="setw-btn"
                onClick={() => onChoose(agent.type, {
                  configId: agent.configId,
                  model: agent.model,
                  effort: agent.effort ?? null,
                })}
              >
                Override…
              </button>
            </SettingsRow>
          )))}
      </SettingsCard>

      <div className="setw-section"><span className="setw-section-title">Providers</span></div>
      <SettingsCard footer={
        <div className="setw-foot-row">
          <span>{checkedAt === null ? 'Readiness is probed fresh, never cached' : `Last checked ${checkedAt}`}</span>
          <button type="button" className="setw-btn" disabled={checking} onClick={onCheck}>
            <RefreshCw size={12} strokeWidth={2.2} color="#6e6e73" />
            <span>{checking ? 'Checking…' : 'Check again'}</span>
          </button>
        </div>
      }>
        {(models?.providers ?? []).map(provider => {
          const status = providerStatusLabel(provider.readiness, checking);
          return (
            <SettingsRow
              key={provider.id}
              title={`${provider.label} CLI`}
              subtitle={
                <span className="setw-status">
                  <StatusDot tone={dotTone(status.tone)} />
                  <span>{status.label}{provider.version ? ` · v${provider.version}` : ''}</span>
                </span>
              }
            />
          );
        })}
      </SettingsCard>
    </div>
  );
}

export function WorkspaceTokenPage({ workspace, settings, login, copied, error, onSave, onDelete, onCopy }: {
  workspace: WorkspaceDto;
  settings: WorkspaceSettingsDto | null;
  login: string | null;
  copied: boolean;
  error: string | null;
  onSave: (token: string) => void;
  onDelete: () => void;
  onCopy: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState('');
  const token = settings?.githubToken ?? null;

  return (
    <div className="setw-page">
      <Eyebrow workspace={workspace} />
      <h1>Token</h1>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}
      <SettingsCard>
        <SettingsRow
          title="GitHub token"
          subtitle={token === null
            ? `${login ?? 'Your account'} · from Integrations → GitHub`
            : token.login}
        >
          {token === null && !editing && (
            <>
              <Badge>global default</Badge>
              <button type="button" className="setw-btn" onClick={() => setEditing(true)}>Use a workspace token…</button>
            </>
          )}
          {token !== null && !editing && (
            <>
              <button type="button" className="setw-btn" onClick={onCopy}>{copied ? 'Copied' : 'Copy'}</button>
              <button type="button" className="setw-btn" onClick={() => setEditing(true)}>Update…</button>
              <button type="button" className="setw-btn is-danger" onClick={onDelete}>Remove</button>
            </>
          )}
        </SettingsRow>
        {token !== null && !editing && (
          <div className="setw-row"><div className="setw-token setw-mono">{token.maskedToken}</div></div>
        )}
        {editing && (
          <div className="setw-row">
            <input
              type="password"
              value={value}
              aria-label="Workspace GitHub token"
              placeholder="github_pat_…"
              className="setw-input setw-mono"
              style={{ flex: 1, minWidth: 0 }}
              onChange={event => setValue(event.target.value)}
            />
            <button
              type="button"
              className="setw-btn-primary"
              disabled={value.trim() === ''}
              onClick={() => { onSave(value.trim()); setValue(''); setEditing(false); }}
            >
              Save
            </button>
            <button type="button" className="setw-btn" onClick={() => { setEditing(false); setValue(''); }}>Cancel</button>
          </div>
        )}
      </SettingsCard>
    </div>
  );
}

export function WorkspaceAdvancedPage({ workspace, busy, error, onUnwatch, onDelete }: {
  workspace: WorkspaceDto;
  busy: boolean;
  error: string | null;
  onUnwatch: () => void;
  onDelete: () => void;
}) {
  return (
    <div className="setw-page">
      <Eyebrow workspace={workspace} />
      <h1>Advanced</h1>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}
      <SettingsCard>
        <SettingsRow title="Unwatch workspace" subtitle="Moves it to Retired and keeps local data">
          <button type="button" className="setw-btn" disabled={busy} onClick={onUnwatch}>
            {busy ? 'Unwatching…' : 'Unwatch'}
          </button>
        </SettingsRow>
        <SettingsRow title="Delete local data" subtitle="Keeps the GitHub repository and fork">
          <button type="button" className="setw-btn is-danger" disabled={busy} onClick={onDelete}>Delete…</button>
        </SettingsRow>
      </SettingsCard>
    </div>
  );
}
