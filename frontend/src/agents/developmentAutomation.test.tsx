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
import { AutomationControl, AutomationSlider, CiProviderSelect } from './AutomationControl';
import { automationSteps, includesLevel, LOCAL_COMMITS_ONLY, developmentQuestion, mergeLabel, stoppingEndsDevelopment } from './developmentAutomation';
import { parseAgentTask, parseAgentTurn, parseDevelopmentAutomation, parseDevelopmentResult } from './contracts';
import { parseAgentEventSequence } from './events';
import { canComplete, canQueue, canSend, canSteer, createTaskCreationTracker, initialAgentTaskState, runtimeStatus } from './model';
import contracts from './fixtures/agent-contracts-v1.json';
import events from './fixtures/agent-events-v2.json';
import WorkflowPage from '../settings/WorkflowPage';
import { entryFor, matchesSearch, settingsLocationFromRoute, settingsRoute } from '../settings/routes';
import type { AgentQueuedInputDto, AgentTaskDto, AgentTaskDefaultsDto, AgentTurnDto, AutoMergeSummaryDto, DevelopmentAutomationDto, ModelProviderDto } from '../types';
import { NO_AGENT_CAPABILITIES } from './capabilities';
import { renderTaskBody } from './fixtures/taskBody';
import { CI_PROJECTION } from './fixtures/ciAutofix';

const defaults: DevelopmentAutomationDto = { level: 'CI_FIX', ciLaneId: null };
const report = { readyForDelivery: false, message: 'Which implementation should I use?' };
const task: AgentTaskDto = { ...parseAgentTask(contracts.task), interactive: true, mutationClaim: null, state: 'OPEN' };
const turn = { ...parseAgentTurn(contracts.turn), state: 'COMPLETED', developmentResult: report };
const state = { ...initialAgentTaskState(), task, turn, latestTurnId: turn.id, connection: 'open' as const };

it('treats the scale as cumulative', () => {
  expect(includesLevel('CI_FIX', 'COMMIT')).toBe(true);
  expect(includesLevel('CI_FIX', 'DRAFT_PR')).toBe(true);
  expect(includesLevel('CI_FIX', 'CI_FIX')).toBe(true);
  expect(includesLevel('CI_FIX', 'REVIEW_FIX')).toBe(false);
  expect(includesLevel('MERGE', 'REVIEW_FIX')).toBe(true);
  expect(includesLevel('COMMIT', 'DRAFT_PR')).toBe(false);
});

