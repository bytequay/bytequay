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
import type { WatchedRepoDto } from '../types';

/** Workspace-scoped Settings — Repositories / AI defaults / Behavior /
 *  Danger zone. The layout matches the mockup; data wiring is partial
 *  in this commit: Repositories is live (read-only — add/remove still
 *  goes through the app-level WatchedReposPage); AI defaults is a
 *  hint pointing at the existing AI settings; Behavior toggles are
 *  UI-only stubs since the backend hooks they'd flip don't exist
 *  yet; Danger zone is disabled because there's exactly one
 *  workspace and the schema doesn't support archive/delete cleanly
 *  yet. Each section carries an explicit follow-up note so the user
 *  doesn't mistake a stub for a real control. */
function WorkspaceSettingsPage() {
  const [repos, setRepos] = useState<WatchedRepoDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setRepos(await window.bridge.getWatchedRepos());
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  }, []);

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
          <span style={mutedHintStyle}>placeholders — wiring lands later</span>
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
              {['After 1h', 'After 1d', 'After 1 week', 'Never'].map((label, i) => (
                <button
                  key={label}
                  type="button"
                  role="tab"
                  aria-selected={i === 1}
                  style={pillStyle(i === 1)}
                  disabled
                >
                  {label}
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
            <Toggle defaultChecked />
          </li>
          <li style={behaviorRowStyle}>
            <div>
              <div style={behaviorLabelStyle}>Auto-promote decisions to memory</div>
              <div style={mutedHintStyle}>
                approves new entries automatically; edits and confirm
              </div>
            </div>
            <Toggle defaultChecked={false} />
          </li>
          <li style={behaviorRowStyle}>
            <div>
              <div style={behaviorLabelStyle}>New-topic nudge</div>
              <div style={mutedHintStyle}>
                suggest a new thread when a message looks unrelated
              </div>
            </div>
            <Toggle defaultChecked />
          </li>
        </ul>
      </section>

      <section className="workspace-card" style={dangerSectionStyle} aria-label="Danger zone">
        <div className="workspace-card__head">
          <div className="workspace-card__title" style={{ color: '#cf1322' }}>Danger zone</div>
          <span style={mutedHintStyle}>
            multi-workspace not enabled — single-workspace mode
          </span>
        </div>
        <ul style={dangerListStyle}>
          <li style={dangerRowStyle}>
            <div>
              <div style={dangerLabelStyle}>Archive workspace</div>
              <div style={mutedHintStyle}>
                hide ByteQuay and release its agents. Threads + memory are
                kept and restorable.
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
                permanently remove the workspace, its memory, and thread
                history. Worktrees + PRs on GitHub are untouched.
              </div>
            </div>
            <button type="button" style={dangerButtonStyle} disabled>
              Delete…
            </button>
          </li>
        </ul>
      </section>
    </>
  );
}

function Toggle({ defaultChecked }: { defaultChecked: boolean }) {
  const [checked, setChecked] = useState(defaultChecked);
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => setChecked(c => !c)}
      style={toggleStyle(checked)}
      disabled
      title="Behavior wiring is a follow-up"
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

export default WorkspaceSettingsPage;
