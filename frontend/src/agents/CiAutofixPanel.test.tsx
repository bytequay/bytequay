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
import type { AgentTaskDto, PullRequestDetailDto } from '../types';
import { ciActivity, DevelopmentCiStatus } from './CiAutofixPanel';
import { parseAgentDevelopmentCi, parseAgentTask, parseAgentTurn, parseCiAutofixRun } from './contracts';
import { AgentTaskBody } from './AgentTaskScreen';
import { canComplete, canQueue, canSend, initialAgentTaskState } from './model';
import { CI_PROJECTION, CI_RUN } from './fixtures/ciAutofix';
import { previewTask } from './fixtures/previewBridge';
import { NO_PULL_REQUEST_DETAIL } from './TaskPullRequestPanel';
import contracts from './fixtures/agent-contracts-v1.json';

const checks: Pick<PullRequestDetailDto, 'repo' | 'number' | 'ciStatus' | 'unavailableSections'> = {
  repo: 'acme/demo', number: 17, ciStatus: 'PASSING', unavailableSections: [],
};

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
  expect(html).toContain('Previous CI Autofix run');
  expect(html).toContain('View CI Autofix history');
  expect(html).not.toContain('ready for review');
});

it.each([
  ['CANCELLED', 'CI Autofix stopped'],
  ['ERROR', 'CI Autofix failed'],
  ['MAX_ATTEMPTS', 'CI repair limit reached'],
  ['AUTHORIZATION_EXPIRED', 'CI Autofix authorization expired'],
  ['STALE_HEAD', 'CI Autofix stopped: PR branch changed'],
])('explains a finished %s run without claiming CI passed', (finishReason, label) => {
  expect(ciActivity({ ...CI_PROJECTION, status: 'FINISHED', finishReason })).toBe(label);
});

it.each([
  ['Automation stopped. CI Autofix: GitHub Actions log download failed with status 404', true],
  ['Automation stopped. Auto commit: The worktree changed unexpectedly', false],
])('keeps the stopped reason %s in its proper context', (reason, historical) => {
  const ci = { ...CI_PROJECTION, status: 'FINISHED' as const, finishReason: 'CANCELLED', canMessage: false, attemptsUsed: 0, terminalAt: '2026-09-13T09:03:41Z' };
  const task: AgentTaskDto = { ...previewTask('workspace-1', 'source-task'), interactive: true, mutationClaim: null, developmentContinuationUnfinished: false, developmentCi: ci };
  const turn = parseAgentTurn({ ...contracts.turn, taskId: task.id, createdAt: '2026-09-13T09:00:00Z' });
  const state = { ...initialAgentTaskState(), task, turn, latestTurnId: turn.id, connection: 'open' as const, developmentProgress: { sequence: 12, message: reason } };
  const detail: PullRequestDetailDto = {
    ...checks, id: 17, title: 'Fix the build', htmlUrl: ci.pullRequestUrl, state: 'open', draft: true, mergeable: null, mergeableState: null,
    author: 'alice', authorAvatarUrl: null, createdAt: null, updatedAt: null, mergedAt: null, baseRef: 'main', headRef: ci.headRef,
    body: '', labels: [], assignees: [], milestone: null, reviewers: [], additions: 0, deletions: 0, changedFiles: 0,
    approvalCount: 0, recentActivity: [], checkRuns: [], viewerCanWrite: false,
  };
  const html = renderToStaticMarkup(<AgentTaskBody state={state} models={[]} onBack={() => {}} onSend={() => {}} onCancel={() => {}}
    onComplete={() => {}} onDecide={() => {}} onCompose={() => {}} onSelectModel={() => {}} onStopServer={() => {}} onMarkFailed={() => {}}
    onOpenDevelopmentCi={() => {}} pullRequestDetail={{ ...NO_PULL_REQUEST_DETAIL, detail }} />);
  expect(html).toContain('CI Autofix stopped');
  expect(html).toContain('Previous CI Autofix run');
  expect(html).toContain('View CI Autofix history');
  expect(html).toContain('Latest known PR checks: <strong>Passed</strong>');
  expect(html).toContain('This run has ended and is no longer monitoring CI.');
  expect(html).toContain(reason);
  const history = html.match(/<details class="agt-ci-history">[\s\S]*?<\/details>/)?.[0];
  expect(history).toBeDefined();
  expect(history?.includes(reason)).toBe(historical);
  expect(html.includes('role="alert"')).toBe(!historical);
  expect(html).not.toContain('CI Autofix is running');
  expect(html).not.toContain('CI passed');
});

