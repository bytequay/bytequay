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
import type { AiProviderInfo, ReviewSkillDto } from './types';

/** docs/mockups/design/tasks/settings-ai-skills.png — Skills surface
 *  for the AI settings page. Three scopes (Global / Repos / Domains)
 *  switch the grouping rule applied to the same flat list of skills.
 *  Repos mode is the default — every skill backed by a real repo gets
 *  grouped under its owner/name header. Global lists skills that apply
 *  everywhere (repo == '*' sentinel today); Domains lists skills
 *  scoped to an agent role (repo prefix 'domain:').
 *
 *  The data model is the existing {@link ReviewSkillDto}; this tab is
 *  the new rendering layer, not a new domain object. */
type Scope = 'global' | 'repos' | 'domains';

const DOMAIN_PREFIX = 'domain:';
const GLOBAL_SENTINEL = '*';

type FormValues = {
  skillName: string;
  scope: Scope;
  /** Used when scope === 'repos'. */
  repo: string;
  /** Used when scope === 'domains'. */
  domain: string;
  llmProvider: string;
  description: string;
  context: string;
};

const EMPTY_FORM: FormValues = {
  skillName: '',
  scope: 'repos',
  repo: '',
  domain: '',
  llmProvider: '',
  description: '',
  context: '',
};

function classifyScope(repo: string | null | undefined): Scope {
  if (repo === null || repo === undefined || repo === '' || repo === GLOBAL_SENTINEL) return 'global';
  if (repo.startsWith(DOMAIN_PREFIX)) return 'domains';
  return 'repos';
}

function toFormValues(s: ReviewSkillDto): FormValues {
  const scope = classifyScope(s.repo);
  return {
    skillName: s.skillName,
    scope,
    repo: scope === 'repos' ? s.repo : '',
    domain: scope === 'domains' ? s.repo.slice(DOMAIN_PREFIX.length) : '',
    llmProvider: s.llmProvider ?? '',
    description: s.description ?? '',
    context: s.context ?? '',
  };
}

function repoFieldFromForm(f: FormValues): string {
  switch (f.scope) {
    case 'global': return GLOBAL_SENTINEL;
    case 'domains': return DOMAIN_PREFIX + f.domain.trim();
    case 'repos': return f.repo.trim();
  }
}

