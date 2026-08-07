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
import { useEffect, useMemo, useState } from 'react';
import type { CredentialDto, WatchedRepoDto, WorkspaceRepoDto } from '../../types';
import AddRepoModal from '../../AddRepoModal';
import SettingsPage from '../shared/SettingsPage';
import { InfoIcon, LockIcon, PlusIcon, TrashIcon } from '../shared/icons';

type Props = {
  workspaceId?: string | null;
};

function WatchedReposPage({ workspaceId }: Props) {
  const wsId = workspaceId?.trim() ?? '';
  const hasWorkspace = wsId.length > 0;
  const [repos, setRepos] = useState<WatchedRepoDto[]>([]);
  const [workspaceRepos, setWorkspaceRepos] = useState<WorkspaceRepoDto[]>([]);
  const [tokens, setTokens] = useState<CredentialDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [removing, setRemoving] = useState<string | null>(null);
  /** Full name of the row whose per-repo token editor is open. */
  const [tokenFor, setTokenFor] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [freshRepos, freshTokens, freshWorkspaceRepos] = await Promise.all([
        window.bridge.getWatchedRepos(),
        window.bridge.listCredentials('REPO'),
        // Pre-fetch in parallel; a backend that doesn't yet have the
        // workspace_repos endpoint shouldn't blank the rest of the
        // page, so we swallow the error here and surface it only at
        // the toggle level.
        hasWorkspace
          ? window.bridge.listWorkspaceRepos(wsId).catch((): WorkspaceRepoDto[] => [])
          : Promise.resolve([]),
      ]);
      setRepos(freshRepos);
      setTokens(freshTokens);
      setWorkspaceRepos(freshWorkspaceRepos);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [wsId]);

  const wsByFullName = useMemo(() => {
    const m = new Map<string, WorkspaceRepoDto>();
    for (const r of workspaceRepos) m.set(r.repoFullName, r);
    return m;
  }, [workspaceRepos]);

  const tokensByName = useMemo(() => {
    const m = new Map<string, CredentialDto>();
    for (const t of tokens) m.set(t.name, t);
    return m;
  }, [tokens]);

  const setAutoFix = async (owner: string, repo: string, enabled: boolean) => {
    if (!hasWorkspace) throw new Error('Choose a workspace before changing auto-fix.');
    const updated = await window.bridge.setWorkspaceRepoAutoFix(wsId, owner, repo, enabled);
    setWorkspaceRepos(prev => [...prev.filter(r => r.repoFullName !== updated.repoFullName), updated]);
  };

  const handleRemove = async (owner: string, repo: string) => {
    const fullName = `${owner}/${repo}`;
    if (!confirm(
      `Remove ${fullName}?\n\nThis deletes its workspace and everything under it — `
      + 'threads, tasks, sync runs, agent logs — plus the local clone and its worktrees. '
      + 'This cannot be undone.',
    )) return;
    setRemoving(fullName);
    try {
      await window.bridge.removeWatchedRepo(owner, repo);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRemoving(null);
    }
  };

  return (
    <SettingsPage
      title="Watched repos"
      width={900}
      subtitle="Repos ByteQuay polls for open pull requests. Each row can override the account token and opt into headless auto-fix."
      action={
        <button className="sv2-btn sv2-btn--dark" type="button" style={{ marginTop: 4 }} onClick={() => setAddOpen(true)}>
          <PlusIcon size={13} />Add repo
        </button>
      }
    >
      <div className="sv2-note">
        <span className="sv2-note__icon"><InfoIcon size={14} /></span>
        <span>
          Headless auto-fix queues an agent turn against this repo’s failing-CI tasks. The agent
          still parks at the publish gate — nothing is pushed silently. Leave it off until you
          trust auto-fix for a repo.
        </span>
      </div>

      {loading && <div className="sv2-loading">Loading…</div>}
      {error !== null && <div className="sv2-error" role="alert">{error}</div>}

      {!loading && repos.length === 0 && (
        <div className="sv2-empty">
          <div className="sv2-empty__title">No repos watched</div>
          <div className="sv2-empty__body">Add one and ByteQuay starts polling its open pull requests.</div>
        </div>
      )}

      {repos.map(r => {
        const fullName = `${r.owner}/${r.repo}`;
        const workspaceRepo = wsByFullName.get(fullName);
        return (
          <div key={r.id}>
            <RepoRow
              fullName={fullName}
              cloned={r.localClonePath !== null}
              token={tokensByName.get(fullName)}
              workspaceRepo={workspaceRepo}
              hasWorkspace={hasWorkspace}
              removing={removing === fullName}
              tokenOpen={tokenFor === fullName}
              onToggleToken={() => setTokenFor(tokenFor === fullName ? null : fullName)}
              onAutoFix={enabled => setAutoFix(r.owner, r.repo, enabled)}
              onRemove={() => { void handleRemove(r.owner, r.repo); }}
            />
            {tokenFor === fullName && (
              <TokenEditor
                fullName={fullName}
                existing={tokensByName.get(fullName)}
                onDone={async () => { setTokenFor(null); await load(); }}
                onCancel={() => setTokenFor(null)}
              />
            )}
          </div>
        );
      })}

      <div className="sv2-foot-note">
        Rows without a per-repo token fall back to the account token in Credentials → Git PAT.
      </div>

      {addOpen && (
        <AddRepoModal
          watchedRepos={repos}
          onAdded={() => { void load(); }}
          onClose={() => setAddOpen(false)}
        />
      )}
    </SettingsPage>
  );
}

