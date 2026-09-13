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
import { describe, expect, it } from 'vitest';
import { AutomationControl, CiProviderSelect } from './AutomationControl';
import { automationForSubmission, automationSummary, DEFAULT_DEVELOPMENT_AUTOMATION, developmentQuestion, stoppingEndsDevelopment } from './developmentAutomation';
import { parseAgentTask, parseAgentTurn, parseDevelopmentAutomation, parseDevelopmentResult } from './contracts';
import { parseAgentEventSequence } from './events';
import { canComplete, canQueue, canSend, canSteer, createDraftSubmissionTracker, createTaskCreationTracker, initialAgentTaskState, runtimeStatus } from './model';
import contracts from './fixtures/agent-contracts-v1.json';
import events from './fixtures/agent-events-v2.json';
import WorkflowPage from '../settings/WorkflowPage';
import { entryFor, matchesSearch, settingsLocationFromRoute, settingsRoute } from '../settings/routes';
import type { AgentQueuedInputDto, AgentTaskDto, AgentTaskDefaultsDto, ModelProviderDto } from '../types';
import { NO_AGENT_CAPABILITIES } from './capabilities';
import { AgentTaskBody } from './AgentTaskScreen';

const defaults = DEFAULT_DEVELOPMENT_AUTOMATION;
const report = { readyForDelivery: false, message: 'Which implementation should I use?', stopAfter: 'COMMITS' as const };
const task: AgentTaskDto = { ...parseAgentTask(contracts.task), interactive: true, mutationClaim: null, state: 'OPEN' };
const turn = { ...parseAgentTurn(contracts.turn), state: 'COMPLETED', developmentResult: report };
const state = { ...initialAgentTaskState(), task, turn, latestTurnId: turn.id, connection: 'open' as const };

it('keeps Local only confined to a submission without changing saved defaults', () => {
  const local = automationForSubmission(defaults, true);
  expect(local).toEqual({ autoCommit: false, autoDraftPr: false, autoCiFix: false, ciLaneId: null });
  expect(defaults.autoCommit).toBe(true);
  expect(automationForSubmission(defaults, false)).toBe(defaults);
  expect(automationSummary(local)).toBe('Local only');
});

it('changes request identity when automation or its first-turn override changes', () => {
  let id = 0;
  const tracker = createTaskCreationTracker(() => String(++id));
  const taskDefaults: AgentTaskDefaultsDto = { model: 'test', effort: null, permissionPolicy: 'AUTO', developmentAutomation: defaults };
  const first = tracker.requestFor('workspace', 'Change code', 'codex', taskDefaults);
  const local = tracker.requestFor('workspace', 'Change code', 'codex', taskDefaults, [], automationForSubmission(defaults, true));
  expect(local.taskId).not.toBe(first.taskId);
  expect(local.defaults.developmentAutomation).toEqual(defaults);
  expect(local.developmentAutomation?.autoCommit).toBe(false);
  expect(tracker.requestFor('workspace', 'Change code', 'codex', taskDefaults, [], local.developmentAutomation)).toBe(local);
  const submissions = createDraftSubmissionTracker(() => String(++id));
  const key = submissions.keyFor('send', 'task', 'task', 'Change code', [], taskDefaults);
  expect(submissions.keyFor('send', 'task', 'task', 'Change code', [], { ...taskDefaults, developmentAutomation: local.developmentAutomation })).not.toBe(key);
});

it('validates and narrows automation and durable readiness at the bridge', () => {
  expect(parseDevelopmentAutomation({ ...defaults, backendSecret: 'never forward' })).toEqual(defaults);
  expect(parseDevelopmentAutomation(defaults).ciLaneId).toBeNull();
  expect(parseDevelopmentAutomation({ ...defaults, ciLaneId: 'claude' }).ciLaneId).toBe('claude');
  expect(() => parseDevelopmentAutomation({ ...defaults, autoCommit: 'true' })).toThrow();
  expect(parseDevelopmentResult(report)).toEqual(report);
  expect(() => parseDevelopmentResult({ ...report, readyForDelivery: 'false' })).toThrow();
  expect(() => parseDevelopmentResult({ ...report, stopAfter: 'READY' })).toThrow();
  expect(parseAgentTurn({ ...contracts.turn, developmentResult: report }).developmentResult).toEqual(report);
  expect(parseAgentTask({ ...contracts.task, interactive: false }).interactive).toBe(false);
  expect(parseAgentTask({ ...contracts.task, developmentContinuationUnfinished: true }).developmentContinuationUnfinished).toBe(true);
  expect(() => parseAgentTask({ ...contracts.task, developmentContinuationUnfinished: 'false' })).toThrow();
});

