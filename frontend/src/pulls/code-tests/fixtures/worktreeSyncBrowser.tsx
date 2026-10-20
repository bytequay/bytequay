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

import { useState } from 'react';
import { flushSync } from 'react-dom';
import { createFixtureRoot } from '../../../fixtures/reactFixture';
import CodeTestsTab, { type CodeTestsBridge } from '../CodeTestsTab';
import type { AgentReviewDto } from '../../agent-review/contracts';
import type { PullRequestDetailDto } from '../../../types';
import type { IdeContext, IdeEvent } from '../../../ide/contracts';
import { reviewFixture, runningReviewFixture } from '../../agent-review/fixtures/review';
import { assert, settle } from '../../../fixtures/harness';
import { parseAgentTask } from '../../../agents/contracts';
import contracts from '../../../agents/fixtures/agent-contracts-v1.json';
import '../../../css/base.css';

export async function runStartup(taskHost: boolean): Promise<string> {
  const review = reviewFixture();
  const task = { ...parseAgentTask(contracts.task), interactive: true };
  let show: (active: boolean) => void = () => {};
  let event: (event: IdeEvent) => void = () => {};
  let checks = 0;
  let starts = 0;
  let available = false;
  let finish: (id: string) => void = () => {};
  let fail: (error: Error) => void = () => {};
  const oldIde = window.ide;
  window.ide = {
    resume: async () => { checks++; return available ? 'editor' : null; },
    open: async host => {
      assert(taskHost ? host.kind === 'task' && host.taskId === task.id : host.kind === 'review' && host.reviewId === review.id,
        'Confirmation must start only the selected working copy');
      starts++; return new Promise((resolve, reject) => { finish = resolve; fail = reject; });
    },
    present: async () => {}, update: async () => {}, reply: () => {}, close: async () => {},
    onRequest: () => () => {}, onEvent: listener => { event = listener; return () => {}; },
  };
  const unused = async (): Promise<never> => { throw new Error('Unexpected bridge call'); };
  const bridge: CodeTestsBridge = {
    listCodeFiles: unused, readCodeFile: unused, listCodeRuns: unused, stopCodeRun: unused, readCodeRunOutput: unused,
    openCodeTerminal: unused, writeReviewTerminal: unused, resizeReviewTerminal: unused, onReviewTerminalEvent: () => () => {},
    listAgentReviews: unused, getAgentReview: unused, subscribeAgentReview: unused, getAgentTask: async () => task,
    prepareReviewWorktree: unused,
  };
  const { host, root, dispose } = createFixtureRoot(); host.style.cssText = 'height:820px;width:1050px';
  function Harness() {
    const [active, setActive] = useState(false); show = setActive;
    return <CodeTestsTab workspaceId="workspace-1" review={taskHost ? null : review} task={taskHost ? task : null}
      onReviewChange={() => {}} active={active} ownReviewStream={false} bridge={bridge} />;
  }
  const click = (label: string) => {
    const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === label);
    assert(button !== undefined, `Missing ${label} button`); button.click();
  };
  try {
    flushSync(() => root.render(<Harness />)); await settle();
    assert(checks === 0 && starts === 0, 'A hidden tab must not connect or start tools');
    flushSync(() => show(true)); await settle();
    assert(Number(checks) === 1 && starts === 0 && host.textContent?.includes('Start IDE'), 'Opening a tab with no server must await confirmation');
    for (const cost of ['memory and CPU', 'indexing takes time', 'disk space', 'does not start an AI agent or use model tokens']) {
      assert(host.textContent?.includes(cost), `Disclose ${cost} before starting`);
    }
    flushSync(() => show(false)); await settle(); flushSync(() => show(true)); await settle();
    assert(starts === 0, 'Switching tabs must not authorize startup');
    click('Start IDE'); await settle();
    assert(Number(starts) === 1 && host.textContent?.includes('Starting code tools'), 'The confirm button must start tools once');
    fail(new Error('Server failed')); await settle();
    assert(host.querySelector('[role="alert"]')?.textContent?.includes('Server failed'), 'Failed starts must expose the error');
    click('Reconnect editor'); await settle();
    finish('editor'); await settle();
    flushSync(() => show(false)); await settle(); flushSync(() => show(true)); await settle();
    assert(Number(starts) === 2 && !host.textContent?.includes('Start IDE'), 'Tab switches must retain a successfully started editor');
    event({ id: 'editor', dirty: 0, busy: false, closed: true }); await settle();
    assert(Number(starts) === 2 && host.textContent?.includes('Start IDE'), 'An expired editor must require fresh confirmation');
    available = true;
    flushSync(() => show(false)); await settle(); flushSync(() => show(true)); await settle();
    assert(Number(starts) === 2 && !host.textContent?.includes('Start IDE'), 'Returning to the tab must reuse a server that became ready elsewhere');
    const previousChecks = checks;
    flushSync(() => root.render(null));
    flushSync(() => root.render(<Harness />));
    flushSync(() => show(true)); await settle();
    assert(Number(starts) === 2 && checks === previousChecks + 1 && !host.textContent?.includes('Start IDE'), 'A ready editor must resume without confirmation or startup');
    return 'Startup requires confirmation, discloses costs, retries failures, and reuses ready editors';
  } finally { dispose(); window.ide = oldIde; }
}

