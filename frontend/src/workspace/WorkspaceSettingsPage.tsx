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
import type {
  WatchedRepoDto,
  WorkModelDto,
  WorkspaceBehaviorDto,
  WorkspaceCardDto,
} from '../types';
import { WorkModelPicker } from './WorkModelPicker';
import { ConfirmDialog } from './ConfirmDialog';

const ARCHIVE_OPTIONS: { value: string; label: string }[] = [
  { value: '1h', label: 'After 1h' },
  { value: '1d', label: 'After 1d' },
  { value: '1w', label: 'After 1 week' },
  { value: 'never', label: 'Never' },
];

const DEFAULT_BEHAVIOR: WorkspaceBehaviorDto = {
  archiveIdleAfter: '1w',
  autoProposeTask: true,
  autoPromoteDecisions: false,
  newTopicNudge: true,
};

/** Workspace-scoped Settings — Repositories / AI defaults / Behavior /
 *  Danger zone. The layout matches the mockup; data wiring is partial
 *  in this commit: Repositories is live (read-only — add/remove still
 *  goes through the app-level WatchedReposPage); AI defaults is a
 *  hint pointing at the existing AI settings; Behavior toggles
 *  persist their selections through {@code /api/settings/workspace-
 *  behavior} but enforcement is a follow-up (the auto-archive
 *  sweeper / propose-task hook / decision promoter read these keys
 *  when those features land); Danger zone is disabled because
 *  there's exactly one workspace and the schema doesn't support
 *  archive/delete cleanly yet. */
