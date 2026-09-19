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
import { createPreviewBridge, previewTask } from './fixtures/previewBridge';
import { TaskComposer } from './AgentTasksView';
import { gridColumnWidth, TaskCard, wantsAttention } from './TaskCard';
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
    // The thumbnail names the lane and model; the footer names the task and its state, nothing more.
    expect(markup).toContain('Codex · 5.6 Sol');
    expect(markup).not.toContain('· 12m');
    expect(markup).toMatch(/class="agt-card__foot"><span class="agt-card__name">Fix flaky preview<\/span><span class="agt-state is-running">/);
    expect(markup).not.toContain('❋');
    // The window shows the task's own events, not a stand-in.
    expect(markup).toContain('Review the authentication change');
    expect(markup).toContain('>Running<');
    // Without a close, rename or hide handler the card offers none of them.
    expect(markup).not.toContain('Close task');
    expect(markup).not.toContain('agt-card__tools');
  });

  it('folds a finished turn to its answer and keeps a live one open, as the screen does', () => {
    const render = (shown: typeof events) => renderToStaticMarkup(
      <TaskCard task={task('t-1', 'Fix flaky preview')} events={shown} providers={PROVIDERS} width={294} now={NOW} onOpen={() => {}} />);

    const finished = render(events);
    expect(finished).toContain('aria-expanded="false"');
    expect(finished).toContain('I found one authentication regression.');
    expect(finished).not.toContain('npm test');

    const live = render(events.filter((event) => !(event.type === 'lifecycle' && event.payload.phase === 'TURN_COMPLETED')));
    expect(live).toContain('aria-expanded="true"');
    expect(live).toContain('npm test');
  });

  it('offers rename and hide only where Home passes them, and scales its screen to the grid column', () => {
    const markup = renderToStaticMarkup(
      <TaskCard task={task('t-1', 'Audit storage')} events={[]} providers={PROVIDERS} width={250} now={NOW} onOpen={() => {}} onRename={() => {}} onHide={() => {}} />);
    expect(markup).toContain('aria-label="Rename task: Audit storage"');
    expect(markup).toContain('aria-label="Hide task from Home: Audit storage"');
    expect(markup).toContain('title="Double-click to rename"');

    // Three columns fit in 900px at the 250px minimum, and each is what is left after the gaps.
    expect(gridColumnWidth(900)).toBe(289);
    expect(gridColumnWidth(1400)).toBe(267);
    expect(gridColumnWidth(0)).toBe(250);
  });

  it('brings a hidden window back only for attention that started after it was put away', () => {
    const away = Date.parse('2026-08-20T10:05:00Z');
    expect(wantsAttention(away, Date.parse('2026-08-20T10:00:00Z'), null)).toBe(false);
    expect(wantsAttention(away, null, Date.parse('2026-08-20T10:04:59Z'))).toBe(false);
    expect(wantsAttention(away, null, Date.parse('2026-08-20T10:06:00Z'))).toBe(true);
    expect(wantsAttention(away, Date.parse('2026-08-20T10:06:00Z'), null)).toBe(true);
    expect(wantsAttention(null, Date.parse('2026-08-20T10:06:00Z'), null)).toBe(false);
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

  it('names the repository, keeps the composer in the page, and lays the open tasks out as a grid', () => {
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

    expect(markup).toContain('aria-label="jackchen/trino"');
    expect(markup).toContain('class="agt-home__repo">jackchen/trino<');
    expect(markup).not.toContain('What should the agent do');
    expect(markup).not.toContain('❋');
    expect(markup.match(/class="agt-start"/g)).toHaveLength(3);
    expect(markup).toContain('lucide-search');
    expect(markup).toContain('lucide-circle-dot');
    expect(markup).toContain('lucide-list-checks');
    expect(markup).toContain('Open tasks · 2');
    expect(markup).toContain('⇧⌘\\');
    // Windows fill a grid whose column is measured in the browser; a static render uses the minimum.
    expect(markup).toContain('class="agt-windows-grid"');
    expect(markup.match(/style="--card-w:250"/g)).toHaveLength(2);
    expect(markup.match(/aria-label="Rename task: /g)).toHaveLength(2);
    // The composer sits under the repository, not docked to the bottom of the page.
    expect(markup.indexOf('agt-compose')).toBeLessThan(markup.indexOf('agt-open-tasks'));
    // A quiet repository shows no attention section at all.
    expect(markup).not.toContain('Needs attention');
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
        onDismiss={() => {}}
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


describe('new task image composer', () => {
  const models = [{ ...MODELS[0], inputModalities: ['text', 'image'] }];
  const providers = [{ ...PROVIDERS[0], models, capabilities: { ...NO_AGENT_CAPABILITIES, attachmentMediaTypes: ['image/png'] } }];
  const props = {
    laneId: 'codex', providers, models, selection: { model: models[0].id, effort: 'high' }, draft: 'Review this screenshot', blocker: null as string | null,
    onDraftChange: () => {}, onSelectModel: () => {}, onSelectProvider: () => {}, onCreate: () => {}, onAttach: () => {},
  };

  it('shows clickable image thumbnails with an accessible remove control and no filename row', () => {
    const markup = renderToStaticMarkup(<TaskComposer {...props} images={[{ id: 'draft-1', name: 'failure.png', url: 'blob:preview' }]} onRemoveImage={() => {}} />);
    expect(markup).toMatch(/aria-label="Attach an image"[^>]*title="Attach images or paste with ⌘V"[^>]*>/);
    expect(markup).not.toMatch(/aria-label="Attach an image"[^>]*disabled/);
    expect(markup).toContain('src="blob:preview" alt="failure.png"');
    expect(markup).toMatch(/<button[^>]*aria-label="Preview image failure.png"/);
    expect(markup).toContain('aria-label="Remove image failure.png"');
    expect(markup).not.toContain('agt-draft__name');
    expect(markup.replace(/<[^>]*>/g, '')).not.toContain('failure.png');
  });

  it('requires model and provider image support, and disables attachment mutation during submission', () => {
    expect(renderToStaticMarkup(<TaskComposer {...props} models={MODELS} />))
      .toMatch(/aria-label="Attach an image"[^>]*disabled/);
    expect(renderToStaticMarkup(<TaskComposer {...props} providers={PROVIDERS} />))
      .toMatch(/aria-label="Attach an image"[^>]*disabled/);
    const busy = renderToStaticMarkup(<TaskComposer {...props} busy images={[{ id: 'draft-1', name: 'failure.png', url: 'blob:preview' }]} />);
    expect(busy).toMatch(/aria-label="Attach an image"[^>]*disabled/);
    expect(busy).toMatch(/aria-label="Remove image failure.png"[^>]*disabled/);
    expect(busy).toMatch(/aria-label="Send — creates the task"[^>]*disabled/);
    expect(busy).toMatch(/<textarea[^>]*aria-label="Describe a task"[^>]*disabled/);
  });

  it('keeps pasted text editable while its image loads and prevents sending before attachment finishes', () => {
    const markup = renderToStaticMarkup(<TaskComposer {...props} busy attaching />);
    expect(markup).not.toMatch(/<textarea[^>]*aria-label="Describe a task"[^>]*disabled/);
    expect(markup).toMatch(/aria-label="Attach an image"[^>]*disabled/);
    expect(markup).toMatch(/aria-label="Send — creates the task"[^>]*disabled/);
  });
});