it('marks each step done, running, waiting or needing you from what the task reports', () => {
  const base: Parameters<typeof automationSteps>[0] = {
    level: 'MERGE', generated: null, delivered: false, failure: null, pullRequest: null, ci: null, reviewFix: null, autoMerge: null, now: 0 };
  const states = (input: Parameters<typeof automationSteps>[0]) => Object.fromEntries(Object.entries(automationSteps(input)).map(([step, status]) => [step, status.state]));
  expect(states(base)).toEqual({ COMMIT: 'pending', DRAFT_PR: 'pending', CI_FIX: 'pending', REVIEW_FIX: 'pending', MERGE: 'pending' });
  expect(states({ ...base, generated: 'AUTO_COMMIT' })).toMatchObject({ COMMIT: 'running', DRAFT_PR: 'pending' });
  expect(states({ ...base, generated: 'CREATE_DRAFT_PR' })).toMatchObject({ COMMIT: 'done', DRAFT_PR: 'running' });
  const fixing = { ...CI_PROJECTION, status: 'ACTIVE' as const, activity: 'FIXING', attemptsUsed: 2 };
  expect(states({ ...base, pullRequest: { draft: true }, ci: fixing })).toMatchObject({ COMMIT: 'done', DRAFT_PR: 'done', CI_FIX: 'running', REVIEW_FIX: 'pending' });
  expect(automationSteps({ ...base, pullRequest: { draft: true }, ci: fixing }).CI_FIX?.note).toBe('Fixing CI 2/3');
  const finished = { ...CI_PROJECTION, status: 'FINISHED' as const };
  expect(states({ ...base, ci: { ...finished, finishReason: 'GREEN' } }).CI_FIX).toBe('done');
  expect(states({ ...base, ci: { ...finished, finishReason: 'MAX_ATTEMPTS' } }).CI_FIX).toBe('attention');
  expect(states({ ...base, delivered: true, level: 'COMMIT' })).toEqual({ COMMIT: 'done' });
  const stopped = automationSteps({ ...base, level: 'CI_FIX', failure: 'Automation stopped. Create draft PR: refused' });
  expect(stopped.DRAFT_PR).toEqual({ state: 'attention', note: 'Automation stopped. Create draft PR: refused' });
  expect(stopped.COMMIT?.state).toBe('done');
  expect(automationSteps({ ...base, level: 'CI_FIX', failure: 'Automation stopped. Send another message to continue.' }).COMMIT?.state).toBe('attention');
  const review = { waiting: 0, fixed: 0, declined: 0, needsYou: 0 };
  expect(automationSteps({ ...base, reviewFix: { ...review, waiting: 2 } }).REVIEW_FIX).toEqual({ state: 'running', note: 'fixing 2 comments' });
  expect(automationSteps({ ...base, reviewFix: { ...review, fixed: 2, declined: 1 } }).REVIEW_FIX).toEqual({ state: 'done', note: '2 fixed · 1 declined' });
  expect(automationSteps({ ...base, reviewFix: { ...review, waiting: 1, needsYou: 1 } }).REVIEW_FIX).toEqual({ state: 'attention', note: '1 comment needs you' });
  const hour = 3_600_000;
  const ready: AutoMergeSummaryDto = { state: 'READY', mergeAt: new Date(hour + 5 * 60_000).toISOString(), message: null };
  expect(automationSteps({ ...base, autoMerge: ready, now: hour }).MERGE).toEqual({ state: 'running', note: 'merging in 5m' });
  expect(mergeLabel({ ...ready, mergeAt: new Date(3 * hour).toISOString() }, hour)).toBe('Merging in 2h');
  expect(mergeLabel({ ...ready, mergeAt: new Date(hour + 65 * 60_000).toISOString() }, hour)).toBe('Merging in 1h 5m');
  expect(mergeLabel(ready, 2 * hour)).toBe('Merging');
  expect(automationSteps({ ...base, autoMerge: { state: 'FAILED', mergeAt: null, message: 'Head branch was modified' } }).MERGE)
    .toEqual({ state: 'attention', note: 'Head branch was modified' });
  expect(automationSteps({ ...base, autoMerge: { state: 'MERGED', mergeAt: null, message: 'Merged' } }).MERGE?.state).toBe('done');
  expect(automationSteps({ ...base, autoMerge: { state: 'WAITING', mergeAt: null, message: 'Waiting for open review threads' } }).MERGE)
    .toEqual({ state: 'pending', note: 'Waiting for open review threads' });
  expect(parseAgentTask({ ...contracts.task, autoMerge: ready }).autoMerge).toEqual(ready);
  expect(() => parseAgentTask({ ...contracts.task, autoMerge: { ...ready, state: 'SOON' } })).toThrow();
  expect(parseAgentTask({ ...contracts.task, reviewFix: { ...review, fixed: 1 } }).reviewFix).toEqual({ ...review, fixed: 1 });
  expect(() => parseAgentTask({ ...contracts.task, reviewFix: { ...review, fixed: '1' } })).toThrow();
});