function WorkspaceSettingsPage() {
  const [repos, setRepos] = useState<WatchedRepoDto[]>([]);
  const [behavior, setBehavior] = useState<WorkspaceBehaviorDto>(DEFAULT_BEHAVIOR);
  const [workspace, setWorkspace] = useState<WorkspaceCardDto | null>(null);
  const [nameDraft, setNameDraft] = useState<string>('');
  const [renamingState, setRenamingState] = useState<'idle' | 'saving'>('idle');
  const [renameError, setRenameError] = useState<string | null>(null);
  const [renameNotice, setRenameNotice] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [workModel, setWorkModel] = useState<WorkModelDto | null>(null);
  const [workModelError, setWorkModelError] = useState<string | null>(null);

  const runDelete = async () => {
    if (workspace === null || deleting) return;
    setDeleting(true);
    setDeleteError(null);
    try {
      await window.bridge.deleteWorkspace(workspace.id);
      // Route the user up to the workspaces landing once the row is
      // gone; the user chooses the next active workspace explicitly.
      window.location.reload();
    }
    catch (err) {
      setDeleteError(err instanceof Error ? err.message : String(err));
      setDeleting(false);
      setConfirmOpen(false);
    }
  };

  const refresh = useCallback(async () => {
    try {
      const [repoList, behaviorRecord, workspaces] = await Promise.all([
        window.bridge.getWatchedRepos(),
        window.bridge.getWorkspaceBehavior(),
        window.bridge.listWorkspaces(),
      ]);
      setRepos(repoList);
      setBehavior(behaviorRecord);
      const current = workspaces[0] ?? null;
      setWorkspace(current);
      setNameDraft(current?.name ?? '');
      if (current !== null) {
        // Pull the full Workspace record so we know the persisted
        // work-model override. The landing card DTO doesn't carry it
        // because most surfaces don't need to know.
        const full = await window.bridge.getWorkspace(current.id);
        setWorkModel(full?.workModel ?? null);
      }
      else {
        setWorkModel(null);
      }
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  }, []);

  const onRename = async (e?: React.FormEvent) => {
    if (e !== undefined) e.preventDefault();
    if (workspace === null) return;
    const trimmed = nameDraft.trim();
    if (trimmed.length === 0) {
      setRenameError('Name is required');
      return;
    }
    if (trimmed === workspace.name) {
      setRenameError(null);
      setRenameNotice(null);
      return;
    }
    setRenamingState('saving');
    setRenameError(null);
    setRenameNotice(null);
    try {
      await window.bridge.renameWorkspace(workspace.id, trimmed);
      setWorkspace({ ...workspace, name: trimmed });
      setNameDraft(trimmed);
      setRenameNotice('Saved');
    }
    catch (err) {
      setRenameError(err instanceof Error ? err.message : String(err));
    }
    finally {
      setRenamingState('idle');
    }
  };

  const persistWorkModel = async (next: WorkModelDto | null) => {
    if (workspace === null) return;
    const prev = workModel;
    // Optimistic update so the picker reflects the new pick
    // immediately. On failure we snap back + surface the error.
    setWorkModel(next);
    setWorkModelError(null);
    try {
      const saved = await window.bridge.setWorkspaceWorkModel(workspace.id, next);
      setWorkModel(saved.workModel);
    }
    catch (e) {
      setWorkModel(prev);
      setWorkModelError(e instanceof Error ? e.message : String(e));
    }
  };

  const persistBehavior = async (next: WorkspaceBehaviorDto) => {
    // Optimistic local update — the optimistic value persists if the
    // PUT succeeds; on failure the surfaced error explains why the
    // checkbox snapped back.
    const prev = behavior;
    setBehavior(next);
    try {
      const saved = await window.bridge.setWorkspaceBehavior(next);
      setBehavior(saved);
      setError(null);
    }
    catch (e) {
      setBehavior(prev);
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  useEffect(() => { void refresh(); }, [refresh]);

  return (
    <>
      <header className="workspace-pageheader">
        <div>
          <h1 className="workspace-pageheader__title">Settings</h1>
          <div className="workspace-pageheader__meta">
            workspace-scoped configuration · single-workspace mode
          </div>
        </div>
      </header>

      {error !== null && <div style={errorStyle} role="alert">{error}</div>}

      <section className="workspace-card" style={sectionStyle} aria-label="Workspace identity">
        <div className="workspace-card__head">
          <div className="workspace-card__title">Identity</div>
          <span style={mutedHintStyle}>display name · used on the rail and landing cards</span>
        </div>
        <p style={sectionDescStyle}>
          The workspace id is stable; only the display name changes.
        </p>
        {workspace === null ? (
          <div style={mutedHintStyle}>{loading ? 'Loading…' : 'No workspace.'}</div>
        ) : (
          <form onSubmit={onRename} style={identityFormStyle}>
            <label style={identityLabelStyle}>
              <span style={identityLabelTextStyle}>Workspace name</span>
              <input
                type="text"
                value={nameDraft}
                onChange={e => { setNameDraft(e.target.value); setRenameNotice(null); }}
                disabled={renamingState === 'saving'}
                maxLength={80}
                style={identityInputStyle}
                placeholder={workspace.name}
              />
            </label>
            {/* Immutable workspace id — read-only line right under the
                editable name so the rename/id-stability story is
                visible in the surface that does the renaming. */}
            <div style={identityIdRowStyle}>
              <span style={identityLabelTextStyle}>Workspace id</span>
              <code style={identityIdValueStyle} title={workspace.id}>
                {workspace.id}
              </code>
              <span style={identityIdHintStyle}>
                immutable · referenced from thread / task ids, urls, logs
              </span>
            </div>
            <div style={identityActionsStyle}>
              <button
                type="submit"
                disabled={renamingState === 'saving' || nameDraft.trim().length === 0 || nameDraft.trim() === workspace.name}
                style={renamingState === 'saving' || nameDraft.trim() === workspace.name
                  ? identitySaveDisabledStyle
                  : identitySaveStyle}
              >
                {renamingState === 'saving' ? 'Saving…' : 'Save'}
              </button>
              {renameError !== null && (
                <span style={renameErrorStyle}>{renameError}</span>
              )}
              {renameError === null && renameNotice !== null && (
                <span style={renameNoticeStyle}>{renameNotice}</span>
              )}
            </div>
          </form>
        )}
      </section>

      <section className="workspace-card" style={sectionStyle} aria-label="Work model">
        <div className="workspace-card__head">
          <div className="workspace-card__title">Work model</div>
          <span style={mutedHintStyle}>
            agent + model the workspace runs by default
          </span>
        </div>
        <p style={sectionDescStyle}>
          The agent that runs work in this workspace — threads, tasks,
          and review seats inherit it. Pick an <strong>agent</strong>,
          then its <strong>model</strong>. CLI agents and API providers
          are equal peers; a thread or task override (later phase) lands
          most-specific-wins.
        </p>
        {workspace === null ? (
          <div style={mutedHintStyle}>{loading ? 'Loading…' : 'No workspace.'}</div>
        ) : (
          <>
            <WorkModelPicker
              value={workModel}
              onChange={(next) => { void persistWorkModel(next); }}
            />
            {workModelError !== null && (
              <div style={renameErrorStyle}>{workModelError}</div>
            )}
          </>
        )}
      </section>

      <section className="workspace-card" style={sectionStyle} aria-label="Repositories">
        <div className="workspace-card__head">
          <div className="workspace-card__title">Repositories</div>
          <span style={mutedHintStyle}>
            edits on the global Settings → Repositories page
          </span>
        </div>
        <p style={sectionDescStyle}>
          The repos this workspace spans. Threads here can spawn Tasks
          (branches + worktrees) in any of them.
        </p>
        {loading ? (
          <div style={mutedHintStyle}>Loading…</div>
        ) : repos.length === 0 ? (
          <div style={mutedHintStyle}>
            No watched repos yet — add one from Settings → Repositories.
          </div>
        ) : (
          <ul style={repoListStyle}>
            {repos.map((r, i) => (
              <li key={r.id} style={repoRowStyle}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span style={repoAvatarStyle}>{r.owner.slice(0, 1).toUpperCase()}</span>
                  <span style={repoNameStyle}>{r.owner}/{r.repo}</span>
                </div>
                {i === 0 && <span style={defaultPillStyle}>default</span>}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="workspace-card" style={sectionStyle} aria-label="AI defaults">
        <div className="workspace-card__head">
          <div className="workspace-card__title">AI defaults</div>
          <span style={mutedHintStyle}>
            credential picks happen on Settings → AI review
          </span>
        </div>
        <p style={sectionDescStyle}>
          Which credential and skills threads here use unless overridden.
          Configured globally on the AI review settings page; the
          workspace inherits whatever's active there.
        </p>
        <ul style={defaultsListStyle}>
          <li style={defaultRowStyle}>
            <span style={defaultLabelStyle}>Default credential</span>
            <span style={defaultValueStyle}>follows Settings → AI review</span>
          </li>
          <li style={defaultRowStyle}>
            <span style={defaultLabelStyle}>Review credential</span>
            <span style={defaultValueStyle}>follows Settings → AI review</span>
          </li>
          <li style={defaultRowStyle}>
            <span style={defaultLabelStyle}>Workspace skills</span>
            <span style={defaultValueStyle}>edit on Settings → AI review → Review skills</span>
          </li>
        </ul>
      </section>

      <section className="workspace-card" style={sectionStyle} aria-label="Behavior">
        <div className="workspace-card__head">
          <div className="workspace-card__title">Behavior</div>
          <span style={mutedHintStyle}>persists now — enforcement follows feature-by-feature</span>
        </div>
        <ul style={behaviorListStyle}>
          <li style={behaviorRowStyle}>
            <div>
              <div style={behaviorLabelStyle}>Archive idle threads</div>
              <div style={mutedHintStyle}>
                idle threads compact + drop from the list
              </div>
            </div>
            <div role="tablist" style={pillGroupStyle}>
              {ARCHIVE_OPTIONS.map(opt => (
                <button
                  key={opt.value}
                  type="button"
                  role="tab"
                  aria-selected={behavior.archiveIdleAfter === opt.value}
                  style={pillStyle(behavior.archiveIdleAfter === opt.value)}
                  onClick={() => persistBehavior({ ...behavior, archiveIdleAfter: opt.value })}
                  disabled={loading}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </li>
          <li style={behaviorRowStyle}>
            <div>
              <div style={behaviorLabelStyle}>Auto-propose Task on code work</div>
              <div style={mutedHintStyle}>
                offer a branch when a Discussion thread is about to edit files
              </div>
            </div>
            <Toggle
              checked={behavior.autoProposeTask}
              onToggle={() => persistBehavior({
                ...behavior, autoProposeTask: !behavior.autoProposeTask,
              })}
              disabled={loading}
            />
          </li>
          <li style={behaviorRowStyle}>
            <div>
              <div style={behaviorLabelStyle}>Auto-promote decisions to memory</div>
              <div style={mutedHintStyle}>
                approves new entries automatically; edits and confirm
              </div>
            </div>
            <Toggle
              checked={behavior.autoPromoteDecisions}
              onToggle={() => persistBehavior({
                ...behavior, autoPromoteDecisions: !behavior.autoPromoteDecisions,
              })}
              disabled={loading}
            />
          </li>
          <li style={behaviorRowStyle}>
            <div>
              <div style={behaviorLabelStyle}>New-topic nudge</div>
              <div style={mutedHintStyle}>
                suggest a new thread when a message looks unrelated
              </div>
            </div>
            <Toggle
              checked={behavior.newTopicNudge}
              onToggle={() => persistBehavior({
                ...behavior, newTopicNudge: !behavior.newTopicNudge,
              })}
              disabled={loading}
            />
          </li>
        </ul>
      </section>

      <section className="workspace-card" style={dangerSectionStyle} aria-label="Danger zone">
        <div className="workspace-card__head">
          <div className="workspace-card__title" style={{ color: '#cf1322' }}>Danger zone</div>
        </div>
        <ul style={dangerListStyle}>
          <li style={dangerRowStyle}>
            <div>
              <div style={dangerLabelStyle}>Archive workspace</div>
              <div style={mutedHintStyle}>
                hide this workspace and release its agents. Threads +
                memory are kept and restorable. (not wired yet)
              </div>
            </div>
            <button type="button" style={dangerButtonStyle} disabled>
              Archive
            </button>
          </li>
          <li style={dangerRowStyle}>
            <div>
              <div style={dangerLabelStyle}>Delete workspace</div>
              <div style={mutedHintStyle}>
                permanently remove the workspace and everything in it —
                threads, tasks, history, worktrees, and repo pins — and
                stop any running agents. PRs already on GitHub are untouched.
              </div>
              {deleteError !== null && (
                <div style={{ ...errorStyle, marginTop: 8 }} role="alert">{deleteError}</div>
              )}
            </div>
            <button
              type="button"
              style={deleting ? { ...dangerButtonStyle, opacity: 0.6, cursor: 'not-allowed' } : dangerButtonStyle}
              onClick={() => setConfirmOpen(true)}
              disabled={workspace === null || deleting}
            >
              {deleting ? 'Deleting…' : 'Delete…'}
            </button>
          </li>
        </ul>
      </section>
      {confirmOpen && workspace !== null && (
        <ConfirmDialog
          title={`Delete workspace "${workspace.name}"?`}
          body={'This removes the workspace and everything in it — every thread, '
            + 'its tasks, messages, history, worktrees, and repo pins — and stops '
            + 'any running agents.\n\nThis cannot be undone.'}
          confirmLabel={deleting ? 'Deleting…' : 'Delete workspace'}
          destructive
          busy={deleting}
          onConfirm={() => { void runDelete(); }}
          onCancel={() => setConfirmOpen(false)}
        />
      )}
    </>
  );
}

function Toggle({ checked, onToggle, disabled }: {
  checked: boolean;
  onToggle: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={onToggle}
      style={toggleStyle(checked)}
      disabled={disabled}
    >
      <span style={toggleThumbStyle(checked)} aria-hidden />
    </button>
  );
}

/* ── styles ─────────────────────────────────────────────────── */

const sectionStyle: React.CSSProperties = {
  marginBottom: 14,
};

const dangerSectionStyle: React.CSSProperties = {
  marginBottom: 14,
  border: '1px solid rgba(207, 19, 34, 0.25)',
  background: 'linear-gradient(180deg, rgba(254,242,242,0.75), rgba(255,255,255,0.8))',
};

const sectionDescStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--ws-text-2)',
  marginTop: 0,
  marginBottom: 12,
  lineHeight: 1.5,
};

const mutedHintStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ws-text-3)',
};

const repoListStyle: React.CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
};

const repoRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  padding: '6px 0',
  borderBottom: '1px solid rgba(124, 58, 237, 0.05)',
};

const repoAvatarStyle: React.CSSProperties = {
  width: 20,
  height: 20,
  borderRadius: 5,
  background: 'rgba(124, 58, 237, 0.15)',
  color: 'var(--ws-accent-deep)',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 10,
  fontWeight: 700,
};

const repoNameStyle: React.CSSProperties = {
  fontSize: 13,
  color: 'var(--ws-text-1)',
};

const defaultPillStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 600,
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  padding: '2px 7px',
  borderRadius: 999,
  color: 'var(--ws-accent-deep)',
  background: 'var(--ws-accent-soft)',
};

const defaultsListStyle: React.CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

const defaultRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'baseline',
  fontSize: 12,
};

const defaultLabelStyle: React.CSSProperties = {
  color: 'var(--ws-text-1)',
  fontWeight: 500,
};

const defaultValueStyle: React.CSSProperties = {
  color: 'var(--ws-text-3)',
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 11,
};

const behaviorListStyle: React.CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
};

const behaviorRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  gap: 14,
};

const behaviorLabelStyle: React.CSSProperties = {
  fontSize: 13,
  color: 'var(--ws-text-1)',
  fontWeight: 500,
  marginBottom: 2,
};

const pillGroupStyle: React.CSSProperties = {
  display: 'inline-flex',
  background: 'rgba(255, 255, 255, 0.6)',
  border: '1px solid var(--ws-card-border)',
  borderRadius: 8,
  padding: 2,
};

function pillStyle(active: boolean): React.CSSProperties {
  return {
    padding: '4px 10px',
    fontSize: 11,
    fontWeight: 600,
    border: 'none',
    borderRadius: 6,
    background: active ? '#fff' : 'transparent',
    color: active ? 'var(--ws-text-1)' : 'var(--ws-text-3)',
    cursor: 'pointer',
    boxShadow: active ? '0 1px 3px rgba(67, 56, 202, 0.07)' : undefined,
  };
}

function toggleStyle(checked: boolean): React.CSSProperties {
  return {
    width: 32,
    height: 18,
    padding: 2,
    borderRadius: 999,
    border: 'none',
    background: checked ? 'var(--ws-accent)' : 'rgba(124, 58, 237, 0.18)',
    cursor: 'pointer',
    transition: 'background var(--ws-fast)',
    display: 'inline-flex',
    alignItems: 'center',
    flexShrink: 0,
  };
}

function toggleThumbStyle(checked: boolean): React.CSSProperties {
  return {
    width: 14,
    height: 14,
    borderRadius: '50%',
    background: '#fff',
    transform: `translateX(${checked ? 14 : 0}px)`,
    transition: 'transform var(--ws-fast)',
  };
}

const dangerListStyle: React.CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
};

const dangerRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  gap: 14,
};

const dangerLabelStyle: React.CSSProperties = {
  fontSize: 13,
  color: '#991b1b',
  fontWeight: 600,
  marginBottom: 2,
};

const dangerButtonStyle: React.CSSProperties = {
  padding: '6px 14px',
  fontSize: 12,
  fontWeight: 600,
  border: '1px solid rgba(207, 19, 34, 0.35)',
  borderRadius: 8,
  background: '#fff',
  color: '#cf1322',
  cursor: 'pointer',
};

const errorStyle: React.CSSProperties = {
  marginBottom: 12,
  padding: 10,
  background: 'rgba(207, 19, 34, 0.06)',
  border: '1px solid rgba(207, 19, 34, 0.4)',
  borderRadius: 8,
  color: '#cf1322',
  fontSize: 12,
};

const identityFormStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
  marginTop: 4,
};

const identityLabelStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

const identityLabelTextStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 600,
  letterSpacing: '0.04em',
  textTransform: 'uppercase',
  color: 'var(--text-3)',
};

const identityInputStyle: React.CSSProperties = {
  padding: '8px 10px',
  fontSize: 13,
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 8,
  background: '#fff',
  outline: 'none',
  color: 'var(--text-1)',
  maxWidth: 380,
};

const identityIdRowStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

const identityIdValueStyle: React.CSSProperties = {
  padding: '6px 10px',
  fontSize: 12,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  background: 'rgba(124, 58, 237, 0.06)',
  border: '1px solid rgba(124, 58, 237, 0.12)',
  borderRadius: 6,
  color: 'var(--text-1)',
  maxWidth: 380,
  width: 'fit-content',
};

const identityIdHintStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
};

const identityActionsStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
};

const identitySaveStyle: React.CSSProperties = {
  padding: '6px 14px',
  fontSize: 12,
  fontWeight: 600,
  border: 'none',
  borderRadius: 8,
  background: 'linear-gradient(135deg, #7c3aed, #6366f1)',
  color: '#fff',
  cursor: 'pointer',
};

const identitySaveDisabledStyle: React.CSSProperties = {
  ...identitySaveStyle,
  background: 'rgba(124, 58, 237, 0.22)',
  color: 'rgba(255,255,255,0.85)',
  cursor: 'not-allowed',
  opacity: 0.7,
};

const renameErrorStyle: React.CSSProperties = {
  fontSize: 12,
  color: '#cf1322',
};

const renameNoticeStyle: React.CSSProperties = {
  fontSize: 12,
  color: '#15803d',
};

export default WorkspaceSettingsPage;