it('decodes root readiness events but rejects child, invalid, and unbound reports', () => {
  const event = { ...events.events[0], type: 'development_result', payload: report };
  expect(parseAgentEventSequence([event])[0].payload).toEqual(report);
  expect(() => parseAgentEventSequence([{ ...event, turnId: null }])).toThrow('root turn');
  expect(() => parseAgentEventSequence([{ ...event, source: { ...event.source, parentProviderThreadId: 'parent' } }])).toThrow('root turn');
  expect(() => parseAgentEventSequence([{ ...event, payload: { ...report, readyForDelivery: 'false' } }])).toThrow();
});

describe('waiting for the user', () => {
  it('uses the durable latest turn when history is unloaded', () => {
    expect(state.events).toEqual([]);
    expect(developmentQuestion(state)).toBe(report.message);
  });
  it('does not keep an old question while an answer runs or after fresh readiness', () => {
    expect(developmentQuestion({ ...state, latestTurnId: 'next-turn' })).toBeNull();
    expect(developmentQuestion({ ...state, turn: { ...turn, state: 'RUNNING' } })).toBeNull();
    expect(developmentQuestion({ ...state, turn: { ...turn, developmentResult: { ...report, readyForDelivery: true } } })).toBeNull();
    expect(developmentQuestion({ ...state, task: { ...task, mutationClaim: { kind: 'TURN', ownerId: turn.id, acquiredAt: '' } } })).toBeNull();
  });
  it('renders the question before the composer without claiming publication', () => {
    const html = renderToStaticMarkup(<AgentTaskBody state={state} models={[]} onBack={() => {}} onSend={() => {}} onCancel={() => {}}
      onComplete={() => {}} onDecide={() => {}} onCompose={() => {}} onSelectModel={() => {}} onStopServer={() => {}} onMarkFailed={() => {}} />);
    expect(html).toContain('Waiting for your answer');
    expect(html).toContain(report.message);
    expect(html.indexOf(report.message)).toBeLessThan(html.indexOf('agt-compose__input'));
    expect(html).not.toContain('Draft created');
  });
  it('holds earlier queued work while allowing the user to answer normally', () => {
    const input: AgentQueuedInputDto = { id: 'queued', taskId: task.id, position: 1, state: 'PAUSED', preview: 'Earlier request',
      settings: { ...contracts.turn.settings }, turnId: null, pauseReason: 'Waiting for a user answer', createdAt: '', updatedAt: '' };
    const html = renderToStaticMarkup(<AgentTaskBody state={{ ...state, composer: 'Use the smaller change', queue: [input] }} models={[]}
      onBack={() => {}} onSend={() => {}} onCancel={() => {}} onComplete={() => {}} onDecide={() => {}} onCompose={() => {}}
      onSelectModel={() => {}} onStopServer={() => {}} onMarkFailed={() => {}} onResumeQueue={() => {}} />);
    expect(html).toMatch(/<button[^>]*disabled[^>]*>Resume queue<\/button>/);
    expect(html).toContain('Answer the agent’s question before resuming earlier queued messages');
    expect(html).toMatch(/<button[^>]*aria-label="Send message"(?![^>]*disabled)[^>]*>/);
  });
});

it('blocks competing actions during a continuation without inventing a running provider', () => {
  const owned = { ...state, task: { ...task, developmentContinuationUnfinished: true }, composer: 'Keep this draft' };
  expect(runtimeStatus(owned)).toBe('idle');
  expect(canSend(owned)).toBe(false);
  expect(canComplete(owned)).toBe(false);
  expect(developmentQuestion(owned)).toBeNull();
  const executing = { ...owned, turn: { ...turn, state: 'RUNNING' }, capabilities: { ...NO_AGENT_CAPABILITIES, steering: true } };
  expect(canSteer(executing)).toBe(false);
  expect(canQueue(executing)).toBe(false);
  const html = renderToStaticMarkup(<AgentTaskBody state={owned} models={[]} onBack={() => {}} onSend={() => {}} onCancel={() => {}}
    onComplete={() => {}} onDecide={() => {}} onCompose={() => {}} onSelectModel={() => {}} onStopServer={() => {}} onMarkFailed={() => {}} />);
  expect(html).toContain('Automation running');
  expect(html).toContain('aria-label="Stop automation"');
  expect(html).toContain('Keep this draft</textarea>');
  expect(html).not.toMatch(/<textarea[^>]*disabled/);
  expect(html).toMatch(/<button[^>]*aria-label="Mark it complete"[^>]*disabled/);
  expect(html).not.toContain('Waiting for your answer');
});

