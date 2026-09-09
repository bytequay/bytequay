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
import type { AgentSkillDto, AgentSkillInput, AgentSkillScope } from '../types';

const scopeLabels: Record<AgentSkillScope, string> = { GLOBAL: 'Global', DEVELOPMENT: 'Development', REVIEW: 'Review' };

export default function AgentSkillsCard() {
  const [skills, setSkills] = useState<AgentSkillDto[] | null>(null);
  const [editing, setEditing] = useState<AgentSkillDto | 'new' | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      setSkills(await window.bridge.getAgentSkills());
    }
    catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
    finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const apply = async (operation: () => Promise<AgentSkillDto[]>) => {
    setBusy(true);
    setError(null);
    try {
      setSkills(await operation());
      setEditing(null);
    }
    catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
    finally {
      setBusy(false);
    }
  };

  return (
    <section className="sets-card" aria-label="Agent skills" aria-busy={busy}>
      <div className="sets-card-head"><span className="sets-card-title">Agent skills</span></div>
      <div className="sets-card-body">
        <p className="sets-skill-description">
          Add your own instructions across providers and workspaces. Choose Global for all agents,
          Development for development tasks, or Review for PR reviews.
          Changes apply on the next turn; a running turn keeps its current instructions.
        </p>
      </div>
      {error && <div className="sets-error" role="alert">{error}</div>}
      {skills === null ? (
        <div className="sets-row">
          {busy || !error
            ? <span className="sets-row-sub">Loading skills…</span>
            : <button type="button" className="sets-btn" onClick={() => { void load(); }}>Try again</button>}
        </div>
      ) : <>
        {skills.length === 0 && <div className="sets-row"><span className="sets-row-sub">No custom skills added yet.</span></div>}
        {skills.map(skill => (
          <AgentSkillRow
            key={skill.id}
            skill={skill}
            disabled={busy || editing !== null}
            onToggle={() => { void apply(() => window.bridge.updateAgentSkill(skill.id, { ...skill, enabled: !skill.enabled })); }}
            onEdit={() => { setError(null); setEditing(skill); }}
            onRemove={() => { void apply(() => window.bridge.removeAgentSkill(skill.id)); }}
          />
        ))}
        {editing !== null ? (
          <AgentSkillForm
            key={editing === 'new' ? 'new' : editing.id}
            skill={editing === 'new' ? undefined : editing}
            busy={busy}
            onSave={input => {
              void apply(() => editing === 'new'
                ? window.bridge.addAgentSkill(input)
                : window.bridge.updateAgentSkill(editing.id, input));
            }}
            onCancel={() => { setEditing(null); setError(null); }}
          />
        ) : (
          <div className="sets-row sets-add-row">
            <button type="button" className="sets-link" disabled={busy} onClick={() => { setError(null); setEditing('new'); }}>
              + Add skill
            </button>
          </div>
        )}
      </>}
    </section>
  );
}

export function AgentSkillRow({ skill, disabled, onToggle, onEdit, onRemove }: {
  skill: AgentSkillDto;
  disabled: boolean;
  onToggle: () => void;
  onEdit: () => void;
  onRemove: () => void;
}) {
  return (
    <div className="sets-row sets-skill-row">
      <div className="sets-row-main">
        <div className="sets-row-title"><span className="sets-name">{skill.name}</span></div>
        <div className="sets-row-sub">{scopeLabels[skill.scope]} · {skill.enabled ? 'Enabled' : 'Disabled'}</div>
      </div>
      <button type="button" className="sets-btn" disabled={disabled} aria-label={`Edit ${skill.name}`} onClick={onEdit}>Edit</button>
      <button type="button" className="sets-btn is-danger" disabled={disabled} aria-label={`Remove ${skill.name}`} onClick={onRemove}>Remove</button>
      <button
        type="button"
        role="switch"
        className={`sets-toggle${skill.enabled ? ' is-on' : ''}`}
        aria-label={`Enable ${skill.name}`}
        aria-checked={skill.enabled}
        disabled={disabled}
        onClick={onToggle}
      >
        <span className="sets-toggle-knob" />
      </button>
    </div>
  );
}

export function AgentSkillForm({ skill, busy, onSave, onCancel }: {
  skill?: AgentSkillDto;
  busy: boolean;
  onSave: (input: AgentSkillInput) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(skill?.name ?? '');
  const [instructions, setInstructions] = useState(skill?.instructions ?? '');
  const [enabled, setEnabled] = useState(skill?.enabled ?? true);
  const [scope, setScope] = useState<AgentSkillScope>(skill ? skill.scope : 'GLOBAL');

  return (
    <form className="sets-key-form sets-skill-form" aria-label={skill ? 'Edit skill' : 'Add skill'} onSubmit={event => {
      event.preventDefault();
      if (!busy && name.trim() && instructions.trim()) onSave({ name: name.trim(), instructions, enabled, scope });
    }}>
      <label>
        Name
        <input value={name} required maxLength={100} disabled={busy} onChange={event => setName(event.target.value)} />
      </label>
      <label>
        Scope
        <select value={scope} disabled={busy} onChange={event => setScope(event.target.value as AgentSkillScope)}>
          <option value="GLOBAL">Global — all agents</option>
          <option value="DEVELOPMENT">Development — development tasks</option>
          <option value="REVIEW">Review — PR reviews</option>
        </select>
      </label>
      <label>
        Instructions
        <textarea
          value={instructions}
          required
          maxLength={20000}
          rows={10}
          disabled={busy}
          placeholder="Paste the instructions you want agents in this scope to follow."
          onChange={event => setInstructions(event.target.value)}
        />
      </label>
      <label className="sets-skill-enabled">
        <input type="checkbox" checked={enabled} disabled={busy} onChange={event => setEnabled(event.target.checked)} />
        Enable this skill
      </label>
      <div className="sets-key-form-row">
        <button type="submit" className="sets-btn-primary" disabled={busy || !name.trim() || !instructions.trim()}>
          {busy ? 'Saving…' : 'Save skill'}
        </button>
        <button type="button" className="sets-btn" disabled={busy} onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}
