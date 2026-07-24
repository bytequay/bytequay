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
import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import type { SkillDto, SkillInput } from '../../types';
import SkillEditorModal, { classify, type ScopeBucket } from './skills/SkillEditorModal';

type Branch = 'development' | 'review';

const BRANCH_USAGE: Record<Branch, 'build' | 'review'> = {
  development: 'build',
  review: 'review',
};

const BRANCH_DEFS: { id: Branch; label: string; icon: string; meta: string; addLabel: string }[] = [
  {
    id: 'development',
    label: 'Development',
    icon: '⚒',
    meta: 'steers how build / task agents work',
    addLabel: '+ New development skill',
  },
  {
    id: 'review',
    label: 'Review',
    icon: '✦',
    meta: 'named voices a review panel can seat',
    addLabel: '+ New review skill',
  },
];

type RowMenuState = number | null;

/**
 * Settings → Skills surface. A two-level left nav — Development /
 * Review (by usage) then All / Global / per-repo (by scope) — scopes
 * the body to its slice of the flat skill list. Every row foregrounds
 * the trigger description ("▸ loads when …") so the "model-triggered,
 * not always-on" model is visible at a glance. Agent roles live on
 * their own read-only page; Add / Edit goes through {@link
 * SkillEditorModal}.
 */
function SkillsPage() {
  const [branch, setBranchRaw] = useState<Branch>('development');
  // Second-level selection: 'all' | 'global' | <repoSlug>.
  const [sub, setSub] = useState<string>('all');
  const [skills, setSkills] = useState<SkillDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<SkillDto | 'new' | null>(null);
  const [editingScope, setEditingScope] = useState<ScopeBucket>('global');
  const [editingRepo, setEditingRepo] = useState<string | undefined>(undefined);
  const [rowMenu, setRowMenu] = useState<RowMenuState>(null);

  const setBranch = (b: Branch) => { setBranchRaw(b); setSub('all'); };

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await window.bridge.listSkills();
      setSkills(list);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  // Skills in the active branch (by usage).
  const branchSkills = useMemo(
    () => skills.filter(s => s.usage === BRANCH_USAGE[branch]), [skills, branch]);

  // Repos that have at least one skill in the active branch — the
  // per-repo entries under the branch in the second-level nav.
  const branchRepos = useMemo(() => {
    const set = new Set<string>();
    branchSkills.forEach(s => { if (s.scope === 'repo' && s.repo) set.add(s.repo); });
    return Array.from(set).sort();
  }, [branchSkills]);

  const branchCount = (b: Branch) => skills.filter(s => s.usage === BRANCH_USAGE[b]).length;
  const subCount = (key: string) => {
    if (key === 'all') return branchSkills.length;
    if (key === 'global') return branchSkills.filter(s => s.scope === 'global').length;
    return branchSkills.filter(s => s.scope === 'repo' && s.repo === key).length;
  };

  const visible = useMemo(() => {
    if (sub === 'all') return branchSkills;
    if (sub === 'global') return branchSkills.filter(s => s.scope === 'global');
    return branchSkills.filter(s => s.scope === 'repo' && s.repo === sub);
  }, [branchSkills, sub]);

  // 'All' groups by scope: a Global/All-repos section then one per repo.
  const groups = useMemo(() => {
    if (sub !== 'all') return null;
    const globalLabel = branch === 'review' ? 'All repos' : 'Global';
    const out: { key: string; label: string; sublabel: string; rows: SkillDto[] }[] = [{
      key: 'global',
      label: globalLabel,
      sublabel: branch === 'review' ? 'any PR' : 'every workspace',
      rows: branchSkills.filter(s => s.scope === 'global'),
    }];
    branchRepos.forEach(r => out.push({
      key: r,
      label: r.includes('/') ? r.split('/')[1] : r,
      sublabel: r,
      rows: branchSkills.filter(s => s.scope === 'repo' && s.repo === r),
    }));
    return out.filter(g => g.rows.length > 0);
  }, [sub, branch, branchSkills, branchRepos]);

  const openAdd = (scope: ScopeBucket, repo?: string) => {
    setEditingScope(scope);
    setEditingRepo(repo);
    setEditing('new');
  };

  const openAddForSub = () => {
    if (sub !== 'all' && sub !== 'global') openAdd('repos', sub);
    else openAdd('global');
  };

  const openEdit = (row: SkillDto) => {
    setEditingScope(classify(row));
    setEditingRepo(undefined);
    setEditing(row);
  };

  const handleSave = async (input: SkillInput) => {
    if (editing === 'new') {
      await window.bridge.createSkill(input);
    }
    else if (editing !== null) {
      await window.bridge.updateSkill(editing.id, input);
    }
    setEditing(null);
    await reload();
  };

  const handleDelete = async (id: number) => {
    if (!confirm('Delete this skill? This cannot be undone.')) return;
    try {
      await window.bridge.deleteSkill(id);
      await reload();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const handleToggleEnabled = async (row: SkillDto) => {
    try {
      const next = await window.bridge.setSkillEnabled(row.id, !row.enabled);
      setSkills(prev => prev.map(s => s.id === row.id ? next : s));
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const renderRow = (row: SkillDto) => (
    <SkillRow
      key={row.id}
      row={row}
      menuOpen={rowMenu === row.id}
      onMenu={() => setRowMenu(rowMenu === row.id ? null : row.id)}
      onCloseMenu={() => setRowMenu(null)}
      onToggle={() => { void handleToggleEnabled(row); }}
      onEdit={() => openEdit(row)}
      onDelete={() => { void handleDelete(row.id); }}
    />
  );

  const activeDef = BRANCH_DEFS.find(b => b.id === branch)!;
  const subLabel = sub === 'all' ? 'All'
      : sub === 'global' ? (branch === 'review' ? 'All repos' : 'Global')
      : sub.includes('/') ? sub.split('/')[1] : sub;

  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">Skills</h2>
          <div className="settings-shell-page__subtitle">
            Skills are <strong>model-triggered</strong>, not always-on. Development skills
            steer how build / task agents work; Review skills are named voices a review
            panel can seat. Agent roles live on their own page.
          </div>
        </div>
      </div>

      <div style={layoutStyle}>
        <nav style={navStyle} aria-label="Skill surfaces">
          {BRANCH_DEFS.map(def => {
            const active = branch === def.id;
            const subItems = [
              { k: 'all', l: 'All' },
              { k: 'global', l: def.id === 'review' ? 'All repos' : 'Global' },
              ...branchRepos.map(r => ({ k: r, l: r.includes('/') ? r.split('/')[1] : r })),
            ];
            return (
              <Fragment key={def.id}>
                <button
                  type="button"
                  onClick={() => setBranch(def.id)}
                  style={branchHeaderStyle(active)}
                >
                  <span style={branchLabelStyle}>{def.icon} {def.label}</span>
                  <span style={navCountStyle(active, branchCount(def.id))}>{branchCount(def.id)}</span>
                </button>
                {active && (
                  <div style={subNavStyle}>
                    {subItems.map(item => {
                      const on = sub === item.k;
                      return (
                        <button
                          key={item.k}
                          type="button"
                          onClick={() => setSub(item.k)}
                          style={subItemStyle(on)}
                        >
                          <span>{item.l}</span>
                          <span style={subCountStyle(on)}>{subCount(item.k)}</span>
                        </button>
                      );
                    })}
                  </div>
                )}
              </Fragment>
            );
          })}
        </nav>

        <section style={bodyStyle}>
          <div style={bodyHeadStyle}>
            <div>
              <div style={bodyTitleStyle}>{activeDef.label} · {subLabel}</div>
              <div style={bodyMetaStyle}>{activeDef.meta}</div>
            </div>
            <button
              type="button"
              className="button button--primary"
              onClick={openAddForSub}
            >
              {activeDef.addLabel}
            </button>
          </div>

          {error !== null && <div className="repo-error">{error}</div>}

          {loading && <div className="settings-loading">Loading…</div>}

          {!loading && visible.length === 0 && error === null && (
            <div style={emptyStyle}>No {branch} skills here yet.</div>
          )}

          {sub === 'all' && groups !== null
            ? groups.map(group => (
                <div key={group.key} style={groupStyle}>
                  <div style={groupHeadStyle}>
                    <span style={groupNameStyle}>{group.label}</span>
                    <span style={groupMetaStyle}>{group.sublabel}</span>
                    {group.key !== 'global' && (
                      <button
                        type="button"
                        className="button button--secondary button--small"
                        onClick={() => openAdd('repos', group.key)}
                        style={{ marginLeft: 'auto' }}
                      >
                        + skill
                      </button>
                    )}
                  </div>
                  {group.rows.map(renderRow)}
                </div>
              ))
            : visible.map(renderRow)}
        </section>
      </div>

      {editing !== null && (
        <SkillEditorModal
          onClose={() => setEditing(null)}
          onSave={handleSave}
          initialScope={editingScope}
          initialUsage={BRANCH_USAGE[branch]}
          initialRepo={editingRepo}
          existing={editing === 'new' ? undefined : editing}
        />
      )}
    </>
  );
}

function SkillRow({
  row, menuOpen, onMenu, onCloseMenu, onToggle, onEdit, onDelete,
}: {
  row: SkillDto;
  menuOpen: boolean;
  onMenu: () => void;
  onCloseMenu: () => void;
  onToggle: () => void;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const bucket = classify(row);
  const triggerText = row.description !== ''
      ? row.description
      : 'no trigger set — add one so the agent knows when to load this';
  return (
    <div style={rowStyle(row.enabled)}>
      <button
        type="button"
        onClick={onToggle}
        style={toggleStyle(row.enabled)}
        title={row.enabled ? 'Disable skill (stays in the vault)' : 'Enable skill'}
        aria-pressed={row.enabled}
      >
        <span style={toggleKnobStyle(row.enabled)} />
      </button>
      <div style={rowMainStyle}>
        <div style={rowTitleStyle}>
          <span style={rowNameStyle}>{row.name}</span>
          <span style={rowScopeBadgeStyle(bucket)}>{scopeLabel(row, bucket)}</span>
          <span style={kindChipStyle(row.kind)}>{row.kind}</span>
          {row.isDefault && <span style={defaultChipStyle}>default</span>}
          {!row.enabled && <span style={mutedChipStyle}>disabled</span>}
        </div>
        <div style={rowTriggerStyle}>
          ▸ loads when <span style={triggerEmphasisStyle}>«{triggerText}»</span>
        </div>
        <div style={rowMetaStyle}>
          <span>{formatBytes(row.body.length)}</span>
          {row.source === 'ai_drafted' && (
            <>
              <span style={dotStyle}>·</span>
              <span>AI-drafted</span>
            </>
          )}
          <span style={dotStyle}>·</span>
          <span>edited {relativeTime(row.updatedAt)}</span>
        </div>
      </div>
      <div style={rowActionsStyle}>
        <button
          type="button"
          className="button button--secondary button--small"
          onClick={onEdit}
        >
          Edit
        </button>
        <div style={{ position: 'relative' }}>
          <button
            type="button"
            className="button button--secondary button--small"
            onClick={onMenu}
            title="More actions"
          >
            ⋯
          </button>
          {menuOpen && (
            <>
              <div style={menuScrimStyle} onClick={onCloseMenu} />
              <div style={menuStyle}>
                <button
                  type="button"
                  style={menuItemDangerStyle}
                  onClick={() => { onCloseMenu(); onDelete(); }}
                >
                  Delete skill
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function scopeLabel(row: SkillDto, bucket: ScopeBucket): string {
  const surface = row.usage === 'review' ? 'Review' : 'Dev';
  if (bucket === 'global') return (row.usage === 'review' ? 'All repos' : 'Global') + ' · ' + surface;
  return 'Repo · ' + surface;
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

function relativeTime(iso: string): string {
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return '—';
  const delta = Math.max(0, Math.floor((Date.now() - t) / 1000));
  if (delta < 60) return `${delta}s ago`;
  const m = Math.floor(delta / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  return `${d}d ago`;
}

const layoutStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '220px 1fr',
  gap: 16,
  alignItems: 'flex-start',
};

const navStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

function navCountStyle(active: boolean, count: number): React.CSSProperties {
  return {
    fontSize: 11,
    fontWeight: 600,
    padding: '1px 8px',
    borderRadius: 999,
    background: active
        ? 'var(--ws-accent, #7c3aed)'
        : count > 0 ? 'rgba(0,0,0,0.06)' : 'transparent',
    color: active ? '#fff' : 'var(--text-3)',
    minWidth: 18,
    textAlign: 'center',
  };
}

const bodyStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
  minWidth: 0,
};

const bodyHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  gap: 12,
};

const bodyTitleStyle: React.CSSProperties = {
  fontSize: 16,
  fontWeight: 700,
  color: 'var(--text-1)',
};

const bodyMetaStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--text-3)',
  marginTop: 2,
};

const emptyStyle: React.CSSProperties = {
  padding: '20px 16px',
  textAlign: 'center',
  fontSize: 13,
  color: 'var(--text-3)',
  border: '1px dashed rgba(0,0,0,0.10)',
  borderRadius: 10,
  background: 'rgba(0,0,0,0.02)',
};

const groupStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
};

const groupHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  marginTop: 6,
};

const groupNameStyle: React.CSSProperties = {
  fontSize: 13,
  fontWeight: 700,
  color: 'var(--text-1)',
};

const groupMetaStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
};

function rowStyle(enabled: boolean): React.CSSProperties {
  return {
    display: 'flex',
    gap: 12,
    padding: '12px 14px',
    border: '1px solid rgba(0,0,0,0.08)',
    borderRadius: 10,
    background: '#fff',
    opacity: enabled ? 1 : 0.6,
  };
}

function toggleStyle(enabled: boolean): React.CSSProperties {
  return {
    flexShrink: 0,
    width: 32,
    height: 18,
    borderRadius: 999,
    border: 'none',
    cursor: 'pointer',
    background: enabled ? 'var(--ws-accent, #7c3aed)' : 'rgba(0,0,0,0.18)',
    position: 'relative',
    padding: 0,
    alignSelf: 'center',
  };
}

function toggleKnobStyle(enabled: boolean): React.CSSProperties {
  return {
    position: 'absolute',
    top: 2,
    left: enabled ? 16 : 2,
    width: 14,
    height: 14,
    borderRadius: 999,
    background: '#fff',
    transition: 'left 140ms ease',
    boxShadow: '0 1px 2px rgba(0,0,0,0.18)',
  };
}

const rowMainStyle: React.CSSProperties = { flex: 1, minWidth: 0 };

const rowTitleStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  flexWrap: 'wrap',
};

const rowNameStyle: React.CSSProperties = {
  fontSize: 14,
  fontWeight: 600,
  color: 'var(--text-1)',
};

function rowScopeBadgeStyle(sc: ScopeBucket): React.CSSProperties {
  const palette: Record<ScopeBucket, { fg: string; bg: string }> = {
    global: { fg: 'var(--accent-deep)', bg: 'var(--accent-a10)' },
    repos: { fg: '#0d9488', bg: 'rgba(13, 148, 136, 0.10)' },
  };
  const p = palette[sc];
  return {
    fontSize: 10,
    fontWeight: 700,
    letterSpacing: '0.04em',
    padding: '1px 6px',
    borderRadius: 4,
    color: p.fg,
    background: p.bg,
  };
}

function kindChipStyle(kind: string): React.CSSProperties {
  const palette: Record<string, { fg: string; bg: string }> = {
    library: { fg: '#0369a1', bg: 'rgba(3, 105, 161, 0.10)' },
    persona: { fg: '#d97706', bg: 'rgba(217, 119, 6, 0.10)' },
    rubric: { fg: '#15803d', bg: 'rgba(22, 163, 74, 0.10)' },
  };
  const p = palette[kind] ?? { fg: 'var(--text-3)', bg: 'rgba(0,0,0,0.06)' };
  return {
    fontSize: 10,
    fontWeight: 600,
    padding: '1px 6px',
    borderRadius: 4,
    color: p.fg,
    background: p.bg,
  };
}

const defaultChipStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 600,
  padding: '1px 6px',
  borderRadius: 4,
  background: 'var(--accent-a10)',
  color: 'var(--accent-deep)',
};

const mutedChipStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 600,
  padding: '1px 6px',
  borderRadius: 4,
  background: 'rgba(0,0,0,0.06)',
  color: 'var(--text-3)',
  textTransform: 'uppercase',
};

const rowTriggerStyle: React.CSSProperties = {
  marginTop: 4,
  fontSize: 12,
  color: 'var(--text-2)',
};

const triggerEmphasisStyle: React.CSSProperties = {
  color: 'var(--text-1)',
  fontStyle: 'italic',
};

const rowMetaStyle: React.CSSProperties = {
  marginTop: 6,
  fontSize: 11,
  color: 'var(--text-4, #94a3b8)',
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  flexWrap: 'wrap',
};

const dotStyle: React.CSSProperties = { color: 'rgba(0,0,0,0.20)' };

const rowActionsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 6,
  alignSelf: 'flex-start',
};

const menuScrimStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  zIndex: 50,
};

const menuStyle: React.CSSProperties = {
  position: 'absolute',
  top: '100%',
  right: 0,
  marginTop: 4,
  minWidth: 160,
  padding: 4,
  background: '#fff',
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 8,
  boxShadow: '0 6px 18px rgba(0,0,0,0.10)',
  zIndex: 51,
};

const menuItemDangerStyle: React.CSSProperties = {
  display: 'block',
  width: '100%',
  padding: '6px 10px',
  fontSize: 12,
  fontWeight: 500,
  border: 'none',
  background: 'transparent',
  color: '#cf1322',
  textAlign: 'left',
  cursor: 'pointer',
  borderRadius: 6,
};