function RepoRow({
  fullName, cloned, token, workspaceRepo, hasWorkspace, removing, tokenOpen,
  onToggleToken, onAutoFix, onRemove,
}: {
  fullName: string;
  cloned: boolean;
  token: CredentialDto | undefined;
  workspaceRepo: WorkspaceRepoDto | undefined;
  hasWorkspace: boolean;
  removing: boolean;
  tokenOpen: boolean;
  onToggleToken: () => void;
  onAutoFix: (enabled: boolean) => Promise<void>;
  onRemove: () => void;
}) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const enabled = workspaceRepo?.autoFixEnabled === true;
  // The V73 backfill covers existing repos, but ones added after the
  // migration land outside the workspace. Say so rather than leaving a
  // greyed-out toggle with no explanation.
  const attached = hasWorkspace && workspaceRepo !== undefined;

  const flip = async () => {
    setSaving(true);
    setError(null);
    try {
      await onAutoFix(!enabled);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="sv2-repo" style={tokenOpen ? { borderRadius: '12px 12px 0 0' } : undefined}>
      <span className="sv2-repo__tile">{fullName.slice(0, 1)}</span>
      <span className="sv2-repo__main">
        <span className="sv2-repo__name">{fullName}</span>
        <span className="sv2-repo__meta">
          {cloned ? 'Local clone ready' : 'Watch only — no local clone'}
          {error !== null && <span style={{ color: '#cf222e' }}> · {error}</span>}
        </span>
      </span>

      {token !== undefined && (
        <span className="sv2-repo__token" title="Per-repo token">
          <LockIcon size={11} width={2} />
          <span className="sv2-mono">{token.preview}</span>
        </span>
      )}

      <button
        className="sv2-repo__autofix"
        type="button"
        role="switch"
        aria-checked={enabled}
        disabled={saving || !attached}
        title={attached
          ? `Headless auto-fix on CI failure for ${fullName}`
          : hasWorkspace
            ? `${fullName} isn't attached to this workspace yet`
            : 'Choose a workspace before changing auto-fix'}
        onClick={() => { void flip(); }}
      >
        <span style={{ color: enabled ? '#1a7f37' : '#8b949e' }}>
          {saving ? 'Saving…' : `Headless auto-fix ${enabled ? 'on' : 'off'}`}
        </span>
        <span className={'sv2-toggle' + (enabled ? ' sv2-toggle--on' : '')}><i /></span>
      </button>

      <button className="sv2-btn sv2-btn--sm" type="button" title="Per-repo token" onClick={onToggleToken}>
        Token
      </button>
      <button
        className="sv2-icon-btn"
        type="button"
        title="Stop watching"
        aria-label={`Stop watching ${fullName}`}
        disabled={removing}
        onClick={onRemove}
      >
        <TrashIcon size={13} />
      </button>
    </div>
  );
}

function TokenEditor({ fullName, existing, onDone, onCancel }: {
  fullName: string;
  existing: CredentialDto | undefined;
  onDone: () => Promise<void>;
  onCancel: () => void;
}) {
  const [value, setValue] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = async (action: () => Promise<unknown>): Promise<void> => {
    setSaving(true);
    setError(null);
    try {
      await action();
      await onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSaving(false);
    }
  };

  return (
    <div className="sv2-repo__editor">
      <input
        className="sv2-input"
        style={{ flex: 1 }}
        type="password"
        placeholder={existing === undefined ? 'ghp_…' : 'Paste a new token to replace'}
        aria-label={`Per-repo token for ${fullName}`}
        value={value}
        autoFocus
        onChange={e => setValue(e.target.value)}
      />
      <button
        className="sv2-btn sv2-btn--primary"
        type="button"
        disabled={saving || value.trim() === ''}
        onClick={() => { void run(() => window.bridge.upsertCredential({
          type: 'REPO', name: fullName, value: value.trim(), label: null, notes: null,
        })); }}
      >
        {saving ? 'Saving…' : 'Save'}
      </button>
      {existing !== undefined && (
        <button
          className="sv2-btn sv2-btn--danger"
          type="button"
          disabled={saving}
          onClick={() => {
            if (!confirm(`Delete the per-repo token for ${fullName}? It'll fall back to the account token.`)) return;
            void run(() => window.bridge.deleteCredential('REPO', fullName));
          }}
        >
          Remove
        </button>
      )}
      <button className="sv2-btn sv2-btn--sm" type="button" disabled={saving} onClick={onCancel}>Cancel</button>
      {error !== null && <span style={{ fontSize: 12, color: '#cf222e' }}>{error}</span>}
    </div>
  );
}

export default WatchedReposPage;
