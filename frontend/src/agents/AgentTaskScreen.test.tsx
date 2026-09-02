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

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { AgentTaskList } from './AgentTasksView';
import { AgentTaskBody } from './AgentTaskScreen';
import fixture from './fixtures/agent-events-v2.json';
import { parseAgentEventSequence } from './events';
import { initialAgentTaskState, type AgentTaskState } from './model';
import { previewTask } from './preview';
import type { AgentTaskDto, ModelOptionDto } from '../types';

const events = parseAgentEventSequence(fixture.events);

const MODELS: ModelOptionDto[] = [
  { id: 'gpt-5.6-codex', name: '5.6 Terra', description: 'Balanced', efforts: ['medium', 'high'], defaultEffort: 'high' },
];

/** Everything the list needs beyond its rows, so each test states only what it varies. */
const listProps = {
  laneId: 'codex',
  models: MODELS,
  selection: { model: 'gpt-5.6-codex', effort: 'high' as string | null },
  creatable: true,
  onSelectModel: () => {},
  onCreate: () => {},
  onOpenTask: () => {},
};

function task(id: string, state: string, title: string): AgentTaskDto {
  return { ...previewTask('workspace-1', id), state, title };
}

function body(state: Partial<AgentTaskState>): string {
  return renderToStaticMarkup(
    <AgentTaskBody
      state={{ ...initialAgentTaskState(), ...state }}
      onBack={() => {}}
      onSend={() => {}}
      onCancel={() => {}}
      onComplete={() => {}}
      onDecide={() => {}}
      onCompose={() => {}}
    />);
}

const css = readFileSync(resolve(process.cwd(), 'src/css/agents.css'), 'utf8');

/** The declarations of one exact selector, as `property: value` strings. */
function declarations(selector: string): string[] {
  const start = css.indexOf(`${selector} {`);
  expect(start, `${selector} is missing from agents.css`).toBeGreaterThan(-1);
  return css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start))
    .split(';')
    .map(declaration => declaration.replace(/\/\*[^]*?\*\//g, '').trim())
    .filter(declaration => declaration !== '');
}

describe('agent task layout', () => {
  it('scrolls the transcript rather than the screen, so the composer stays reachable', () => {
    // A long transcript must move under a fixed header and composer, not push them off-screen.
    expect(declarations('.agt-screen')).toContain('height: 100%');
    expect(declarations('.agt-screen')).toContain('overflow: hidden');
    expect(declarations('.agt-transcript')).toContain('overflow-y: auto');
    expect(declarations('.agt-transcript')).toContain('flex: 1');
    // A flex child will not shrink below its content without this, which re-breaks the scroll.
    expect(declarations('.agt-transcript')).toContain('min-height: 0');
  });
});

describe('agent task list', () => {
  it('separates open from finished and never hides that the data is fixture data', () => {
    const markup = renderToStaticMarkup(
      <AgentTaskList
        {...listProps}
        tasks={[task('t-1', 'OPEN', 'Rename the widget'), task('t-2', 'COMPLETED', 'Old work')]}
        loading={false}
        error={null}
        />);

    // Asserting the separator too: a JSX text node does not interpret \\u escapes, so a
    // hand-written one renders literally and only an exact match catches it.
    expect(markup).toContain('1 open \u00b7 1 finished');
    expect(markup).not.toContain('u00b7');
    expect(markup).toContain('Rename the widget');
    expect(markup).toContain('New agent task');
    // Wire enums never reach the user.
    expect(markup).toContain('>Running<');
    expect(markup).toContain('>Completed<');
    expect(markup).not.toContain('OPEN');
    // The preview must never read as a live task.
    expect(markup).toContain('Fixture data');
  });

  it('pins the lane and offers the catalog model, not an invented one', () => {
    const markup = renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading={false} error={null} />);

    // The lane is fixed at creation, so it must not render as a picker.
    expect(markup).toContain('Codex');
    expect(markup).toContain('agt-lane');
    expect(markup).toContain('5.6 Terra');
    expect(markup).toContain('high');
    expect(markup).not.toContain('disabled=""');
  });

  it('refuses to offer creation when the provider has no model to run', () => {
    // The backend rejects a blank model, so an empty catalog must block the button, not the POST.
    const markup = renderToStaticMarkup(
      <AgentTaskList
        {...listProps}
        models={[]}
        selection={null}
        creatable={false}
        tasks={[]}
        loading={false}
        error={null}
      />);

    expect(markup).toMatch(/<button[^>]*disabled[^>]*>\s*New agent task/);
    expect(markup).toContain('No Codex model is available yet');
    // The catalog is only fetched by a readiness check, so the copy must send them there.
    expect(markup).toContain('Workspace settings');
    expect(markup).toContain('also fetches its models');
  });

  it('shows loading, empty and error states rather than an unexplained blank list', () => {
    expect(renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading error={null} />))
      .toContain('Loading agent tasks\u2026');
    expect(renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading={false} error={null} />))
      .toContain('No agent tasks yet');
    expect(renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading={false} error="backend refused" />))
      .toContain('backend refused');
  });
});

