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
import fixture from './fixtures/agent-events-v2.json';
import { parseAgentEventSequence } from './events';
import { AgentHomeView } from './AgentHome';
import { createPreviewBridge, previewTask } from './preview';
import { TaskCard } from './TaskCard';
import { TaskOverviewView } from './TaskOverview';
import { NO_AGENT_CAPABILITIES } from './capabilities';
import type { ModelOptionDto, ModelProviderDto } from '../types';

const events = parseAgentEventSequence(fixture.events);
const MODELS: ModelOptionDto[] = [
  { id: 'gpt-5.6-sol', name: '5.6 Sol', description: '', efforts: ['high'], defaultEffort: 'high', inputModalities: ['text'] },
];
const PROVIDERS: ModelProviderDto[] = [{
  id: 'codex',
  label: 'Codex',
  readiness: 'READY',
  version: '0.1.0',
  diagnostic: '',
  capabilities: NO_AGENT_CAPABILITIES,
  models: MODELS,
  selectedModel: 'gpt-5.6-sol',
  selectedEffort: 'high',
  main: true,
}];
const NOW = Date.parse('2026-08-20T10:12:00Z');

function task(id: string, title: string, claim: string | null = null) {
  return {
    ...previewTask('workspace-1', id),
    title,
    defaults: { model: 'gpt-5.6-sol', effort: 'high', permissionPolicy: 'AUTO' },
    updatedAt: '2026-08-20T10:00:00Z',
    mutationClaim: claim === null ? null : { kind: claim, ownerId: 'turn-1', acquiredAt: '2026-08-20T10:00:00Z' },
  };
}

describe('task window', () => {
  it('scales the task screen into a card with the opening exchange and a caption', () => {
    const markup = renderToStaticMarkup(
      <TaskCard task={task('t-1', 'Fix flaky preview', 'TURN')} events={events} providers={PROVIDERS} width={294} now={NOW} onOpen={() => {}} />);

    // One unitless width drives both the thumbnail height and the screen's scale.
    expect(markup).toContain('style="--card-w:294"');
    expect(markup).toContain('aria-label="Open task: Fix flaky preview"');
    expect(markup).toContain('‹ Tasks');
    expect(markup).toContain('5.6 Sol · 12m');
    // The window shows the task's own first events, not a stand-in.
    expect(markup).toContain('Review the authentication change');
    expect(markup).toContain('>Running<');
    // Without a close handler the card cannot close its task.
    expect(markup).not.toContain('Close task');
  });

  it('marks the task behind the overview and offers to close idle tasks only', () => {
    const idle = renderToStaticMarkup(
      <TaskCard task={task('t-1', 'Audit storage')} events={[]} providers={PROVIDERS} width={294} active now={NOW} onOpen={() => {}} onClose={() => {}} />);
    expect(idle).toContain('agt-card is-active');
    expect(idle).toMatch(/<button[^>]*class="agt-card__close"(?![^>]*disabled)[^>]*aria-label="Close task: Audit storage"/);

    const running = renderToStaticMarkup(
      <TaskCard task={task('t-2', 'Still going', 'TURN')} events={[]} providers={PROVIDERS} width={200} now={NOW} onOpen={() => {}} onClose={() => {}} />);
    expect(running).toMatch(/<button[^>]*class="agt-card__close"[^>]*disabled/);
    expect(running).toContain('Complete unlocks when the task is idle');
  });
});

describe('agent home and overview', () => {
  const bridge = () => createPreviewBridge('workspace-1');

  it('asks what the agent should do and shows the open tasks as windows', () => {
    const markup = renderToStaticMarkup(
      <AgentHomeView
        repository="jackchen/trino"
        laneId="codex"
        providers={PROVIDERS}
        bridge={bridge}
        tasks={[task('t-1', 'Fix flaky preview', 'TURN'), task('t-2', 'Audit storage')]}
        models={MODELS}
        selection={{ model: 'gpt-5.6-sol', effort: 'high' }}
        draft=""
        blocker={null}
        onDraftChange={() => {}}
        onSelectModel={() => {}}
        onSelectProvider={() => {}}
        onCreate={() => {}}
        onOpenTask={() => {}}
        onShowAll={() => {}}
      />);

    expect(markup).toContain('What should the agent do in jackchen/trino?');
    expect(markup.match(/class="agt-suggestion"/g)).toHaveLength(3);
    expect(markup).toContain('Open tasks · 2');
    expect(markup).toContain('⇧⌘\\');
    expect(markup).toContain('style="--card-w:200"');
    expect(markup).toContain('aria-label="Describe a task"');
    expect(markup).not.toContain('Describe a task — sending the first message creates it…');
    expect(markup).not.toContain('master');
  });

  it('lays every open task out as a closable window with a place to start another', () => {
    const markup = renderToStaticMarkup(
      <TaskOverviewView
        tasks={[task('t-1', 'Fix flaky preview', 'TURN'), task('t-2', 'Audit storage')]}
        providers={PROVIDERS}
        bridge={bridge}
        notice={null}
        activeTaskId="t-2"
        onOpenTask={() => {}}
        onCloseTask={() => {}}
        onNewTask={() => {}}
      />);

    expect(markup).toContain('role="dialog"');
    expect(markup).toContain('aria-label="All tasks"');
    expect(markup).toContain('2 open · 1 running');
    expect(markup.match(/aria-label="Close task: /g)).toHaveLength(2);
    expect(markup).toContain('agt-card is-active');
    expect(markup).toContain('aria-label="New task"');
    expect(markup).toContain('toggle overview');
    expect(markup).toContain('back to the current task');
  });
});
