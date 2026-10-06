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
import { createRoot } from 'react-dom/client';
import CodeTestsTab, { type CodeTestsBridge } from '../CodeTestsTab';
import type { AgentReviewDto } from '../../agent-review/contracts';
import type { PullRequestDetailDto } from '../../../types';
import type { IdeContext, IdeEvent, IdeRequest } from '../../../ide/contracts';
import { reviewFixture } from '../../agent-review/fixtures/review';
import { assert, settle } from '../../../fixtures/harness';
import '../../../css/base.css';

export async function runExploration(): Promise<string> {
  const prepared = reviewFixture({ runs: [], activeTurn: null, followUpPaused: true });
  const detail = { repo: prepared.repo, number: prepared.number } as PullRequestDetailDto;
  let attempts = 0;
  let opened = 0;
  let finish: (review: AgentReviewDto) => void = () => {};
  const oldIde = window.ide;
  window.ide = {
    open: async host => {
      assert(host.kind === 'review' && host.reviewId === prepared.id, 'Explore must open the prepared checkout');
      opened++; return 'explorer';
    },
    present: async () => {}, update: async () => {}, reply: () => {}, close: async () => {}, discardSourceDrafts: async () => {},
    onRequest: () => () => {}, onEvent: () => () => {},
  };
  const unused = async (): Promise<never> => { throw new Error('Unexpected bridge call'); };
  const bridge: CodeTestsBridge = {
    listCodeFiles: async () => ({ source: 'WORKING_COPY', revision: 'a'.repeat(40), sourceFingerprint: 'fingerprint', truncated: false,
      savedExperiment: false, files: [] }),
    readCodeFile: unused, listCodeRuns: unused, stopCodeRun: unused, readCodeRunOutput: unused, discardReviewCodeChanges: unused,
    openCodeTerminal: unused, writeReviewTerminal: unused, resizeReviewTerminal: unused, onReviewTerminalEvent: () => () => {},
    listAgentReviews: async () => [], getAgentReview: unused, subscribeAgentReview: unused, getAgentTask: unused, syncReviewWorktree: unused,
    prepareReviewWorktree: async (workspaceId, repo, number) => {
      assert(workspaceId === prepared.workspaceId && repo === prepared.repo && number === prepared.number, 'Preparation must target the selected watched PR');
      attempts++;
      if (attempts === 1) throw new Error('Cannot fetch PR branch');
      return new Promise(resolve => { finish = resolve; });
    },
  };
  const host = document.createElement('div'); host.style.cssText = 'height:820px;width:1050px'; document.body.append(host);
  const root = createRoot(host);
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
    assert(button.disabled && button.textContent === 'Creating worktree…', 'Pending preparation must disable duplicate clicks');
    button.click(); await settle();
    assert(Number(attempts) === 2 && Number(opened) === 0, 'Wait for preparation before opening the editor');
    finish(prepared); await settle(); await settle();
    assert(Number(opened) === 1 && host.querySelector('.ct-ide-surface') !== null, 'The prepared working copy must open without an agent run');
    return 'Exploration waits for explicit creation, retries failures, and opens the prepared checkout';
  } finally { flushSync(() => root.unmount()); host.remove(); window.ide = oldIde; }
}