export async function runExploration(): Promise<string> {
  const prepared = reviewFixture({ runs: [], activeTurn: null, followUpPaused: true });
  const detail = { repo: prepared.repo, number: prepared.number } as PullRequestDetailDto;
  let attempts = 0;
  let opened = 0;
  let finish: (review: AgentReviewDto) => void = () => {};
  const oldIde = window.ide;
  window.ide = {
    resume: async () => null,
    open: async host => {
      assert(host.kind === 'review' && host.reviewId === prepared.id, 'Explore must open the prepared checkout');
      opened++; return 'explorer';
    },
    present: async () => {}, update: async () => {}, reply: () => {}, close: async () => {},
    onRequest: () => () => {}, onEvent: () => () => {},
  };
  const unused = async (): Promise<never> => { throw new Error('Unexpected bridge call'); };
  const bridge: CodeTestsBridge = {
    listCodeFiles: async () => ({ source: 'WORKING_COPY', revision: 'a'.repeat(40), sourceFingerprint: 'fingerprint', truncated: false,
      savedExperiment: false, files: [] }),
    readCodeFile: unused, listCodeRuns: unused, stopCodeRun: unused, readCodeRunOutput: unused,
    openCodeTerminal: unused, writeReviewTerminal: unused, resizeReviewTerminal: unused, onReviewTerminalEvent: () => () => {},
    listAgentReviews: async () => [], getAgentReview: unused, subscribeAgentReview: unused, getAgentTask: unused,
    prepareReviewWorktree: async (workspaceId, repo, number) => {
      assert(workspaceId === prepared.workspaceId && repo === prepared.repo && number === prepared.number, 'Preparation must target the selected watched PR');
      attempts++;
      if (attempts === 1) throw new Error('Cannot fetch PR branch');
      return new Promise(resolve => { finish = resolve; });
    },
  };
  const { host, root, dispose } = createFixtureRoot(); host.style.cssText = 'height:820px;width:1050px';
  function Harness() {
    const [review, setReview] = useState<AgentReviewDto | null>(null);
    return <CodeTestsTab detail={detail} workspaceId={prepared.workspaceId} review={review} onReviewChange={setReview}
      active ownReviewStream={false} bridge={bridge} />;
  }
  try {
    flushSync(() => root.render(<Harness />)); await settle();
    const button = host.querySelector<HTMLButtonElement>('.agr-action.is-primary');
    assert(button?.textContent === 'Create worktree to explore' && host.textContent?.includes('additional memory'), 'Explain creation and memory use before launching');
    assert(opened === 0 && attempts === 0, 'Opening the tab must not create a worktree or code server');
    button.click(); await settle();
    assert(host.querySelector('[role="alert"]')?.textContent === 'Cannot fetch PR branch' && !button.disabled, 'Failed creation must show a retryable error');
    button.click(); await settle();
    assert(button.disabled && String(button.textContent) === 'Creating worktree…', 'Pending preparation must disable duplicate clicks');
    button.click(); await settle();
    assert(Number(attempts) === 2 && Number(opened) === 0, 'Wait for preparation before opening the editor');
    finish(prepared); await settle(); await settle();
    assert(Number(opened) === 1 && host.querySelector('.ct-ide-surface') !== null, 'The prepared working copy must open without an agent run');
    return 'Exploration waits for explicit creation, retries failures, and opens the prepared checkout';
  } finally { dispose(); window.ide = oldIde; }
}

