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
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { SkillDto, SkillInput } from '../../types';
import SkillEditorModal, { classify, type ScopeBucket } from './skills/SkillEditorModal';

type Tab = 'global' | 'repos' | 'roles';

const TAB_DEFS: { id: Tab; label: string; meta: string; addLabel: string; emptyHint: string }[] = [
  {
    id: 'global',
    label: 'Global',
    meta: 'available to every workspace + agent',
    addLabel: '+ New skill',
    emptyHint: 'No global skills yet. Add one that should be available across every workspace.',
  },
  {
    id: 'repos',
    label: 'Repos',
    meta: 'scoped to a single owner/name',
    addLabel: '+ New skill',
    emptyHint: 'No per-repo skills yet. Add one tied to a watched repo.',
  },
  {
    id: 'roles',
    label: 'Roles',
    meta: 'always-on identity for an agent role',
    addLabel: '+ New skill',
    emptyHint: 'No role skills yet. Add one for reviewer / reviewee / scheduler / trunk.',
  },
];

/** Read-mostly role catalogue rendered on the Roles tab so the
 *  surface is informative even before the role-generation work
 *  lands. */
const ROLE_CARDS: { id: string; label: string; kind: string; can: string[]; cant: string[]; blurb: string }[] = [
  {
    id: 'trunk',
    label: 'Trunk',
    kind: 'fixed template',
    can: ['create_task', 'search', 'recall'],
    cant: ['edit files', 'push'],
    blurb: 'Orchestrates planning; cuts tasks but never writes code or pushes. Ships with the app — not editable here.',
  },
  {
    id: 'task',
    label: 'Task',
    kind: 'generated per task · frozen',
    can: ['edit files', 'push (gated)', 'comment'],
    cant: ['create_task', 'change role'],
    blurb: 'Composed at task creation from the task\'s repo / branch / PR; frozen onto the task so behaviour is reproducible.',
  },
  {
    id: 'reviewer',
    label: 'Reviewer',
    kind: 'composed · deferred',
    can: ['read diff', 'comment'],
    cant: ['edit files', 'push', 'create_task'],
    blurb: 'Composed base + persona + how-to × backend. Wiring lives with the review-panel work — surfaced here for visibility only.',
  },
  {
    id: 'lead',
    label: 'Lead',
    kind: 'composed · deferred',
    can: ['arbitrate panel', 'publish review'],
    cant: ['edit files', 'push code'],
    blurb: 'Final arbiter on the review panel. Same composed pattern as Reviewer; lands with the review-panel surface.',
  },
];

type RowMenuState = number | null;

/**
 * Settings → Skills surface. A kind nav (Global / Repos / Roles)
 * scopes the body to its own slice of the flat skill list. Every
 * skill row foregrounds the trigger description ("▸ loads when …")
 * so the "model-triggered, not always-on" model is visible at a
 * glance. Add / Edit goes through the {@link SkillEditorModal}.
 */