function branchHeaderStyle(active: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    padding: '9px 12px',
    textAlign: 'left',
    border: active ? '1px solid var(--ws-accent, #7c3aed)' : '1px solid transparent',
    background: active ? 'var(--accent-a7)' : 'transparent',
    borderRadius: 8,
    cursor: 'pointer',
    color: 'var(--text-1)',
  };
}

const branchLabelStyle: React.CSSProperties = { fontSize: 13, fontWeight: 700 };

const subNavStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  margin: '2px 0 6px 10px',
  paddingLeft: 8,
  borderLeft: '1px solid rgba(0,0,0,0.08)',
};

function subItemStyle(active: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    padding: '5px 10px',
    textAlign: 'left',
    border: 'none',
    background: active ? 'var(--accent-a10)' : 'transparent',
    borderRadius: 6,
    cursor: 'pointer',
    fontSize: 12.5,
    fontWeight: active ? 600 : 400,
    color: active ? 'var(--text-1)' : 'var(--text-2)',
  };
}

function subCountStyle(active: boolean): React.CSSProperties {
  return {
    fontSize: 10,
    fontWeight: 600,
    color: active ? 'var(--text-2)' : 'var(--text-4, #94a3b8)',
    fontVariantNumeric: 'tabular-nums',
  };
}

export default SkillsPage;