export async function run(): Promise<string> {
  const initial = reviewFixture({ worktreeSync: { state: 'BLOCKED', headSha: 'a'.repeat(40), targetHeadSha: 'b'.repeat(40), dirty: true, reason: 'Local edits' } });
  let head = initial.worktreeSync?.headSha ?? '';
  let update: (review: AgentReviewDto) => void = () => {};
  let event: (event: IdeEvent) => void = () => {};
  let request: (request: IdeRequest) => void = () => {};
  let context: IdeContext | undefined;
  let unsaved = 0;
  let fail = true;
  const calls: string[] = [];
  const oldIde = window.ide;
  window.ide = {
    open: async host => { assert(host.kind === 'review' && host.reviewId === initial.id, 'The editor must open the selected review'); calls.push('open'); return 'editor'; },
    present: async () => {}, update: async (_id, value) => { context = value; }, reply: () => {},
    onRequest: listener => { request = listener; return () => {}; },
    onEvent: listener => { event = listener; return () => {}; },
    discardSourceDrafts: async () => { calls.push('discard drafts'); },
    close: async () => { calls.push('close'); event({ id: 'editor', closed: true, dirty: 0, busy: false }); },
  };
  const unused = async (): Promise<never> => { throw new Error('Unexpected bridge call'); };
  const bridge: CodeTestsBridge = {
    listCodeFiles: async () => ({ source: 'WORKING_COPY', revision: head, sourceFingerprint: head, truncated: false,
      savedExperiment: false, files: [] }),
    readCodeFile: unused, listCodeRuns: unused, stopCodeRun: unused,
    readCodeRunOutput: unused, discardReviewCodeChanges: unused,
    openCodeTerminal: unused, writeReviewTerminal: unused, resizeReviewTerminal: unused, onReviewTerminalEvent: () => () => {},
    listAgentReviews: unused, prepareReviewWorktree: unused, getAgentReview: unused, subscribeAgentReview: unused, getAgentTask: unused,
    syncReviewWorktree: async (id, expected) => {
      assert(id === initial.id && expected === head, 'Reset must use the review and head shown at confirmation');
      calls.push('reset');
      if (fail) throw new Error('Remote fetch failed');
      head = 'b'.repeat(40);
      return { ...initial, worktreeSync: { state: 'CURRENT', headSha: head, targetHeadSha: head, dirty: false, reason: null } };
    },
  };
  const host = document.createElement('div'); host.style.cssText = 'height:820px;width:1050px'; document.body.append(host);
  const root = createRoot(host);
  function Harness() {
    const [review, setReview] = useState(initial); update = setReview;
    return <CodeTestsTab workspaceId="workspace-1" review={review} onReviewChange={setReview} active ownReviewStream={false}
      bridge={bridge} onUnsavedChange={count => { unsaved = count; }} />;
  }
  const button = (text: string) => {
    const found = [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim() === text);
    assert(found !== undefined, `Missing ${text}`); return found;
  };
  const reset = () => request({ id: 'editor', requestId: 'reset', method: 'worktree.reset', args: [] });
  try {
    flushSync(() => root.render(<Harness />)); await settle();
    assert(calls.includes('open') && host.querySelector('.ct-ide-surface') !== null, 'Active reviews must use the isolated IDE surface');
    event({ id: 'editor', dirty: 1, busy: false }); await settle();
    assert(unsaved === 1 && context?.workingCopy?.blocked === null && context.workingCopy.staleLabel !== null, 'Source drafts keep the host guard and the confirmed reset action');
    for (const claim of ['COMPLETION', 'LOCAL_RUN', 'RECOVERY'] as const) {
      flushSync(() => update({ ...initial, task: { ...initial.task, mutationClaim: claim } })); await settle();
      assert(context?.workingCopy?.blocked != null, `${claim} must disable reset`);
      reset(); await settle();
      assert(host.querySelector('dialog[open]') === null, `${claim} cannot open a destructive confirmation`);
    }
    flushSync(() => update(initial)); await settle();
    reset(); await settle();
    assert(host.querySelector('dialog[open]')?.textContent?.includes('unsaved source drafts'), 'The dialog must explain source draft deletion');
    button('Cancel').click(); await settle();
    assert(!calls.includes('reset') && unsaved === 1, 'Cancelling preserves the draft');
    reset(); await settle(); button('Discard and reset').click(); await settle();
    assert(host.textContent?.includes('Remote fetch failed') && !calls.includes('discard drafts') && unsaved === 1, 'A failed backend reset must preserve the editor draft');
    fail = false; button('Discard and reset').click(); await settle(); await settle();
    assert(calls.filter(call => call === 'reset').length === 2 && calls.indexOf('discard drafts') > calls.lastIndexOf('reset'), 'Discard the source draft only after a successful backend reset');
    assert(calls.includes('close') && Number(unsaved) === 0 && host.querySelector('dialog[open]') === null, 'A successful reset rebuilds the editor with cleared source drafts');
    assert(context?.workingCopy?.staleLabel === null && host.querySelector('.ct-toolbar') === null, 'The current branch loses its stale action and no redundant toolbar remains');
    return 'Worktree sync preserves drafts until a confirmed reset and never starts a review';
  } finally { flushSync(() => root.unmount()); host.remove(); window.ide = oldIde; }
}
