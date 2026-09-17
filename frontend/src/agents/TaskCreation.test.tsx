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
import { describe, expect, it } from 'vitest';
import { AgentHomeView } from './AgentHome';
import { AgentTaskList } from './AgentTasksView';
import { NO_AGENT_CAPABILITIES } from './capabilities';
import { createPreviewBridge, previewTask } from './fixtures/previewBridge';
import type { AgentTaskStartupPhase, ModelOptionDto, ModelProviderDto } from '../types';

const MODELS: ModelOptionDto[] = [
  { id: 'gpt-5.6-sol', name: '5.6 Sol', description: '', efforts: ['medium'], defaultEffort: 'medium', inputModalities: ['text', 'image'] },
];
const PROVIDERS: ModelProviderDto[] = [{
  id: 'codex', label: 'Codex', readiness: 'READY', version: null, diagnostic: '',
  capabilities: { ...NO_AGENT_CAPABILITIES, attachmentMediaTypes: ['image/png'] },
  models: MODELS, selectedModel: MODELS[0].id, selectedEffort: 'medium', }];
const props = {
  tasks: [{ ...previewTask('workspace-1', 'existing-task'), title: 'Existing task' }],
  loading: false,
  error: null as string | null,
  laneId: 'codex',
  providers: PROVIDERS,
  models: MODELS,
  selection: { model: MODELS[0].id, effort: 'medium' },
  draft: '  Review this screenshot  ',
  images: [{ id: 'draft-1', name: 'failure.png', url: 'blob:preview' }],
  blocker: null as string | null,
  busy: false,
  startup: null as AgentTaskStartupPhase | null,
  attaching: false,
  onDraftChange: () => {},
  onSelectProvider: () => {},
  onSelectModel: () => {},
  onCreate: () => {},
  onOpenTask: () => {},
  onAttach: () => {},
  onRemoveImage: () => {},
};

describe.each(['Home', 'Tasks'])('first-message feedback on %s', (surface) => {
  const render = (overrides: Partial<typeof props>) => renderToStaticMarkup(surface === 'Home'
    ? <AgentHomeView {...props} {...overrides} repository="bytequay/bytequay" bridge={() => createPreviewBridge('workspace-1')} onShowAll={() => {}} />
    : <AgentTaskList {...props} {...overrides} />);

  it('shows the submitted message and images while creation is pending, without mutable creation controls', () => {
    const markup = render({ busy: true });

    expect(markup).toContain('class="agt-user__bubble"');
    expect(markup).toContain('<div>Review this screenshot</div>');
    expect(markup).toContain('src="blob:preview" alt="failure.png"');
    expect(markup).toContain('role="status"');
    expect(markup).toContain('Starting task…');
    expect(markup).toContain('5.6 Sol');
    expect(markup).not.toContain('Existing task');
    expect(markup).not.toContain('aria-label="Describe a task"');
    expect(markup).not.toContain('aria-label="Provider:');
    expect(markup).not.toContain('aria-label="Model and effort for the new task"');
    expect(markup).not.toContain('aria-label="Send — creates the task"');
    expect(markup).not.toContain('aria-label="Remove image failure.png"');
  });

  it('names every creation step and marks the reported one as the current step', () => {
    const markup = render({ busy: true, startup: 'WORKTREE' });

    expect([...markup.matchAll(/agt-starting-step is-(\w+)/g)].map((match) => match[1]))
      .toEqual(['done', 'done', 'done', 'current', 'pending', 'pending']);
    expect(markup).toContain('Checking that Codex is installed and signed in');
    expect(markup).toContain('Naming task and branch');
    expect(markup).toContain('Creating the task branch and worktree');
    expect(markup).toContain('Storing the attached images');
    expect(markup).toContain('Starting Codex and sending your message');
  });

  it('lists every step as pending until the backend reports one', () => {
    const markup = render({ busy: true, images: [] });

    expect([...markup.matchAll(/agt-starting-step is-(\w+)/g)].map((match) => match[1]))
      .toEqual(['pending', 'pending', 'pending', 'pending', 'pending']);
    expect(markup).not.toContain('Storing the attached images');
  });

  it('keeps the draft editable while an attachment loads, without showing task startup', () => {
    const markup = render({ busy: true, attaching: true });

    expect(markup).toContain('aria-label="Describe a task"');
    expect(markup).toContain('value="  Review this screenshot  "');
    expect(markup).not.toMatch(/<input[^>]*disabled/);
    expect(markup).toMatch(/aria-label="Send — creates the task"[^>]*disabled/);
    expect(markup).not.toContain('Starting task…');
  });

  it('restores the original draft and images with the failure available for retry', () => {
    const markup = render({ busy: false, error: 'Unable to create the agent task' });

    expect(markup).toContain('role="alert">Unable to create the agent task</div>');
    expect(markup).toContain('value="  Review this screenshot  "');
    expect(markup).toContain('src="blob:preview" alt="failure.png"');
    expect(markup).not.toMatch(/<input[^>]*disabled/);
    expect(markup).toContain('aria-label="Send — creates the task"');
    expect(markup).not.toMatch(/aria-label="Send — creates the task"[^>]*disabled/);
    expect(markup).toContain('aria-label="Remove image failure.png"');
    expect(markup).not.toMatch(/aria-label="Remove image failure.png"[^>]*disabled/);
    expect(markup).not.toContain('Starting task…');
  });
});