it('explains stop using the matching active turn policy instead of mutable task defaults', () => {
  const accepted = { ...turn, settings: { ...contracts.turn.settings, developmentAutomation: defaults } };
  const local = automationForSubmission(defaults, true);
  const active = { ...state, task: { ...task, defaults: { ...task.defaults, developmentAutomation: local } }, turn: accepted };
  expect(stoppingEndsDevelopment(active, turn.id)).toBe(true);
  expect(stoppingEndsDevelopment(active, 'another-turn')).toBe(false);
  expect(stoppingEndsDevelopment(active, null)).toBe(false);
  expect(stoppingEndsDevelopment({ ...active, task: { ...task, defaults: { ...task.defaults, developmentAutomation: defaults } },
    turn: { ...accepted, settings: { ...accepted.settings, developmentAutomation: local } } }, turn.id)).toBe(false);
  expect(stoppingEndsDevelopment({ ...state, task: { ...task, developmentContinuationUnfinished: true } }, null)).toBe(true);
});

it('shows all switches and inherited CI repair agent selection in settings and the composer', () => {
  const page = renderToStaticMarkup(<WorkflowPage value={defaults} providers={[]} busy={false} error={null} onChange={() => {}} />);
  expect(page.match(/aria-checked="true"/g)).toHaveLength(3);
  expect(page).toContain('Same agent as development');
  expect(page).toContain('Use the development agent or choose another agent to repair failing CI checks.');
  const composer = renderToStaticMarkup(<AutomationControl value={defaults} providers={[]} localOnly={false}
    futureTurns onChange={() => {}} onLocalOnlyChange={() => {}} />);
  expect(composer).toContain('For future turns');
  expect(composer).toContain('Local only for this turn');
  expect(composer).toContain('mark the draft ready');
  expect(composer).toContain('Same agent as development');
});

it('offers inherited CI repair by default and supported Codex or Claude overrides', () => {
  const provider = (id: string, supported: boolean): ModelProviderDto => ({ id, label: id, readiness: 'READY', version: '1', diagnostic: '', signInCommand: null,
    models: [], selectedModel: '', selectedEffort: '', capabilities: { ...NO_AGENT_CAPABILITIES, permissionPolicies: supported ? ['CI_AUTOFIX'] : ['AUTO'] } });
  const providers = [provider('codex', true), provider('claude', true), provider('unsupported', false)];
  const html = renderToStaticMarkup(<CiProviderSelect value={null} providers={providers} onChange={() => {}} />);
  expect(html).toContain('value="codex"');
  expect(html).toContain('value="claude"');
  expect(html).not.toContain('value="unsupported"');
  expect(html).toContain('value="" selected="">Same agent as development');
  expect(html).toContain('CI repairs use the task’s development agent.');
  expect(html).not.toContain('Select a supported agent');
  expect(html).not.toContain('does not support CI Autofix');
  for (const laneId of ['codex', 'claude']) {
    const selected = renderToStaticMarkup(<CiProviderSelect value={laneId} providers={providers} onChange={() => {}} />);
    expect(selected).toContain(`value="${laneId}" selected=""`);
    expect(selected).not.toContain('value="" selected=""');
    expect(selected).not.toContain('CI repairs use the task’s development agent.');
  }
});

it('makes Workflow discoverable and deep-linkable', () => {
  expect(settingsRoute('workflow')).toBe('#/settings/agents/workflow');
  expect(settingsLocationFromRoute('#/settings/agents/workflow')?.section).toBe('workflow');
  expect(matchesSearch(entryFor('workflow'), 'Auto draft PR')).toBe(true);
  expect(matchesSearch(entryFor('workflow'), 'CI repair agent')).toBe(true);
});
