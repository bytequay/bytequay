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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { AgentSkillDto } from '../types';
import AgentSkillsCard, { AgentSkillForm, AgentSkillRow } from './AgentSkillsCard';

const skill: AgentSkillDto = {
  id: 'release-notes',
  name: 'Release notes',
  instructions: 'Use our release note format.\nTreat <details> as text.',
  enabled: true,
  scope: 'REVIEW',
};

describe('agent skills settings', () => {
  it('describes the user’s own instructions and when a change takes effect', () => {
    const html = renderToStaticMarkup(<AgentSkillsCard />);
    expect(html).toContain('Add your own instructions across providers and workspaces');
    expect(html).toContain('Choose Global for all agents');
    expect(html).toContain('Development for development tasks, or Review for PR reviews');
    expect(html).toContain('Changes apply on the next turn');
    expect(html).toContain('a running turn keeps its current instructions');
  });

  it('exposes enabled state and individually named editing controls', () => {
    const html = renderToStaticMarkup(<AgentSkillRow skill={skill} disabled={false} onToggle={vi.fn()} onEdit={vi.fn()} onRemove={vi.fn()} />);
    expect(html).toContain('role="switch"');
    expect(html).toContain('aria-checked="true"');
    expect(html).toContain('aria-label="Edit Release notes"');
    expect(html).toContain('aria-label="Remove Release notes"');
    expect(html).toContain('Review · Enabled');
    const disabled = renderToStaticMarkup(<AgentSkillRow skill={{ ...skill, enabled: false }} disabled onToggle={vi.fn()} onEdit={vi.fn()} onRemove={vi.fn()} />);
    expect(disabled).toContain('aria-checked="false"');
    expect(disabled.match(/disabled=""/g)).toHaveLength(3);
  });

  it('keeps existing instructions editable as plain text and preserves the enabled choice', () => {
    const html = renderToStaticMarkup(<AgentSkillForm skill={{ ...skill, enabled: false }} busy={false} onSave={vi.fn()} onCancel={vi.fn()} />);
    expect(html).toContain('value="Release notes"');
    expect(html).toContain('Use our release note format.\nTreat &lt;details&gt; as text.');
    expect(html).not.toContain('checked=""');
    expect(html).not.toContain('disabled=""');
    expect(html).toContain('maxLength="20000"');
    expect(html).toContain('<option value="REVIEW" selected="">Review — PR reviews</option>');
  });

  it('disables empty submissions and locks the editor during a save', () => {
    const empty = renderToStaticMarkup(<AgentSkillForm busy={false} onSave={vi.fn()} onCancel={vi.fn()} />);
    expect(empty).toContain('checked=""');
    expect(empty).toContain('<option value="GLOBAL" selected="">Global — all agents</option>');
    expect(empty.match(/disabled=""/g)).toHaveLength(1);
    const saving = renderToStaticMarkup(<AgentSkillForm skill={skill} busy onSave={vi.fn()} onCancel={vi.fn()} />);
    expect(saving).toContain('Saving…');
    expect(saving.match(/disabled=""/g)).toHaveLength(6);
  });
});
