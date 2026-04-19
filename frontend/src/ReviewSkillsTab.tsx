import { useEffect, useMemo, useState } from 'react';
import type { AiProviderInfo, ReviewSkillDto } from './types';

type FormValues = {
  skillName: string;
  repo: string;
  llmProvider: string; // '' = any provider
  description: string;
  context: string;
};

const EMPTY_FORM: FormValues = {
  skillName: '',
  repo: '',
  llmProvider: '',
  description: '',
  context: '',
};

function toFormValues(skill: ReviewSkillDto): FormValues {
  return {
    skillName: skill.skillName,
    repo: skill.repo,
    llmProvider: skill.llmProvider ?? '',
    description: skill.description ?? '',
    context: skill.context ?? '',
  };
}

/**
 * Per-repo review-skill CRUD. Each row in the table is a stored skill;
 * clicking edit pops the same form used for "New skill" pre-filled with
 * the row's values. The provider dropdown sources from /ai/providers,
 * with a leading "(Any provider)" option that maps to llmProvider=null.
 */
function ReviewSkillsTab() {
  const [skills, setSkills] = useState<ReviewSkillDto[]>([]);
  const [providers, setProviders] = useState<AiProviderInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // null === closed; 'new' === creating; number === editing that id.
  const [editorTarget, setEditorTarget] = useState<'new' | number | null>(null);
  const [form, setForm] = useState<FormValues>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

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
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void reload(); }, []);

  const providerLabel = useMemo(() => {
    const map = new Map<string, string>();
    for (const p of providers) map.set(p.providerId, p.displayName);
    return map;
  }, [providers]);

  const startCreate = () => {
    setEditorTarget('new');
    setForm(EMPTY_FORM);
    setFormError(null);
  };
  const startEdit = (skill: ReviewSkillDto) => {
    setEditorTarget(skill.id);
    setForm(toFormValues(skill));
    setFormError(null);
  };
  const cancelEdit = () => {
    setEditorTarget(null);
    setFormError(null);
  };

  const submit = async () => {
    if (!form.skillName.trim()) {
      setFormError('Skill name is required.');
      return;
    }
    if (!form.repo.trim()) {
      setFormError('Repo is required.');
      return;
    }
    setSaving(true);
    setFormError(null);
    const payload = {
      skillName: form.skillName.trim(),
      repo: form.repo.trim(),
      llmProvider: form.llmProvider ? form.llmProvider : null,
      description: form.description.trim() || null,
      context: form.context.trim() || null,
    };
    try {
      if (editorTarget === 'new') {
        await window.bridge.createReviewSkill(payload);
      } else if (typeof editorTarget === 'number') {
        await window.bridge.updateReviewSkill(editorTarget, payload);
      }
      await reload();
      setEditorTarget(null);
    } catch (e) {
      setFormError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (id: number) => {
    if (!confirm('Delete this skill? This cannot be undone.')) return;
    setError(null);
    try {
      await window.bridge.deleteReviewSkill(id);
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <>
      <div className="skills-header">
        <div>
          <h3>Review skills</h3>
          <p className="section-copy">
            Per-repo prompt context the AI reviewer applies when reviewing matching repos.
            Lock a skill to one provider, or leave the provider blank to apply it to every model.
          </p>
        </div>
        <button
          type="button"
          className="button button--primary"
          onClick={startCreate}
          disabled={editorTarget === 'new' || typeof editorTarget === 'number'}
        >
          + New skill
        </button>
      </div>

      {error && <p className="error-text">{error}</p>}

      {loading ? (
        <p className="section-copy">Loading skills…</p>
      ) : (
        <table className="skills-table">
          <thead>
            <tr>
              <th>Skill</th>
              <th>Repo</th>
              <th>Provider</th>
              <th>Description</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {skills.length === 0 ? (
              <tr>
                <td colSpan={5} className="skills-table__empty">No skills yet — click <b>+ New skill</b> above.</td>
              </tr>
            ) : skills.map(s => (
              <tr key={s.id}>
                <td><b>{s.skillName}</b></td>
                <td><code>{s.repo}</code></td>
                <td>{s.llmProvider ? (providerLabel.get(s.llmProvider) ?? s.llmProvider) : <i>Any</i>}</td>
                <td className="skills-table__desc">{s.description ?? '—'}</td>
                <td className="skills-table__actions">
                  <button
                    type="button"
                    className="button button--secondary"
                    onClick={() => startEdit(s)}
                  >Edit</button>
                  <button
                    type="button"
                    className="button button--danger-link"
                    onClick={() => void remove(s.id)}
                  >Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {editorTarget !== null && (
        <div className="skill-editor">
          <h4>{editorTarget === 'new' ? 'New skill' : 'Edit skill'}</h4>

          <div className="settings-field">
            <label className="settings-label" htmlFor="skill-name">Skill name</label>
            <input
              id="skill-name"
              className="settings-input"
              type="text"
              value={form.skillName}
              onChange={e => setForm(f => ({ ...f, skillName: e.target.value }))}
              placeholder="e.g. Trino code style"
            />
          </div>

          <div className="settings-field">
            <label className="settings-label" htmlFor="skill-repo">Repo (owner/name)</label>
            <input
              id="skill-repo"
              className="settings-input"
              type="text"
              value={form.repo}
              onChange={e => setForm(f => ({ ...f, repo: e.target.value }))}
              placeholder="e.g. trinodb/trino"
            />
          </div>

          <div className="settings-field">
            <label className="settings-label" htmlFor="skill-provider">LLM provider</label>
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
              placeholder="One-line summary of this skill"
            />
          </div>

          <div className="settings-field">
            <label className="settings-label" htmlFor="skill-context">Review context</label>
            <textarea
              id="skill-context"
              className="settings-textarea"
              rows={10}
              value={form.context}
              onChange={e => setForm(f => ({ ...f, context: e.target.value }))}
              placeholder="Repo-specific guidance — code style, review priorities, conventions to flag…"
            />
          </div>

          {formError && <p className="error-text">{formError}</p>}

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
    </>
  );
}

export default ReviewSkillsTab;