it.each([
  { name: 'a newer CI attachment failure', terminalAt: '2026-09-13T09:03:41Z', createdAt: '2026-09-13T09:04:00Z', latestTurnId: '<turn-1>' },
  { name: 'an absent CI stop time', terminalAt: null, createdAt: '2026-09-13T09:00:00Z', latestTurnId: '<turn-1>' },
  { name: 'an absent loaded turn', terminalAt: '2026-09-13T09:03:41Z', createdAt: null, latestTurnId: '<turn-1>' },
  { name: 'an unreadable turn time', terminalAt: '2026-09-13T09:03:41Z', createdAt: '', latestTurnId: '<turn-1>' },
  { name: 'a newer turn whose details have not loaded', terminalAt: '2026-09-13T09:03:41Z', createdAt: '2026-09-13T09:00:00Z', latestTurnId: 'newer-turn' },
])('keeps $name visible beside older CI history', ({ terminalAt, createdAt, latestTurnId }) => {
  const ci = { ...CI_PROJECTION, status: 'FINISHED' as const, finishReason: 'CANCELLED', canMessage: false, terminalAt };
  const task: AgentTaskDto = { ...previewTask('workspace-1', 'source-task'), interactive: true, mutationClaim: null, developmentContinuationUnfinished: false, developmentCi: ci };
  const turn = createdAt === null ? null : parseAgentTurn({ ...contracts.turn, taskId: task.id, createdAt });
  const reason = 'Automation stopped. CI Autofix: Could not attach a new CI run';
  const state = { ...initialAgentTaskState(), task, turn, latestTurnId, connection: 'open' as const, developmentProgress: { sequence: 12, message: reason } };
  const html = renderToStaticMarkup(<AgentTaskBody state={state} models={[]} onBack={() => {}} onSend={() => {}} onCancel={() => {}}
    onComplete={() => {}} onDecide={() => {}} onCompose={() => {}} onSelectModel={() => {}} onStopServer={() => {}} onMarkFailed={() => {}} />);
  expect(html).toContain('Previous CI Autofix run');
  expect(html).toContain(`<div class="agt-notice" role="alert">${reason}</div>`);
  expect(html.match(/<details class="agt-ci-history">[\s\S]*?<\/details>/)?.[0]).not.toContain(reason);
});

it.each([
  ['PASSING', 'Passed'], ['FAILING', 'Failing'], ['PENDING', 'In progress'], ['NONE', 'No checks reported'],
] as const)('distinguishes latest known %s checks from a stopped run', (ciStatus, label) => {
  const html = renderToStaticMarkup(<DevelopmentCiStatus ci={{ ...CI_PROJECTION, status: 'FINISHED', finishReason: 'CANCELLED' }}
    active={false} checks={{ ...checks, ciStatus }} />);
  expect(html).toContain(`Latest known PR checks: <strong>${label}</strong>`);
  expect(html).toContain('CI Autofix stopped');
  expect(html).not.toContain('CI passed');
});

it.each([
  { ...checks, repo: 'other/demo' }, { ...checks, number: 18 }, null,
])('does not borrow passing checks from another or unloaded PR: %s', detail => {
  const html = renderToStaticMarkup(<DevelopmentCiStatus ci={{ ...CI_PROJECTION, status: 'FINISHED', finishReason: 'CANCELLED' }} active={false} checks={detail} />);
  expect(html).not.toContain('Latest known PR checks:');
  expect(html).not.toContain('Passed');
});

it('does not claim passing when the matching PR checks are unavailable', () => {
  const html = renderToStaticMarkup(<DevelopmentCiStatus ci={{ ...CI_PROJECTION, status: 'FINISHED', finishReason: 'CANCELLED' }}
    active={false} checks={{ ...checks, unavailableSections: ['checks'] }} />);
  expect(html).toContain('Latest known PR checks: <strong>Unavailable</strong>');
  expect(html).not.toContain('Passed');
});

it('keeps active CI polling errors visible rather than folding them into history', () => {
  const error = 'Could not refresh CI activity. Connection refused';
  const html = renderToStaticMarkup(<DevelopmentCiStatus ci={{ ...CI_PROJECTION, status: 'ACTIVE', activity: 'WATCHING_CI' }} active error={error} checks={checks} />);
  expect(html).toContain(`<span class="agt-development-ci__error">${error}</span>`);
  expect(html).toContain('CI Autofix is running');
  expect(html).not.toContain('agt-ci-history');
  expect(html).not.toContain('Previous CI Autofix run');
  expect(html).not.toContain('Latest known PR checks:');
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
