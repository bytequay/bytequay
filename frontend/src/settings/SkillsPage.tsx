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
import { useState } from 'react';
import { Pencil } from 'lucide-react';
import type { AgentSkillDto, AgentSkillInput, AgentSkillScope } from '../types';
import { AddAction, Dialog, Segmented, SettingsCard, SettingsRow, Toggle } from './primitives';

type ScopeFilter = 'ALL' | AgentSkillScope;

const SCOPE_LABELS: Record<AgentSkillScope, string> = {
  GLOBAL: 'Global',
  DEVELOPMENT: 'Development',
  REVIEW: 'Review',
};

/** What each scope actually receives, shown live in the dialog as the scope changes. */
export const SCOPE_HINTS: Record<AgentSkillScope, string> = {
  GLOBAL: 'Sent to every agent turn',
  DEVELOPMENT: 'Sent to development tasks and CI Autofix only',
  REVIEW: 'Sent to review runs and discussions only',
};

export function filterSkills(skills: AgentSkillDto[], scope: ScopeFilter): AgentSkillDto[] {
  return scope === 'ALL' ? skills : skills.filter(skill => skill.scope === scope);
}

export default function SkillsPage({ skills, busy, error, onSave, onToggle }: {
  skills: AgentSkillDto[];
  busy: boolean;
  error: string | null;
  onSave: (input: AgentSkillInput, id: string | null) => void;
  onToggle: (skill: AgentSkillDto) => void;
}) {
  const [scope, setScope] = useState<ScopeFilter>('ALL');
  const [editing, setEditing] = useState<AgentSkillDto | 'new' | null>(null);
  const shown = filterSkills(skills, scope);

  return (
    <div className="setw-page">
      <h1>Skills</h1>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}

      <div className="setw-section">
        <span className="setw-section-title">Your instructions</span>
        <Segmented<ScopeFilter>
          label="Filter by scope"
          value={scope}
          options={[
            { value: 'ALL', label: 'All' },
            { value: 'GLOBAL', label: 'Global' },
            { value: 'DEVELOPMENT', label: 'Development' },
            { value: 'REVIEW', label: 'Review' },
          ]}
          onChange={setScope}
        />
      </div>

      <SettingsCard footer={<AddAction label="Add skill" onClick={() => setEditing('new')} />}>
        {shown.map(skill => (
          <SettingsRow key={skill.id} title={skill.name} subtitle={SCOPE_LABELS[skill.scope]}>
            <button
              type="button"
              className="setw-btn is-quiet"
              aria-label={`Edit ${skill.name}`}
              disabled={busy}
              onClick={() => setEditing(skill)}
            >
              <Pencil size={12} strokeWidth={2} />
              <span>Edit</span>
            </button>
            <Toggle
              on={skill.enabled}
              label={`Enable ${skill.name}`}
              disabled={busy}
              onChange={() => onToggle(skill)}
            />
          </SettingsRow>
        ))}
        {shown.length === 0 && <div className="setw-row-empty">No skills in this scope</div>}
      </SettingsCard>
      <div className="setw-footnote">Applies from the next turn · a running turn keeps its instructions</div>

      {editing !== null && (
        <SkillDialog
          skill={editing === 'new' ? null : editing}
          onCancel={() => setEditing(null)}
          onSave={input => { setEditing(null); onSave(input, editing === 'new' ? null : editing.id); }}
        />
      )}
    </div>
  );
}

function SkillDialog({ skill, onSave, onCancel }: {
  skill: AgentSkillDto | null;
  onSave: (input: AgentSkillInput) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(skill?.name ?? '');
  const [scope, setScope] = useState<AgentSkillScope>(skill?.scope ?? 'GLOBAL');
  const [instructions, setInstructions] = useState(skill?.instructions ?? '');
  const ready = name.trim() !== '';

  return (
    <Dialog
      title={skill === null ? 'Add skill' : 'Edit skill'}
      confirmLabel={skill === null ? 'Add skill' : 'Save skill'}
      confirmDisabled={!ready}
      onConfirm={() => {
        if (ready) onSave({ name: name.trim(), instructions, enabled: skill?.enabled ?? true, scope });
      }}
      onCancel={onCancel}
    >
      <div className="setw-field-label">Name</div>
      <input
        value={name}
        aria-label="Skill name"
        maxLength={100}
        placeholder="Conventional commit subjects"
        onChange={event => setName(event.target.value)}
      />
      <div className="setw-field-label">Scope</div>
      <Segmented<AgentSkillScope>
        label="Skill scope"
        value={scope}
        options={[
          { value: 'GLOBAL', label: 'Global' },
          { value: 'DEVELOPMENT', label: 'Development' },
          { value: 'REVIEW', label: 'Review' },
        ]}
        onChange={setScope}
      />
      <div className="setw-field-label">Instructions</div>
      <textarea
        rows={5}
        value={instructions}
        aria-label="Skill instructions"
        maxLength={20000}
        placeholder="Write the instruction exactly as you would say it to the agent."
        onChange={event => setInstructions(event.target.value)}
      />
      <div className="setw-dialog-hint">{SCOPE_HINTS[scope]}</div>
    </Dialog>
  );
}