export async function run(): Promise<string> {
  const initial = reviewFixture({ worktreeSync: { state: 'BLOCKED', headSha: 'a'.repeat(40), targetHeadSha: 'b'.repeat(40), dirty: true, reason: 'Local edits' } });
  let update: (review: AgentReviewDto) => void = () => {};
  let show: (active: boolean) => void = () => {};
  let event: (event: IdeEvent) => void = () => {};
  let context: IdeContext | undefined;
  let unsaved = 0;
  const calls: string[] = [];
  const oldIde = window.ide;
  window.ide = {
    resume: async () => { calls.push('resume'); return 'editor'; },
    open: async host => { assert(host.kind === 'review' && host.reviewId === initial.id, 'The editor must open the selected review'); calls.push('open'); return 'editor'; },
    present: async () => {}, update: async (_id, value) => { context = value; }, reply: () => {},
    onRequest: () => () => {},
    onEvent: listener => { event = listener; return () => {}; },
    close: async () => { calls.push('close'); event({ id: 'editor', closed: true, dirty: 0, busy: false }); },
  };
  const unused = async (): Promise<never> => { throw new Error('Unexpected bridge call'); };
  const bridge: CodeTestsBridge = {
    listCodeFiles: async () => ({ source: 'WORKING_COPY', revision: initial.worktreeSync?.headSha ?? '', sourceFingerprint: 'fingerprint', truncated: false,
      savedExperiment: false, files: [] }),
    readCodeFile: unused, listCodeRuns: unused, stopCodeRun: unused,
    readCodeRunOutput: unused,
    openCodeTerminal: unused, writeReviewTerminal: unused, resizeReviewTerminal: unused, onReviewTerminalEvent: () => () => {},
    listAgentReviews: unused, prepareReviewWorktree: unused, getAgentReview: unused, subscribeAgentReview: unused, getAgentTask: unused,
  };
  const { host, root, dispose } = createFixtureRoot(); host.style.cssText = 'height:820px;width:1050px';
  function Harness() {
    const [review, setReview] = useState(() => ({ ...runningReviewFixture(), worktreeSync: initial.worktreeSync })); update = setReview;
    const [active, setActive] = useState(true); show = setActive;
    return <CodeTestsTab workspaceId="workspace-1" review={review} onReviewChange={setReview} active={active} ownReviewStream={false}
      bridge={bridge} onUnsavedChange={count => { unsaved = count; }} />;
  }
  try {
    flushSync(() => root.render(<Harness />)); await settle();
    assert(calls.includes('resume') && !calls.includes('open') && host.querySelector('.ct-ide-surface') !== null, 'Active reviews must reuse a ready IDE surface');
    flushSync(() => update(initial)); await settle();
    flushSync(() => show(false)); await settle();
    flushSync(() => show(true)); await settle();
    assert(context?.review?.activeTurn === null && context.review.runs[0]?.state === 'ACCEPTED', 'The editor must receive the completed review');
    assert(calls.filter(call => call === 'resume').length === 1 && !calls.includes('open') && !calls.includes('close') && host.querySelector('.ct-ide-surface') !== null,
      'Completing a review and reopening its code must retain the existing editor');
    event({ id: 'editor', dirty: 1, busy: false }); await settle();
    assert(unsaved === 1, 'Source drafts must keep the host unsaved-change guard');
    assert(host.querySelector('dialog[open]') === null, 'No working-copy action dialog should remain');
    return 'Completing a review retains the editor and its unsaved-change guard';
  } finally { dispose(); window.ide = oldIde; }
}
