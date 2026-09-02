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
import { AgentTaskList } from './AgentTasksView';
import { AgentTaskBody } from './AgentTaskScreen';
import fixture from './fixtures/agent-events-v2.json';
import { parseAgentEventSequence } from './events';
import { initialAgentTaskState, type AgentTaskState } from './model';
import { previewTask } from './preview';
import type { AgentTaskDto } from '../types';

const events = parseAgentEventSequence(fixture.events);

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

describe('agent task list', () => {
  it('separates open from finished and never hides that the data is fixture data', () => {
    const markup = renderToStaticMarkup(
      <AgentTaskList
        tasks={[task('t-1', 'OPEN', 'Rename the widget'), task('t-2', 'COMPLETED', 'Old work')]}
        loading={false}
        error={null}
        onCreate={() => {}}
        onOpenTask={() => {}}
      />);

    expect(markup).toContain('1 open');
    expect(markup).toContain('1 finished');
    expect(markup).toContain('Rename the widget');
    expect(markup).toContain('New agent task');
    // The preview must never read as a live task.
    expect(markup).toContain('Fixture data');
  });

  it('shows loading, empty and error states rather than an unexplained blank list', () => {
    expect(renderToStaticMarkup(
      <AgentTaskList tasks={[]} loading error={null} onCreate={() => {}} onOpenTask={() => {}} />))
      .toContain('Loading agent tasks');
    expect(renderToStaticMarkup(
      <AgentTaskList tasks={[]} loading={false} error={null} onCreate={() => {}} onOpenTask={() => {}} />))
      .toContain('No agent tasks yet');
    expect(renderToStaticMarkup(
      <AgentTaskList tasks={[]} loading={false} error="backend refused" onCreate={() => {}} onOpenTask={() => {}} />))
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

    expect(idle).toContain('Send a message to this task');
    expect(idle).toContain('>Send<');
    // Idle: Send is live, Cancel and only Cancel is disabled.
    expect(idle).toContain('>Complete<');
    expect(idle.match(/disabled/g)).toHaveLength(1);
  });

  it('says it is reconnecting without claiming anything was lost', () => {
    const markup = body({ connection: 'reconnecting', connectionDetail: 'stream closed' });

    expect(markup).toContain('agt-conn is-reconnecting');
    expect(markup).toContain('Nothing is lost');
    expect(markup).toContain('stream closed');
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
