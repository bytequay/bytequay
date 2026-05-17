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
import type { LocalRepoStatusDto, TaskGroupDto, TaskKindDto } from '../types';

type Props = {
  onClose: () => void;
  onCreated: () => void | Promise<void>;
  /** Pre-selects the group dropdown — used when the dialog is opened
   *  from a group view so the new task lands in that group by default. */
  initialGroupId?: string | null;
};

/** Shape of the work the dialog plans to do on submit. {@code workingDir}
 *  is filled in either from the existing local clone or — for unmapped
 *  rows — only after we clone into the app's default path. */
type Plan = {
  repo: LocalRepoStatusDto;
  needsClone: boolean;
};

/**
 * Modal that gathers the inputs for one new task and calls
 * {@code window.bridge.createTask}. The {@code initialPrompt} kicks off
 * the first turn synchronously on the backend, so a successful submit
 * returns a row that's already RUNNING.
 *
 * <p>Working directory is picked from the user's tracked repos rather
 * than typed by hand. Repos that haven't been cloned yet show a
 * "Clone on start" hint and trigger {@code cloneRepo} into the app's
 * default path before the task is created.
 */
export default function NewTaskDialog({ onClose, onCreated, initialGroupId }: Props) {
  const [kind] = useState<TaskKindDto>('CLI_AGENT');
  const [title, setTitle] = useState('');
  const [initialPrompt, setInitialPrompt] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [repos, setRepos] = useState<LocalRepoStatusDto[] | null>(null);
  const [reposError, setReposError] = useState<string | null>(null);
  const [selectedKey, setSelectedKey] = useState<string>('');
  const [cloneStatus, setCloneStatus] = useState<string | null>(null);

  const [groups, setGroups] = useState<TaskGroupDto[]>([]);
  const [groupId, setGroupId] = useState<string>(initialGroupId ?? '');

  // Esc dismisses the dialog the same way overlay-click does. We skip
  // while a submit is in-flight so the user can't strand a half-created
  // task by stabbing Escape mid-clone — the submit button is already
  // disabled in that window, and the dialog will close on its own when
  // onCreated() fires.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (submitting) return;
      e.preventDefault();
      onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, submitting]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.listLocalRepos();
        if (cancelled) return;
        setRepos(list);
        // Default-select the first usable repo so the form is one click
        // away from a working submit. Prefer mapped over unmapped — the
        // user is more likely to want a repo they've already cloned.
        const firstMapped = list.find(r => r.localClonePath != null);
        const fallback = list[0];
        const initial = firstMapped ?? fallback;
        if (initial) {
          setSelectedKey(repoKey(initial));
        }
      }
      catch (e) {
        if (cancelled) return;
        setReposError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const gs = await window.bridge.listTaskGroups();
        if (!cancelled) setGroups(gs);
      }
      catch {
        // Groups are optional — silently leave the dropdown empty.
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const plan = useMemo<Plan | null>(() => {
    if (!repos || !selectedKey) return null;
    const repo = repos.find(r => repoKey(r) === selectedKey);
    if (!repo) return null;
    return { repo, needsClone: repo.localClonePath == null };
  }, [repos, selectedKey]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    if (!title.trim()) {
      setError('Title is required.');
      return;
    }
    if (!plan) {
      setError('Pick a repository to work in.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      let workingDir = plan.repo.localClonePath;
      if (plan.needsClone) {
        setCloneStatus(`Cloning ${plan.repo.owner}/${plan.repo.repo}…`);
        const destination = await window.bridge.defaultClonePath(
          plan.repo.owner, plan.repo.repo);
        const cloned = await window.bridge.cloneRepo(
          plan.repo.owner, plan.repo.repo, destination);
        workingDir = cloned.localClonePath;
        setCloneStatus(null);
      }
      if (!workingDir) {
        throw new Error('Repo has no local path even after cloning.');
      }
      // Model is intentionally blank — Claude Code picks its own model
      // and reports it back through the stream; the task-detail page
      // surfaces that real value once the session emits its first event.
      await window.bridge.createTask({
        kind,
        provider: 'claude-code',
        model: '',
        title: title.trim(),
        workingDir,
        initialPrompt: initialPrompt.trim() || undefined,
        // Single-pick dropdown maps to a one-element array; the
        // M:N picker UI lands with the tasks-group page redesign.
        initialGroupIds: groupId ? [groupId] : undefined,
      });
      await onCreated();
    }
    catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setCloneStatus(null);
      setSubmitting(false);
    }
  };

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={dialogStyle} onClick={e => e.stopPropagation()}>
        <header style={dialogHeaderStyle}>
          <h2 style={dialogTitleStyle}>New task</h2>
          <button type="button" onClick={onClose} style={closeBtnStyle} aria-label="Close">×</button>
        </header>

        <form onSubmit={submit} style={formStyle}>
          <Field label="Title">
            <input
              type="text"
              value={title}
              onChange={e => setTitle(e.target.value)}
              placeholder="Add tracing to the order pipeline"
              style={inputStyle}
              autoFocus
            />
          </Field>

          <Field
            label="Repository"
            hint={plan?.needsClone
              ? 'Not cloned yet — we will clone into the app default path before starting.'
              : 'Pick a tracked repo. Manage the list in the Repos tab.'}
          >
            {reposError && (
              <div style={errorBoxStyle}>Couldn't load repos: {reposError}</div>
            )}
            {repos === null && !reposError && (
              <div style={mutedHintStyle}>Loading repos…</div>
            )}
            {repos !== null && repos.length === 0 && (
              <div style={mutedHintStyle}>
                No tracked repos yet. Add one in the Repos tab, then come back here.
              </div>
            )}
            {repos !== null && repos.length > 0 && (
              <select
                value={selectedKey}
                onChange={e => setSelectedKey(e.target.value)}
                style={inputStyle}
              >
                {repos.map(r => (
                  <option key={repoKey(r)} value={repoKey(r)}>
                    {r.owner}/{r.repo}
                    {r.localClonePath ? ` — ${r.localClonePath}` : ' (not cloned)'}
                  </option>
                ))}
              </select>
            )}
          </Field>

          <Field label="Kind">
            <select value={kind} disabled style={inputStyle}>
              <option value="CLI_AGENT">Claude Code (CLI)</option>
              <option value="LOGIC_LOOP" disabled>Logic loop (coming soon)</option>
            </select>
          </Field>

          <Field
            label="Group"
            hint="Optional. Pins this task to one of your task groups."
          >
            <select
              value={groupId}
              onChange={e => setGroupId(e.target.value)}
              style={inputStyle}
            >
              <option value="">— None —</option>
              {groups.map(g => (
                <option key={g.id} value={g.id}>
                  {g.glyph ? `${g.glyph}  ` : ''}{g.name}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Initial prompt" hint="Optional. Sent as the first user turn once the session is up.">
            <textarea
              value={initialPrompt}
              onChange={e => setInitialPrompt(e.target.value)}
              placeholder="Describe the change you want…"
              rows={5}
              style={{ ...inputStyle, fontFamily: 'inherit', resize: 'vertical' }}
            />
          </Field>

          {cloneStatus && (
            <div style={infoBoxStyle}>{cloneStatus}</div>
          )}
          {error && (
            <div style={errorBoxStyle}>{error}</div>
          )}

          <footer style={footerStyle}>
            <button type="button" onClick={onClose} style={cancelBtnStyle} disabled={submitting}>
              Cancel
            </button>
            <button
              type="submit"
              style={submitBtnStyle}
              disabled={submitting || !plan}
              title={!plan ? 'Pick a repository first' : undefined}
            >
              {submitting
                ? (plan?.needsClone ? 'Cloning…' : 'Starting…')
                : (plan?.needsClone ? 'Clone & start' : 'Start task')}
            </button>
          </footer>
        </form>
      </div>
    </div>
  );
}

function repoKey(r: LocalRepoStatusDto): string {
  return `${r.owner}/${r.repo}`;
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label style={fieldStyle}>
      <span style={fieldLabelStyle}>{label}</span>
      {children}
      {hint && <span style={fieldHintStyle}>{hint}</span>}
    </label>
  );
}

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(17, 24, 39, 0.5)',
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'center',
  paddingTop: 80,
  zIndex: 1000,
};
const dialogStyle: React.CSSProperties = {
  width: 'min(560px, 92vw)',
  background: 'var(--bg-panel)',
  color: 'var(--text-1)',
  borderRadius: 10,
  boxShadow: '0 20px 60px rgba(0, 0, 0, 0.25)',
  overflow: 'hidden',
};
const dialogHeaderStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  padding: '16px 20px',
  borderBottom: '1px solid var(--border)',
};
const dialogTitleStyle: React.CSSProperties = {
  margin: 0, fontSize: 18, fontWeight: 600, color: 'var(--text-1)',
};
const closeBtnStyle: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  fontSize: 28,
  lineHeight: 1,
  color: 'var(--text-3)',
  cursor: 'pointer',
  padding: 0,
};
const formStyle: React.CSSProperties = { padding: 20, display: 'flex', flexDirection: 'column', gap: 14 };
const fieldStyle: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 4 };
const fieldLabelStyle: React.CSSProperties = { fontSize: 12, fontWeight: 600, color: 'var(--text-2)' };
const fieldHintStyle: React.CSSProperties = { fontSize: 11, color: 'var(--text-3)' };
const mutedHintStyle: React.CSSProperties = { fontSize: 12, color: 'var(--text-3)', padding: '6px 0' };
const inputStyle: React.CSSProperties = {
  padding: '8px 10px',
  background: 'var(--bg-input)',
  color: 'var(--text-1)',
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  fontSize: 14,
  width: '100%',
  boxSizing: 'border-box',
};
const errorBoxStyle: React.CSSProperties = {
  padding: '8px 12px',
  background: '#FEF2F2',
  color: '#991B1B',
  border: '1px solid #FCA5A5',
  borderRadius: 6,
  fontSize: 13,
};
const infoBoxStyle: React.CSSProperties = {
  padding: '8px 12px',
  background: '#EFF6FF',
  color: '#1E40AF',
  border: '1px solid #BFDBFE',
  borderRadius: 6,
  fontSize: 13,
};
const footerStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: 8,
  marginTop: 4,
};
const cancelBtnStyle: React.CSSProperties = {
  padding: '8px 14px',
  background: 'var(--bg-btn-secondary)',
  color: 'var(--text-2)',
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  cursor: 'pointer',
};
const submitBtnStyle: React.CSSProperties = {
  padding: '8px 14px',
  background: 'var(--accent)',
  color: '#fff',
  border: 'none',
  borderRadius: 6,
  fontWeight: 600,
  cursor: 'pointer',
};