function SkillsPage() {
  const [tab, setTab] = useState<Tab>('global');
  const [skills, setSkills] = useState<SkillDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<SkillDto | 'new' | null>(null);
  const [editingScope, setEditingScope] = useState<ScopeBucket>('global');
  const [editingRepo, setEditingRepo] = useState<string | undefined>(undefined);
  const [rowMenu, setRowMenu] = useState<RowMenuState>(null);

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

  const counts = useMemo(() => {
    // counts reflect user-authored data, not the static role-info
    // cards (those live as a separate informational grid on the Roles
    // tab and don't make sense as a "you have N" badge).
    const c = { global: 0, repos: 0, roles: 0 };
    for (const s of skills) {
      const sc = classify(s);
      if (sc === 'global') c.global++;
      else if (sc === 'repos') c.repos++;
      else if (sc === 'role') c.roles++;
    }
    return c;
  }, [skills]);

  const visible = useMemo(() => {
    const wanted: ScopeBucket = tab === 'global' ? 'global'
        : tab === 'repos' ? 'repos'
        : 'role';
    return skills.filter(s => classify(s) === wanted);
  }, [skills, tab]);

  const grouped = useMemo(() => {
    if (tab !== 'repos') return [];
    const byKey = new Map<string, { label: string; sublabel: string; rows: SkillDto[] }>();
    for (const s of visible) {
      if (s.repo === null) continue;
      const key = s.repo;
      const label = s.repo.includes('/') ? s.repo.split('/')[1] : s.repo;
      let bucket = byKey.get(key);
      if (bucket === undefined) {
        bucket = { label, sublabel: s.repo, rows: [] };
        byKey.set(key, bucket);
      }
      bucket.rows.push(s);
    }
    return Array.from(byKey.entries()).map(([key, v]) => ({ key, ...v }));
  }, [visible, tab]);

  const openAdd = (scope: ScopeBucket, repo?: string) => {
    setEditingScope(scope);
    setEditingRepo(repo);
    setEditing('new');
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

  const activeDef = TAB_DEFS.find(t => t.id === tab)!;

  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">Skills</h2>
          <div className="settings-shell-page__subtitle">
            Skills are <strong>model-triggered</strong>, not always-on. Each one carries
            a trigger description; the agent decides whether to load the body based on
            the task. Must-always-hold facts belong in the workspace brain instead, so
            they don't overlap.
          </div>
        </div>
      </div>

      <div style={layoutStyle}>
        <nav style={navStyle} aria-label="Skill kinds">
          {TAB_DEFS.map(def => {
            const active = tab === def.id;
            const count = counts[def.id];
            return (
              <button
                key={def.id}
                type="button"
                onClick={() => setTab(def.id)}
                style={navItemStyle(active)}
              >
                <span style={navLabelStyle}>{def.label}</span>
                <span style={navCountStyle(active, count)}>{count}</span>
                <span style={navMetaStyle}>{def.meta}</span>
              </button>
            );
          })}
        </nav>

        <section style={bodyStyle}>
          <div style={bodyHeadStyle}>
            <div>
              <div style={bodyTitleStyle}>{activeDef.label}</div>
              <div style={bodyMetaStyle}>{activeDef.meta}</div>
            </div>
            <button
              type="button"
              className="button button--primary"
              onClick={() => openAdd(tab === 'global' ? 'global' : tab === 'repos' ? 'repos' : 'role')}
            >
              {activeDef.addLabel}
            </button>
          </div>

          {error !== null && <div className="repo-error">{error}</div>}

          {loading && <div className="settings-loading">Loading…</div>}

          {!loading && tab !== 'roles' && visible.length === 0 && error === null && (
            <div style={emptyStyle}>{activeDef.emptyHint}</div>
          )}

          {tab === 'global' && visible.map(row => (
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
          ))}

          {tab === 'repos' && grouped.map(group => (
            <div key={group.key} style={groupStyle}>
              <div style={groupHeadStyle}>
                <span style={groupNameStyle}>{group.label}</span>
                <span style={groupMetaStyle}>{group.sublabel}</span>
                <button
                  type="button"
                  className="button button--secondary button--small"
                  onClick={() => openAdd('repos', group.key)}
                  style={{ marginLeft: 'auto' }}
                >
                  + skill
                </button>
              </div>
              {group.rows.map(row => (
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
              ))}
            </div>
          ))}

          {tab === 'roles' && (
            <>
              <div style={rolesGridStyle}>
                {ROLE_CARDS.map(card => (
                  <article key={card.id} style={roleCardStyle}>
                    <header style={roleCardHeadStyle}>
                      <div>
                        <div style={roleCardLabelStyle}>{card.label}</div>
                        <div style={roleCardKindStyle}>{card.kind}</div>
                      </div>
                      <span style={roleCardTagStyle}>role</span>
                    </header>
                    <p style={roleCardBlurbStyle}>{card.blurb}</p>
                    <div style={chipColStyle}>
                      <div style={chipRowStyle}>
                        <span style={chipLabelOkStyle}>can</span>
                        {card.can.map(c => <span key={c} style={chipOkStyle}>{c}</span>)}
                      </div>
                      <div style={chipRowStyle}>
                        <span style={chipLabelNoStyle}>can't</span>
                        {card.cant.map(c => <span key={c} style={chipNoStyle}>{c}</span>)}
                      </div>
                    </div>
                  </article>
                ))}
              </div>
              <div style={roleSectionHeadStyle}>Your role skills</div>
              {visible.length === 0 && error === null && (
                <div style={emptyStyle}>{activeDef.emptyHint}</div>
              )}
              {visible.map(row => (
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
              ))}
            </>
          )}
        </section>
      </div>

      {editing !== null && (
        <SkillEditorModal
          onClose={() => setEditing(null)}
          onSave={handleSave}
          initialScope={editingScope}
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
  const surface = row.usage === 'review' ? ' · Review' : '';
  if (bucket === 'global') return 'Global' + surface;
  if (bucket === 'role') return 'Role · ' + (row.roleTag ?? '') + surface;
  return 'Repo' + surface;
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

function navItemStyle(active: boolean): React.CSSProperties {
  return {
    display: 'grid',
    gridTemplateColumns: '1fr auto',
    gridTemplateRows: 'auto auto',
    gap: 2,
    padding: '10px 12px',
    textAlign: 'left',
    border: active ? '1px solid var(--ws-accent, #7c3aed)' : '1px solid transparent',
    background: active ? 'rgba(124, 58, 237, 0.06)' : 'transparent',
    borderRadius: 8,
    cursor: 'pointer',
    color: 'var(--text-1)',
  };
}

const navLabelStyle: React.CSSProperties = { fontSize: 13, fontWeight: 600 };

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

const navMetaStyle: React.CSSProperties = {
  gridColumn: '1 / 3',
  fontSize: 11,
  color: 'var(--text-3)',
};

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
    global: { fg: '#5b21b6', bg: 'rgba(124, 58, 237, 0.10)' },
    repos: { fg: '#0d9488', bg: 'rgba(13, 148, 136, 0.10)' },
    role: { fg: '#d97706', bg: 'rgba(217, 119, 6, 0.10)' },
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
  background: 'rgba(124, 58, 237, 0.10)',
  color: '#5b21b6',
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

const roleSectionHeadStyle: React.CSSProperties = {
  marginTop: 6,
  fontSize: 12,
  fontWeight: 700,
  letterSpacing: '0.04em',
  textTransform: 'uppercase',
  color: 'var(--text-3)',
};

const rolesGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
  gap: 10,
};

const roleCardStyle: React.CSSProperties = {
  padding: 14,
  border: '1px solid rgba(0,0,0,0.08)',
  borderRadius: 12,
  background: '#fff',
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

const roleCardHeadStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'flex-start',
};

const roleCardLabelStyle: React.CSSProperties = {
  fontSize: 15,
  fontWeight: 700,
};

const roleCardKindStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  marginTop: 2,
};

const roleCardTagStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.04em',
  padding: '1px 6px',
  borderRadius: 4,
  background: 'rgba(217, 119, 6, 0.10)',
  color: '#d97706',
};

const roleCardBlurbStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 12,
  color: 'var(--text-2)',
  lineHeight: 1.4,
};

const chipColStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  marginTop: 2,
};

const chipRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  flexWrap: 'wrap',
  gap: 4,
};

const chipLabelOkStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
  color: '#15803d',
  marginRight: 4,
};

const chipLabelNoStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
  color: '#b91c1c',
  marginRight: 4,
};

const chipOkStyle: React.CSSProperties = {
  fontSize: 11,
  padding: '1px 6px',
  borderRadius: 4,
  background: 'rgba(22, 163, 74, 0.10)',
  color: '#15803d',
};

const chipNoStyle: React.CSSProperties = {
  fontSize: 11,
  padding: '1px 6px',
  borderRadius: 4,
  background: 'rgba(207, 19, 34, 0.08)',
  color: '#b91c1c',
};

export default SkillsPage;