function AiSkillsTab() {
  const [skills, setSkills] = useState<ReviewSkillDto[]>([]);
  const [providers, setProviders] = useState<AiProviderInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [scope, setScope] = useState<Scope>('repos');
  const [search, setSearch] = useState('');
  // Local-only enabled state — the persisted model doesn't carry this
  // bit yet, so the toggle here is session-scoped UX feedback. A
  // follow-up adds the column + a PATCH endpoint and wires this state
  // through the API.
  const [disabled, setDisabled] = useState<Set<number>>(new Set());
  // null = closed; 'new' = creating; number = editing that id.
  const [editorTarget, setEditorTarget] = useState<'new' | number | null>(null);
  const [form, setForm] = useState<FormValues>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

  const reload = async () => {
    setLoading(true);
    setError(null);
    try {
      const [list, provs] = await Promise.all([
        window.bridge.listReviewSkills(),
        window.bridge.listAiProviders(),
      ]);
      setSkills(list);
      setProviders(provs);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  };

  useEffect(() => { void reload(); }, []);

  const providerLabel = useMemo(() => {
    const map = new Map<string, string>();
    for (const p of providers) map.set(p.providerId, p.displayName);
    return map;
  }, [providers]);

  // Scope counts feed the segmented control's chips so the user sees
  // how many skills sit in each bucket at a glance. Counts are over
  // the full list, not the filtered list — the chip is a switcher,
  // not a result-count.
  const counts = useMemo(() => {
    const c = { global: 0, repos: 0, domains: 0 };
    for (const s of skills) c[classifyScope(s.repo)]++;
    return c;
  }, [skills]);

  const visibleSkills = useMemo(() => {
    const q = search.trim().toLowerCase();
    return skills
      .filter(s => classifyScope(s.repo) === scope)
      .filter(s => q === ''
        || s.skillName.toLowerCase().includes(q)
        || (s.description ?? '').toLowerCase().includes(q)
        || (s.repo ?? '').toLowerCase().includes(q));
  }, [skills, scope, search]);

  // Group by repo / domain / single "global" bucket so the renderer
  // walks a stable ordered list and emits group headers between
  // group boundaries.
  const grouped = useMemo(() => {
    const byKey = new Map<string, { label: string; sublabel: string; rows: ReviewSkillDto[] }>();
    for (const s of visibleSkills) {
      let key: string;
      let label: string;
      let sublabel: string;
      const sc = classifyScope(s.repo);
      if (sc === 'global') {
        key = '__global__';
        label = 'Global';
        sublabel = 'loaded on every prompt';
      }
      else if (sc === 'domains') {
        const domain = s.repo.slice(DOMAIN_PREFIX.length);
        key = 'domain:' + domain;
        label = domain;
        sublabel = 'domain role';
      }
      else {
        key = s.repo;
        label = s.repo.includes('/') ? s.repo.split('/')[1] : s.repo;
        sublabel = '— ' + s.repo;
      }
      let bucket = byKey.get(key);
      if (bucket === undefined) {
        bucket = { label, sublabel, rows: [] };
        byKey.set(key, bucket);
      }
      bucket.rows.push(s);
    }
    return Array.from(byKey.entries()).map(([key, v]) => ({ key, ...v }));
  }, [visibleSkills]);

  const startCreate = (presetScope?: Scope, presetRepo?: string) => {
    setEditorTarget('new');
    setForm({ ...EMPTY_FORM, scope: presetScope ?? scope, repo: presetRepo ?? '' });
    setFormError(null);
  };
  const startEdit = (s: ReviewSkillDto) => {
    setEditorTarget(s.id);
    setForm(toFormValues(s));
    setFormError(null);
  };
  const cancelEdit = () => { setEditorTarget(null); setFormError(null); };

  const submit = async () => {
    if (form.skillName.trim() === '') { setFormError('Skill name is required.'); return; }
    if (form.scope === 'repos' && form.repo.trim() === '') {
      setFormError('Repo is required for per-repo skills.'); return;
    }
    if (form.scope === 'domains' && form.domain.trim() === '') {
      setFormError('Domain is required for per-domain skills.'); return;
    }
    setSaving(true);
    setFormError(null);
    const payload = {
      skillName: form.skillName.trim(),
      repo: repoFieldFromForm(form),
      llmProvider: form.llmProvider === '' ? null : form.llmProvider,
      description: form.description.trim() === '' ? null : form.description.trim(),
      context: form.context.trim() === '' ? null : form.context.trim(),
    };
    try {
      if (editorTarget === 'new') {
        await window.bridge.createReviewSkill(payload);
      }
      else if (typeof editorTarget === 'number') {
        await window.bridge.updateReviewSkill(editorTarget, payload);
      }
      await reload();
      setEditorTarget(null);
    }
    catch (e) {
      setFormError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSaving(false);
    }
  };

  const remove = async (id: number) => {
    if (!confirm('Delete this skill? This cannot be undone.')) return;
    setError(null);
    try {
      await window.bridge.deleteReviewSkill(id);
      await reload();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const toggleEnabled = (id: number) => {
    setDisabled(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleCollapsed = (key: string) => {
    setCollapsed(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  };

  return (
    <div className="ai-skills">
      <div className="ai-skills__bar">
        <div className="ai-skills__scope" role="radiogroup" aria-label="Scope filter">
          {(['global', 'repos', 'domains'] as const).map(s => (
            <button
              key={s}
              type="button"
              role="radio"
              aria-checked={scope === s}
              className={`ai-skills__seg${scope === s ? ' ai-skills__seg--active' : ''}`}
              onClick={() => setScope(s)}
            >
              <span>{s === 'global' ? 'Global' : s === 'repos' ? 'Repos' : 'Domains'}</span>
              <span className="ai-skills__seg-count">{counts[s]}</span>
            </button>
          ))}
        </div>
        <input
          type="text"
          className="ai-skills__search"
          placeholder="Search skills…"
          value={search}
          onChange={e => setSearch(e.target.value)}
        />
        <button
          type="button"
          className="ai-skills__new"
          onClick={() => startCreate()}
        >
          + New skill
        </button>
      </div>

      {error !== null && <p className="error-text">{error}</p>}

      {loading ? (
        <p className="section-copy">Loading skills…</p>
      ) : grouped.length === 0 ? (
        <div className="ai-skills__empty">
          {scope === 'repos' && 'No per-repo skills yet.'}
          {scope === 'global' && 'No global skills yet.'}
          {scope === 'domains' && 'No per-domain skills yet.'}
          {' '}Click <b>+ New skill</b> above to add one.
        </div>
      ) : (
        grouped.map(g => {
          const isCollapsed = collapsed.has(g.key);
          return (
            <div key={g.key} className="ai-skills__group">
              <button
                type="button"
                className="ai-skills__group-head"
                onClick={() => toggleCollapsed(g.key)}
                aria-expanded={!isCollapsed}
              >
                <span className="ai-skills__chev" aria-hidden>
                  {isCollapsed ? '▸' : '▾'}
                </span>
                <span
                  className="ai-skills__group-ic"
                  style={{ background: groupColor(g.key) }}
                  aria-hidden
                >
                  {g.label.charAt(0).toUpperCase()}
                </span>
                <span className="ai-skills__group-name">{g.label}</span>
                <span className="ai-skills__group-sub">{g.sublabel}</span>
                <span className="ai-skills__group-count">{g.rows.length}</span>
                <span
                  className="ai-skills__group-add"
                  role="button"
                  tabIndex={0}
                  onClick={(e) => {
                    e.stopPropagation();
                    startCreate(scope, scope === 'repos' ? g.key : '');
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      e.stopPropagation();
                      startCreate(scope, scope === 'repos' ? g.key : '');
                    }
                  }}
                >
                  + skill
                </span>
              </button>

              {!isCollapsed && g.rows.map(s => {
                const off = disabled.has(s.id);
                const sc = classifyScope(s.repo);
                return (
                  <div key={s.id} className={`ai-skills__row${off ? ' ai-skills__row--off' : ''}`}>
                    <button
                      type="button"
                      className={`ai-skills__toggle${off ? ' ai-skills__toggle--off' : ''}`}
                      onClick={() => toggleEnabled(s.id)}
                      aria-pressed={!off}
                      aria-label={off ? 'Enable skill' : 'Disable skill'}
                    />
                    <div className="ai-skills__body">
                      <div className="ai-skills__title">
                        <span>{s.skillName}</span>
                        <span className={`ai-skills__pill ai-skills__pill--${sc}`}>
                          {sc === 'global' ? 'Global' : sc === 'repos' ? 'Repo' : 'Domain'}
                        </span>
                      </div>
                      {s.description !== null && s.description !== '' && (
                        <div className="ai-skills__desc">{s.description}</div>
                      )}
                      <div className="ai-skills__meta">
                        <span>{formatBytes((s.context ?? '').length)}</span>
                        {s.llmProvider !== null && (
                          <>
                            <span className="ai-skills__sep">·</span>
                            <span>
                              loaded for{' '}
                              <span className="ai-skills__kbd">
                                {providerLabel.get(s.llmProvider) ?? s.llmProvider}
                              </span>
                            </span>
                          </>
                        )}
                        <span className="ai-skills__sep">·</span>
                        <span>{off
                          ? `disabled · last touched ${relativeTime(s.updatedAt)}`
                          : `edited ${relativeTime(s.updatedAt)}`}</span>
                      </div>
                    </div>
                    <div className="ai-skills__actions">
                      <button
                        type="button"
                        className="ai-skills__row-btn"
                        onClick={() => startEdit(s)}
                      >
                        Edit
                      </button>
                      <button
                        type="button"
                        className="ai-skills__menu-btn"
                        onClick={() => void remove(s.id)}
                        title="Delete skill"
                        aria-label="Delete skill"
                      >
                        ⋯
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          );
        })
      )}

      {editorTarget !== null && (
        <div className="skill-editor">
          <h4>{editorTarget === 'new' ? 'New skill' : 'Edit skill'}</h4>

          <div className="settings-field">
            <label className="settings-label">Scope</label>
            <div className="ai-skills__scope ai-skills__scope--form">
              {(['global', 'repos', 'domains'] as const).map(s => (
                <button
                  key={s}
                  type="button"
                  className={`ai-skills__seg${form.scope === s ? ' ai-skills__seg--active' : ''}`}
                  onClick={() => setForm(f => ({ ...f, scope: s }))}
                >
                  {s === 'global' ? 'Global' : s === 'repos' ? 'Per-repo' : 'Per-domain'}
                </button>
              ))}
            </div>
          </div>

          <div className="settings-field">
            <label className="settings-label" htmlFor="skill-name">Skill name</label>
            <input
              id="skill-name"
              className="settings-input"
              type="text"
              value={form.skillName}
              onChange={e => setForm(f => ({ ...f, skillName: e.target.value }))}
              placeholder="e.g. Backend uses Java 25"
            />
          </div>

          {form.scope === 'repos' && (
            <div className="settings-field">
              <label className="settings-label" htmlFor="skill-repo">Repo (owner/name)</label>
              <input
                id="skill-repo"
                className="settings-input"
                type="text"
                value={form.repo}
                onChange={e => setForm(f => ({ ...f, repo: e.target.value }))}
                placeholder="e.g. chenjian2664/ByteQuay"
              />
            </div>
          )}

          {form.scope === 'domains' && (
            <div className="settings-field">
              <label className="settings-label" htmlFor="skill-domain">Domain</label>
              <input
                id="skill-domain"
                className="settings-input"
                type="text"
                value={form.domain}
                onChange={e => setForm(f => ({ ...f, domain: e.target.value }))}
                placeholder="e.g. reviewer, reviewee, task-scheduler"
              />
            </div>
          )}

          <div className="settings-field">
            <label className="settings-label" htmlFor="skill-provider">Lock to provider</label>
            <select
              id="skill-provider"
              className="settings-input"
              value={form.llmProvider}
              onChange={e => setForm(f => ({ ...f, llmProvider: e.target.value }))}
            >
              <option value="">(Any provider)</option>
              {providers.map(p => (
                <option key={p.providerId} value={p.providerId}>{p.displayName}</option>
              ))}
            </select>
          </div>

          <div className="settings-field">
            <label className="settings-label" htmlFor="skill-description">Description</label>
            <input
              id="skill-description"
              className="settings-input"
              type="text"
              value={form.description}
              onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
              placeholder="One-line summary shown under the title"
            />
          </div>

          <div className="settings-field">
            <label className="settings-label" htmlFor="skill-context">Prompt body</label>
            <textarea
              id="skill-context"
              className="settings-textarea"
              rows={10}
              value={form.context}
              onChange={e => setForm(f => ({ ...f, context: e.target.value }))}
              placeholder="The actual instruction the AI loads alongside its prompts."
            />
          </div>

          {formError !== null && <p className="error-text">{formError}</p>}

          <div className="settings-actions">
            <button
              type="button"
              className="button button--primary"
              onClick={() => void submit()}
              disabled={saving}
            >
              {saving ? 'Saving…' : (editorTarget === 'new' ? 'Create skill' : 'Save changes')}
            </button>
            <button
              type="button"
              className="button button--secondary"
              onClick={cancelEdit}
              disabled={saving}
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

// ── helpers ─────────────────────────────────────────────────────────

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
  if (d < 14) return `${d}d ago`;
  const w = Math.floor(d / 7);
  if (w < 8) return `${w}w ago`;
  const mo = Math.floor(d / 30);
  return `${mo}mo ago`;
}

/** Deterministic accent gradient per group key. Keeps each repo /
 *  domain visually distinct in the rail without needing a real
 *  avatar lookup — same key → same gradient on every render. */
function groupColor(key: string): string {
  const palette = [
    'linear-gradient(135deg, #b794f4, #7c5cff)',
    'linear-gradient(135deg, #6ee7b7, #047857)',
    'linear-gradient(135deg, #93c5fd, #2563eb)',
    'linear-gradient(135deg, #fcd34d, #b45309)',
    'linear-gradient(135deg, #f87171, #b91c1c)',
    'linear-gradient(135deg, #1f2937, #4b5563)',
  ];
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) >>> 0;
  return palette[h % palette.length];
}

export default AiSkillsTab;
