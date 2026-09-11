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
import { ciActivity, DevelopmentCiStatus } from './CiAutofixPanel';
import { parseAgentDevelopmentCi, parseAgentTask, parseCiAutofixRun } from './contracts';
import { AgentTaskBody } from './AgentTaskScreen';
import { canComplete, canQueue, canSend, initialAgentTaskState } from './model';
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

it('derives CI activity from observed run facts without claiming the PR is ready', () => {
  expect(ciActivity({ ...CI_PROJECTION, status: 'ACTIVE', activity: 'WATCHING_CI' })).toBe('Waiting for CI');
  expect(ciActivity({ ...CI_PROJECTION, status: 'ACTIVE', activity: 'FIXING', attemptsUsed: 2 })).toBe('Fixing CI 2/3');
  expect(ciActivity({ ...CI_PROJECTION, status: 'ACTIVE', activity: 'REBASING' })).toBe('Rebasing CI changes');
  expect(ciActivity(CI_PROJECTION)).toBe('CI needs your input');
  expect(ciActivity({ ...CI_PROJECTION, canMessage: false })).toBe('CI Autofix paused');
  const html = renderToStaticMarkup(<DevelopmentCiStatus ci={{ ...CI_PROJECTION, status: 'FINISHED', finishReason: 'GREEN' }} active={false} onOpen={() => {}} />);
  expect(html).toContain('CI passed');
  expect(html).toContain('1/3 repair attempts');
  expect(html).toContain('Open CI Autofix');
  expect(html).not.toContain('ready for review');
});

it('keeps source input drafts while CI owns Send, queue and Complete', () => {
  const task = { ...previewTask('workspace-1', 'source-task'), interactive: true, developmentContinuationUnfinished: true, developmentCi: CI_PROJECTION };
  const state = { ...initialAgentTaskState(), task, composer: 'Keep this source draft', connection: 'open' as const };
  expect(canSend(state)).toBe(false);
  expect(canQueue(state)).toBe(false);
  expect(canComplete(state)).toBe(false);
  const html = renderToStaticMarkup(<AgentTaskBody state={state} models={[]} onBack={() => {}} onSend={() => {}} onCancel={() => {}}
    onComplete={() => {}} onDecide={() => {}} onCompose={() => {}} onSelectModel={() => {}} onStopServer={() => {}} onMarkFailed={() => {}} onOpenDevelopmentCi={() => {}} />);
  expect(html).toContain('CI Autofix is running · Stop to start over');
  expect(html).toContain('Keep this source draft</textarea>');
  expect(html).not.toMatch(/<textarea[^>]*disabled/);
  expect(html).toContain('aria-label="Stop automation"');
  expect(html).toMatch(/<button[^>]*aria-label="Mark it complete"[^>]*disabled/);
  expect(html).toContain('Open CI Autofix');
});

it('shows a prior finished CI run as history while another development continuation runs', () => {
  const task: AgentTaskDto = { ...previewTask('workspace-1', 'source-task'), interactive: true, mutationClaim: null, developmentContinuationUnfinished: true,
    developmentCi: { ...CI_PROJECTION, status: 'FINISHED' as const, finishReason: 'GREEN', canMessage: false } };
  const state = { ...initialAgentTaskState(), task, connection: 'open' as const };
  const html = renderToStaticMarkup(<AgentTaskBody state={state} models={[]} onBack={() => {}} onSend={() => {}} onCancel={() => {}}
    onComplete={() => {}} onDecide={() => {}} onCompose={() => {}} onSelectModel={() => {}} onStopServer={() => {}} onMarkFailed={() => {}} onOpenDevelopmentCi={() => {}} />);
  expect(html).toContain('>Automation running<');
  expect(html).toContain('CI passed');
  expect(html).toContain('Automation is running. Your next message stays in the composer until it finishes.');
  expect(html).not.toContain('CI Autofix is running');
});
