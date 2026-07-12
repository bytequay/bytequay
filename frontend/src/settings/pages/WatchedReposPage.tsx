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
import Avatar from '../../Avatar';
import SettingCard from '../shared/SettingCard';

type Tab = 'repos' | 'tokens';

type Props = {
  workspaceId?: string | null;
};

function WatchedReposPage({ workspaceId }: Props) {
  const wsId = workspaceId?.trim() ?? '';
  const hasWorkspace = wsId.length > 0;
  const [tab, setTab] = useState<Tab>('repos');
  const [repos, setRepos] = useState<WatchedRepoDto[]>([]);
  const [workspaceRepos, setWorkspaceRepos] = useState<WorkspaceRepoDto[]>([]);
  const [tokens, setTokens] = useState<CredentialDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [removing, setRemoving] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [freshRepos, freshTokens, freshWorkspaceRepos] = await Promise.all([
        window.bridge.getWatchedRepos(),
        window.bridge.listCredentials('REPO'),
        // Pre-fetch in parallel; a backend that doesn't yet have the
        // workspace_repos endpoint shouldn't blank the rest of the
        // settings page, so we swallow the error here and surface it
        // only at the toggle level.
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

  const setRepoAutoFix = async (owner: string, repo: string, enabled: boolean) => {
    if (!hasWorkspace) {
      throw new Error('Choose a workspace before changing auto-fix.');
    }
    const updated = await window.bridge.setWorkspaceRepoAutoFix(
      wsId, owner, repo, enabled);
    setWorkspaceRepos(prev => {
      const next = prev.filter(r => r.repoFullName !== updated.repoFullName);
      next.push(updated);
      return next;
    });
  };

  useEffect(() => { void load(); }, [wsId]);

  // The add modal watches + maps the repo in one step; re-read the list to
  // pick up the new clone-backed row.
  const handleAdded = () => { void load(); };

  const handleRemove = async (owner: string, repo: string) => {
    const fullName = `${owner}/${repo}`;
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
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">Watched repos</h2>
          <div className="settings-shell-page__subtitle">
            Repos ByteQuay polls for open pull requests. Each repo can override the
            account-level token with a repo-specific PAT.
          </div>
        </div>
        {tab === 'repos' && (
          <button className="button button--primary" type="button" onClick={() => setAddOpen(true)}>
            + Add repo
          </button>
        )}
      </div>

      <div className="settings-page-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'repos'}
          className={`settings-page-tab${tab === 'repos' ? ' settings-page-tab--active' : ''}`}
          onClick={() => setTab('repos')}
        >
          Repos
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'tokens'}
          className={`settings-page-tab${tab === 'tokens' ? ' settings-page-tab--active' : ''}`}
          onClick={() => setTab('tokens')}
        >
          Tokens
        </button>
      </div>

      {loading && <div className="repo-loading">Loading…</div>}
      {error && <div className="repo-error">{error}</div>}

      {!loading && tab === 'repos' && (
        <ReposTab
          repos={repos}
          workspaceRepos={workspaceRepos}
          hasWorkspace={hasWorkspace}
          removing={removing}
          onRemove={handleRemove}
          onAutoFixChange={setRepoAutoFix}
        />
      )}
      {!loading && tab === 'tokens' && <TokensTab repos={repos} tokens={tokens} onChange={load} />}

      {addOpen && (
        <AddRepoModal
          watchedRepos={repos}
          onAdded={handleAdded}
          onClose={() => setAddOpen(false)}
        />
      )}
    </>
  );
}

function ReposTab({
  repos,
  workspaceRepos,
  hasWorkspace,
  removing,
  onRemove,
  onAutoFixChange,
}: {
  repos: WatchedRepoDto[];
  workspaceRepos: WorkspaceRepoDto[];
  hasWorkspace: boolean;
  removing: string | null;
  onRemove: (owner: string, repo: string) => void;
  onAutoFixChange: (owner: string, repo: string, enabled: boolean) => Promise<void>;
}) {
  const wsByFullName = useMemo(() => {
    const m = new Map<string, WorkspaceRepoDto>();
    for (const r of workspaceRepos) m.set(r.repoFullName, r);
    return m;
  }, [workspaceRepos]);

  if (repos.length === 0) {
    return (
      <SettingCard>
        <div className="settings-shell-page__subtitle" style={{ padding: '20px 0' }}>
          No repos watched yet. Add one to start seeing PRs in your inbox.
        </div>
      </SettingCard>
    );
  }
  return (
    <SettingCard
      hint={
        <>
          The <em>headless auto-fix</em> toggle is off by default. When
          enabled, ByteQuay queues an agent turn against this repo's
          failing-CI tasks; the agent still parks at the publish gate
          (no silent push). Leave it off until you trust auto-fix for a
          given repo.
        </>
      }
    >
      {repos.map(r => {
        const fullName = `${r.owner}/${r.repo}`;
        const workspaceRepo = wsByFullName.get(fullName);
        return (
          <div key={r.id} className="watched-row">
            <Avatar login={r.owner} size={20} className="avatar--repo" />
            <div className="watched-row__name">{fullName}</div>
            <AutoFixToggle
              fullName={fullName}
              workspaceRepo={workspaceRepo}
              hasWorkspace={hasWorkspace}
              onChange={enabled => onAutoFixChange(r.owner, r.repo, enabled)}
            />
            <button
              className="button button--danger"
              type="button"
              disabled={removing === fullName}
              onClick={() => onRemove(r.owner, r.repo)}
            >
              {removing === fullName ? 'Removing…' : 'Remove'}
            </button>
          </div>
        );
      })}
    </SettingCard>
  );
}

function AutoFixToggle({
  fullName,
  workspaceRepo,
  hasWorkspace,
  onChange,
}: {
  fullName: string;
  workspaceRepo: WorkspaceRepoDto | undefined;
  hasWorkspace: boolean;
  onChange: (enabled: boolean) => Promise<void>;
}) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const enabled = workspaceRepo?.autoFixEnabled === true;
  // Repo isn't attached to the workspace yet — the V73 backfill
  // covers existing repos, but ones added after the migration land
  // outside it. Surface a hint so the user knows why the toggle is
  // greyed out instead of guessing the backend was sleeping.
  const attached = hasWorkspace && workspaceRepo !== undefined;
  const flip = async () => {
    setSaving(true);
    setError(null);
    try {
      await onChange(!enabled);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };
  return (
    <label
      style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }}
      title={attached
        ? `Headless auto-fix on CI failure for ${fullName}`
        : hasWorkspace
          ? `${fullName} isn't attached to this workspace yet`
          : 'Choose a workspace before changing auto-fix'}
    >
      <input
        type="checkbox"
        checked={enabled}
        disabled={saving || !attached}
        onChange={() => { void flip(); }}
      />
      <span>{saving ? 'Saving…' : 'Headless auto-fix'}</span>
      {error !== null && <span style={{ color: '#b91c1c', fontStyle: 'italic' }}>{error}</span>}
    </label>
  );
}