it('counts down a waiting merge in the status pill, whose square stops it', () => {
  const now = Date.parse('2026-10-21T10:00:00Z');
  const waiting: AgentTaskDto = { ...task, developmentContinuationUnfinished: false, defaults: { ...task.defaults, developmentAutomation: { level: 'MERGE', ciLaneId: null } },
    autoMerge: { state: 'READY', mergeAt: '2026-10-21T10:42:00Z', message: null } };
  const html = renderTaskBody({ ...state, task: waiting }, { now, automation: { level: 'MERGE', ciLaneId: null } });
  expect(html).toContain('Merging in 42m</span>');
  expect(html).toMatch(/<button[^>]*class="agt-auto-pill__stop"[^>]*aria-label="Stop auto-merge"[^>]*title="Stop auto-merge · the pull request stays open"/);
  expect(html).toContain('title="Auto-merge · merging in 42m"');
  const merging = renderTaskBody({ ...state, task: { ...waiting, autoMerge: { state: 'MERGING', mergeAt: null, message: null } } },
    { now, automation: { level: 'MERGE', ciLaneId: null } });
  expect(merging).toMatch(/<button[^>]*aria-label="Stop auto-merge"[^>]*disabled/);
});

it('shows a started task’s steps as status tiles with their meaning on hover', () => {
  const html = renderToStaticMarkup(<AutomationControl value={defaults} status={{
    COMMIT: { state: 'done', note: 'committed' }, DRAFT_PR: { state: 'done', note: 'PR opened' }, CI_FIX: { state: 'running', note: 'Fixing CI 2/3' } }} />);
  expect(html).toContain('<span class="agt-auto-chip__step is-done" title="Auto-Commit · committed">');
  expect(html).toContain('<span class="agt-auto-chip__step is-running" title="CI fix · Fixing CI 2/3">');
  expect(html).toContain('agt-auto-chip is-locked');
});

it('changes request identity when automation changes', () => {
  let id = 0;
  const tracker = createTaskCreationTracker(() => String(++id));
  const taskDefaults: AgentTaskDefaultsDto = { model: 'test', effort: null, permissionPolicy: 'AUTO', developmentAutomation: defaults };
  const first = tracker.requestFor('workspace', 'Change code', 'codex', taskDefaults, [], defaults);
  const local = tracker.requestFor('workspace', 'Change code', 'codex', { ...taskDefaults, developmentAutomation: LOCAL_COMMITS_ONLY }, [], LOCAL_COMMITS_ONLY);
  expect(local.taskId).not.toBe(first.taskId);
  expect(local.developmentAutomation?.level).toBe('COMMIT');
  expect(tracker.requestFor('workspace', 'Change code', 'codex', { ...taskDefaults, developmentAutomation: LOCAL_COMMITS_ONLY }, [], LOCAL_COMMITS_ONLY)).toBe(local);
});

