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

import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { expect, it } from 'vitest';
import type { AgentTaskDto } from '../types';
import { ciActivity, ciMessage, ciMessageUnavailable } from './ciAutofix';
import { parseAgentDevelopmentCi, parseAgentTask, parseCiAutofixRun } from './contracts';
import { AgentTaskBody } from './AgentTaskScreen';
import { initialAgentTaskState, type AgentTaskState } from './model';
import { CI_PROJECTION, CI_RUN } from './fixtures/ciAutofix';
import { previewTask } from './fixtures/previewBridge';
import contracts from './fixtures/agent-contracts-v1.json';

it('narrows CI projections and requires explicit current permission for child messages', () => {
  expect(parseAgentDevelopmentCi({ ...CI_PROJECTION, privatePath: '/secret' })).toEqual(CI_PROJECTION);
  expect(parseAgentTask({ ...contracts.task, developmentCi: CI_PROJECTION }).developmentCi).toEqual(CI_PROJECTION);
  expect(parseCiAutofixRun({ ...CI_RUN, runtimePath: '/secret' })).toEqual(CI_RUN);
  expect(() => parseAgentDevelopmentCi({ ...CI_PROJECTION, canMessage: 'true' })).toThrow();
  expect(() => parseCiAutofixRun({ ...CI_RUN, canMessage: undefined })).toThrow();
  expect(() => parseAgentDevelopmentCi({ ...CI_PROJECTION, status: 'READY' })).toThrow();
});

it('recognizes a standalone CI recipient without treating emails or longer handles as routing', () => {
  expect(ciMessage('@ci Use the compatibility fix')).toBe('Use the compatibility fix');
  expect(ciMessage('  @CI\nTry again  ')).toBe('Try again');
  expect(ciMessage('Please fix this @ci')).toBe('Please fix this');
  expect(ciMessage('@ci')).toBe('');
  for (const text of ['Continue development', 'build@ci', '@circle fix this', '@ci-bot help']) {
    expect(ciMessage(text)).toBeNull();
  }
});

const paused: AgentTaskState & { task: AgentTaskDto } = {
  ...initialAgentTaskState(), connection: 'open' as const, composer: '@ci Use the compatibility fix',
  task: { ...previewTask('workspace-1', 'source-task'), interactive: true, mutationClaim: null, developmentContinuationUnfinished: true, developmentCi: CI_PROJECTION },
};

it('requires current paused CI authority and a bounded text message', () => {
  expect(ciMessageUnavailable(paused)).toBeNull();
  expect(ciMessageUnavailable({ ...paused, composer: '@ci' })).not.toBeNull();
  expect(ciMessageUnavailable({ ...paused, composer: '@ci ' + 'a'.repeat(16_384) })).toBeNull();
  expect(ciMessageUnavailable({ ...paused, composer: '@ci ' + 'a'.repeat(16_385) })).not.toBeNull();
  expect(ciMessageUnavailable({ ...paused, connection: 'reconnecting' })).not.toBeNull();
  expect(ciMessageUnavailable({ ...paused, sending: true })).not.toBeNull();
  for (const ci of [undefined, { ...CI_PROJECTION, canMessage: false }, { ...CI_PROJECTION, status: 'ACTIVE' as const }, { ...CI_PROJECTION, status: 'FINISHED' as const }]) {
    expect(ciMessageUnavailable({ ...paused, task: { ...paused.task, developmentCi: ci } })).not.toBeNull();
  }
  expect(ciMessageUnavailable({ ...paused, task: { ...paused.task, developmentContinuationUnfinished: false } })).not.toBeNull();
  expect(ciMessageUnavailable({ ...paused, task: { ...paused.task, state: 'COMPLETED' } })).not.toBeNull();
});

it('keeps CI activity in the task header and routes the composer without a status or history panel', () => {
  expect(ciActivity(CI_PROJECTION)).toBe('CI needs your input');
  const render = (state: AgentTaskState = paused) => renderToStaticMarkup(<AgentTaskBody state={state} models={[]} onBack={() => {}} onSend={() => {}} onCancel={() => {}}
    onComplete={() => {}} onDecide={() => {}} onCompose={() => {}} onSelectModel={() => {}} onStopServer={() => {}} onMarkFailed={() => {}} />);
  const html = render();
  expect(html).toContain('CI needs your input');
  expect(html).toContain('aria-label="Send to CI agent"');
  expect(html).toContain('aria-label="Stop automation"');
  expect(html).not.toContain('>Steer<');
  expect(html).not.toContain('>Run next<');
  for (const label of ['CI Autofix status', 'CI Autofix run', 'View CI Autofix history', 'Open CI Autofix', 'repair attempts']) {
    expect(html).not.toContain(label);
  }
  const finished = render({ ...paused, task: { ...paused.task, developmentContinuationUnfinished: false,
    developmentCi: { ...CI_PROJECTION, status: 'FINISHED', finishReason: 'CANCELLED', canMessage: false } } });
  expect(finished).toContain('This CI Autofix run has ended.');
  expect(finished).not.toContain('Previous CI Autofix run');
  expect(finished).toMatch(/<button[^>]*aria-label="Send to CI agent"[^>]*disabled/);
});