function TokensTab({
  repos,
  tokens,
  onChange,
}: {
  repos: WatchedRepoDto[];
  tokens: CredentialDto[];
  onChange: () => Promise<void>;
}) {
  const tokensByName = useMemo(() => {
    const m = new Map<string, CredentialDto>();
    for (const t of tokens) m.set(t.name, t);
    return m;
  }, [tokens]);

  return (
    <SettingCard
      title="Per-repo tokens"
      hint={
        <>
          Each watched repo can use its own PAT — useful when one repo lives in a
          different org with stricter token scopes than your account-level GitHub
          token. Repos without a per-repo token fall back to the
          <em> Settings → GitHub token</em>.
        </>
      }
    >
      {repos.length === 0 ? (
        <div className="settings-shell-page__subtitle" style={{ padding: '20px 0' }}>
          Add a watched repo first, then come back to set its token.
        </div>
      ) : (
        repos.map(r => {
          const fullName = `${r.owner}/${r.repo}`;
          const existing = tokensByName.get(fullName);
          return (
            <RepoTokenRow
              key={r.id}
              fullName={fullName}
              existing={existing}
              onChange={onChange}
            />
          );
        })
      )}
    </SettingCard>
  );
}

function RepoTokenRow({
  fullName,
  existing,
  onChange,
}: {
  fullName: string;
  existing: CredentialDto | undefined;
  onChange: () => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = async () => {
    if (!value.trim()) {
      setError('Token must not be blank.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await window.bridge.upsertCredential({
        type: 'REPO',
        name: fullName,
        value: value.trim(),
        label: null,
        notes: null,
      });
      setEditing(false);
      setValue('');
      await onChange();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!confirm(`Delete the per-repo token for ${fullName}? It'll fall back to the account-level GitHub token.`)) return;
    setSaving(true);
    setError(null);
    try {
      await window.bridge.deleteCredential('REPO', fullName);
      await onChange();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="watched-row" style={{ flexWrap: 'wrap', alignItems: 'center' }}>
      <div className="watched-row__name" style={{ flex: '0 0 220px' }}>{fullName}</div>
      {!editing && existing && (
        <>
          <code style={{ flex: '1 1 auto', minWidth: 0, fontSize: 12, color: 'var(--text-3)' }}>
            {existing.preview}
          </code>
          <button className="button button--secondary" type="button" onClick={() => setEditing(true)}>
            Replace
          </button>
          <button className="button button--danger" type="button" onClick={() => void remove()} disabled={saving}>
            Remove
          </button>
        </>
      )}
      {!editing && !existing && (
        <>
          <div style={{ flex: '1 1 auto', fontSize: 12, color: 'var(--text-3)' }}>
            Falls back to the account-level token.
          </div>
          <button className="button button--secondary" type="button" onClick={() => setEditing(true)}>
            + Add token
          </button>
        </>
      )}
      {editing && (
        <>
          <input
            className="settings-input-number"
            style={{ flex: '1 1 auto', minWidth: 200 }}
            type="password"
            placeholder="ghp_…"
            value={value}
            onChange={e => setValue(e.target.value)}
            autoFocus
          />
          <button className="button button--primary" type="button" onClick={() => void save()} disabled={saving}>
            {saving ? 'Saving…' : 'Save'}
          </button>
          <button
            className="button button--secondary"
            type="button"
            onClick={() => { setEditing(false); setValue(''); setError(null); }}
            disabled={saving}
          >
            Cancel
          </button>
        </>
      )}
      {error && (
        <div style={{ flex: '1 1 100%', color: 'var(--col-attn, #c0303d)', fontSize: 12 }}>
          {error}
        </div>
      )}
    </div>
  );
}

export default WatchedReposPage;