it('validates and narrows automation and durable readiness at the bridge', () => {
  expect(parseDevelopmentAutomation({ ...defaults, backendSecret: 'never forward' })).toEqual(defaults);
  expect(parseDevelopmentAutomation(defaults).ciLaneId).toBeNull();
  expect(parseDevelopmentAutomation({ ...defaults, ciLaneId: 'claude' }).ciLaneId).toBe('claude');
  expect(parseDevelopmentAutomation({ level: 'MERGE', ciLaneId: null }).level).toBe('MERGE');
  expect(() => parseDevelopmentAutomation({ level: 'EVERYTHING', ciLaneId: null })).toThrow();
  expect(() => parseDevelopmentAutomation({ autoDraftPr: true, autoCiFix: true, ciLaneId: null })).toThrow();
  expect(parseDevelopmentResult(report)).toEqual(report);
  expect(() => parseDevelopmentResult({ ...report, readyForDelivery: 'false' })).toThrow();
  expect(() => parseDevelopmentResult({ ...report, message: false })).toThrow();
  expect(parseAgentTurn({ ...contracts.turn, developmentResult: report }).developmentResult).toEqual(report);
  expect(parseAgentTask({ ...contracts.task, interactive: false }).interactive).toBe(false);
  expect(parseAgentTask({ ...contracts.task, localReviewHead: 'a'.repeat(40) })).not.toHaveProperty('localReviewHead');
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
  it('keeps a pending decision after summary refinement and a local explanation', () => {
    const refined = { ...turn, id: 'summary-turn', developmentOrigin: { kind: 'REFINE_SUMMARY' as const, parentUserTurnId: turn.id },
      developmentResult: { readyForDelivery: true, message: 'Summary refreshed.' } };
    expect(developmentQuestion({ ...state, turn: refined, latestTurnId: refined.id, turns: { [turn.id]: turn, [refined.id]: refined } })).toBe(report.message);
    const explanation: AgentTurnDto = { ...turn, id: 'explanation-turn', developmentResult: null };
    expect(developmentQuestion({ ...state, turn: explanation, latestTurnId: explanation.id,
      turns: { [turn.id]: turn, [refined.id]: refined, [explanation.id]: explanation } })).toBe(report.message);
    const commitQuestion: AgentTurnDto = { ...turn, id: 'auto-commit-question', createdAt: '2099-01-01T00:00:00Z',
      developmentOrigin: { kind: 'AUTO_COMMIT', parentUserTurnId: turn.id }, developmentResult: { readyForDelivery: false, message: 'Which files should be committed?' } };
    expect(developmentQuestion({ ...state, turn: refined, latestTurnId: refined.id,
      turns: { [turn.id]: { ...turn, developmentResult: { readyForDelivery: true, message: null } }, [commitQuestion.id]: commitQuestion } })).toBe('Which files should be committed?');
  });
  it('waits for the answer in the composer without claiming publication', () => {
    const html = renderTaskBody(state);
    // The question itself stays in the transcript; the composer only says whose turn it is.
    expect(html).toContain('placeholder="Waiting for your response…"');
    expect(html).not.toContain(report.message);
    expect(html).not.toContain('Draft created');
  });
  it('holds earlier queued work while allowing the user to answer normally', () => {
    const input: AgentQueuedInputDto = { id: 'queued', taskId: task.id, position: 1, state: 'PAUSED', preview: 'Earlier request',
      settings: { ...contracts.turn.settings }, turnId: null, pauseReason: 'Waiting for a user answer', createdAt: '', updatedAt: '' };
    const html = renderTaskBody({ ...state, composer: 'Use the smaller change', queue: [input] }, { onResumeQueue: () => {} });
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
  const html = renderTaskBody(owned);
  expect(html).toContain('<span class="agt-auto-pill is-blue">');
  expect(html).toContain('Automation active');
  expect(html).toMatch(/<button[^>]*class="agt-auto-pill__stop"[^>]*aria-label="Stop automation"[^>]*title="Stop automation · your draft is kept"/);
  expect(html).not.toContain('Stop automation and continue…');
  expect(html).not.toContain('agt-stop-square');
  expect(html).toMatch(/<button[^>]*aria-label="Send message"[^>]*disabled/);
  expect(html).toContain('Keep this draft</textarea>');
  expect(html).not.toMatch(/<textarea[^>]*disabled/);
  expect(html).toMatch(/<button[^>]*aria-label="Mark it complete"[^>]*disabled/);
  expect(html).not.toContain('Waiting for your response');
});

it('explains stop using the matching active turn policy instead of mutable task defaults', () => {
  const accepted = { ...turn, settings: { ...contracts.turn.settings, developmentAutomation: defaults } };
  const local = LOCAL_COMMITS_ONLY;
  const active = { ...state, task: { ...task, defaults: { ...task.defaults, developmentAutomation: local } }, turn: accepted };
  expect(stoppingEndsDevelopment(active, turn.id)).toBe(true);
  expect(stoppingEndsDevelopment(active, 'another-turn')).toBe(false);
  expect(stoppingEndsDevelopment(active, null)).toBe(false);
  expect(stoppingEndsDevelopment({ ...active, task: { ...task, defaults: { ...task.defaults, developmentAutomation: defaults } },
    turn: { ...accepted, settings: { ...accepted.settings, developmentAutomation: local } } }, turn.id)).toBe(true);
  expect(stoppingEndsDevelopment({ ...active, task: { ...active.task, interactive: false } }, turn.id)).toBe(false);
  expect(stoppingEndsDevelopment({ ...state, task: { ...task, developmentContinuationUnfinished: true } }, null)).toBe(true);
});

it('shows the chosen step with its slider and keeps CI repair for CI steps', () => {
  const preferences = { taskRetentionDays: 7, recentRetentionDays: 14, prWarmCount: 30 as const, autoMergeDelayMinutes: 120, theme: 'SYSTEM' as const };
  const page = renderToStaticMarkup(<WorkflowPage value={defaults} providers={[]} preferences={preferences} error={null} onChange={() => {}} onPreferences={() => {}} />);
  expect(page).toContain('CI fix');
  expect(page).toContain('mark the draft ready');
  expect(page).toContain('aria-valuetext="CI fix"');
  expect(page).toContain('Same agent as development');
  expect(page).toContain('Each step includes the ones before it.');
  expect(page).not.toContain('Auto-merge delay');
  const local = renderToStaticMarkup(<WorkflowPage value={LOCAL_COMMITS_ONLY} providers={[]} preferences={preferences} error={null} onChange={() => {}} onPreferences={() => {}} />);
  expect(local).toContain('Nothing leaves your Mac.');
  expect(local).not.toContain('CI repair agent');
  const merging = renderToStaticMarkup(<WorkflowPage value={{ level: 'MERGE', ciLaneId: null }} providers={[]} preferences={preferences} error={null}
    onChange={() => {}} onPreferences={() => {}} />);
  expect(merging).toContain('Auto-merge delay');
  expect(merging).toContain('2 hours');
});

it('draws one composer icon per step that is on, and locks a started task', () => {
  const composer = renderToStaticMarkup(<AutomationControl value={defaults} defaultValue={LOCAL_COMMITS_ONLY} onChange={() => {}} />);
  expect(composer).toContain('aria-label="Automation: CI fix"');
  expect(composer.match(/agt-auto-chip__step/g)).toHaveLength(3);
  expect(composer).toContain('lucide-chevron-right');
  expect(composer).not.toContain('is-locked');
  const started = renderToStaticMarkup(<AutomationControl value={{ level: 'MERGE', ciLaneId: null }} />);
  expect(started).toContain('agt-auto-chip is-locked');
  expect(started.match(/agt-auto-chip__step/g)).toHaveLength(5);
  expect(started).not.toContain('lucide-chevron-right');
});

it('puts the slider value under its drawn stop and locks it without a handler', () => {
  const slider = renderToStaticMarkup(<AutomationSlider value="DRAFT_PR" onChange={() => {}} />);
  expect(slider).toContain('--stop:1;--stops:4;--end:0');
  expect(slider).toMatch(/<input type="range" min="0" max="4" step="1"[^>]*value="1"/);
  expect(slider.match(/agt-auto-slider__dot is-filled/g)).toHaveLength(1);
  expect(slider.match(/agt-auto-slider__dot"/g)).toHaveLength(3);
  const end = renderToStaticMarkup(<AutomationSlider value="MERGE" />);
  expect(end).toContain('--end:1');
  expect(end).toContain('agt-auto-slider is-locked');
  expect(end).toMatch(/<input[^>]*disabled=""/);
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
  expect(html).not.toContain('Select a supported agent');
  expect(html).not.toContain('does not support CI Autofix');
  for (const laneId of ['codex', 'claude']) {
    const selected = renderToStaticMarkup(<CiProviderSelect value={laneId} providers={providers} onChange={() => {}} />);
    expect(selected).toContain(`value="${laneId}" selected=""`);
    expect(selected).not.toContain('value="" selected=""');
  }
});

it('makes Workflow discoverable and deep-linkable', () => {
  expect(settingsRoute('workflow')).toBe('#/settings/agents/workflow');
  expect(settingsLocationFromRoute('#/settings/agents/workflow')?.section).toBe('workflow');
  expect(matchesSearch(entryFor('workflow'), 'Auto-Draft PR')).toBe(true);
  expect(matchesSearch(entryFor('workflow'), 'CI repair agent')).toBe(true);
  expect(matchesSearch(entryFor('workflow'), 'Auto-merge')).toBe(true);
});