describe('agent task detail', () => {
  it('prints the whole fixture transcript', () => {
    const markup = body({ task: task('t-1', 'OPEN', 'Rename the widget'), events, connection: 'open' });

    expect(markup).toContain('Rename the widget');
    expect(markup).toContain('You ');
    expect(markup).toContain('Plan');
    expect(markup).toContain('Command');
    expect(markup).toContain('Approval');
    expect(markup).toContain('Usage');
    expect(markup).toContain('Live');
  });

  it('anchors a composer with Send, Cancel and idle-only Complete', () => {
    const idle = body({ task: task('t-1', 'OPEN', 'x'), connection: 'open', composer: 'go' });

    expect(idle).toContain('Message the agent');
    expect(idle).toContain('>Send<');
    expect(idle).toContain('Complete');
    // Idle with no turn: Send is live, and Cancel alone is disabled because there is nothing to cancel.
    expect(idle.match(/disabled/g)).toHaveLength(1);
    expect(idle).toMatch(/<button[^>]*disabled[^>]*>Cancel</);
    // A disabled Complete has to say why it is disabled.
    expect(body({ connection: 'open' })).toContain('Available when the task is idle');
  });

  it('says Connecting in the composer until the stream is open', () => {
    expect(body({ connection: 'connecting' })).toContain('placeholder="Connecting\u2026"');
    expect(body({ connection: 'open' })).toContain('placeholder="Message the agent"');
  });

  it('keeps a healthy connection in the header instead of spending a row on it', () => {
    const open = body({ connection: 'open' });
    // Live state is the header dot only; no full-width strip.
    expect(open).toContain('agt-conn-dot is-open');
    expect(open).not.toContain('class="agt-conn ');
    expect(open).toContain('aria-label="Live"');
  });

  it('says it is reconnecting, from where, without claiming anything was lost', () => {
    const markup = body({ connection: 'reconnecting', lastSequence: 214 });

    expect(markup).toContain('agt-conn is-reconnecting');
    // The resume point is the load-bearing part: "catching up" alone asks for faith.
    expect(markup).toContain('catching up from event 214');
    expect(markup).toContain('Nothing is lost');
  });

  it('offers a decision on a pending approval and none on a settled one', () => {
    const markup = body({ events, connection: 'open' });

    // The fixture carries both, so the reviewable state is not only the settled one.
    expect(markup).toContain('Allow once');
    expect(markup).toContain('>Deny<');
    expect(markup).toContain('Approval \u00b7 COMMAND \u00b7 ALLOWED');
    expect(markup).toContain('git push origin fix/preview-limit');
  });

  it('surfaces a held gap and a terminal error instead of a silent hole', () => {
    expect(body({ connection: 'open', gap: true })).toContain('waiting for a missing event');

    const failed = body({ connection: 'failed', connectionDetail: 'stream refused with status 404' });
    expect(failed).toContain('agt-conn is-failed');
    expect(failed).toContain('status 404');

    const errored = body({ events, connection: 'open' });
    expect(errored).toContain('agt-item is-error');
    expect(errored).toContain('role="alert"');
  });
});
