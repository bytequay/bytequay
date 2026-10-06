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
import type { IdeEvent } from '../../../ide/contracts';
import { reviewFixture } from '../../agent-review/fixtures/review';
import { assert, settle } from '../../../fixtures/harness';
import '../../../css/base.css';

export async function run(): Promise<string> {
  const initial = reviewFixture({ worktreeSync: { state: 'BLOCKED', headSha: 'a'.repeat(40), targetHeadSha: 'b'.repeat(40), dirty: true, reason: 'Local edits' } });
  let head = initial.worktreeSync?.headSha ?? '';
  let update: (review: AgentReviewDto) => void = () => {};
  let event: (event: IdeEvent) => void = () => {};
  let unsaved = 0;
  let fail = true;
  const calls: string[] = [];
  const oldIde = window.ide;
  window.ide = {
    open: async host => { assert(host.kind === 'review' && host.reviewId === initial.id, 'The editor must open the selected review'); calls.push('open'); return 'editor'; },
    present: async () => {}, update: async () => {}, reply: () => {}, onRequest: () => () => {},
    onEvent: listener => { event = listener; return () => {}; },
    discardSourceDrafts: async () => { calls.push('discard drafts'); },
    close: async () => { calls.push('close'); event({ id: 'editor', closed: true, dirty: 0, busy: false }); },
  };
  const unused = async (): Promise<never> => { throw new Error('Unexpected bridge call'); };
  const bridge: CodeTestsBridge = {
    listCodeFiles: async () => ({ source: 'WORKING_COPY', revision: head, sourceFingerprint: head, codeGraph: 'OFF', truncated: false,
      savedExperiment: false, files: [] }),
    readCodeFile: unused, searchCodeSymbols: unused, readCodeOutline: unused, listCodeRuns: unused, startCodeRun: unused, stopCodeRun: unused,
    readCodeRunOutput: unused, saveCodeFile: unused, discardReviewCodeChanges: unused,
    openCodeTerminal: unused, writeReviewTerminal: unused, resizeReviewTerminal: unused, onReviewTerminalEvent: () => () => {},
    listAgentReviews: unused, startAgentReview: unused, getAgentReview: unused, subscribeAgentReview: unused, getAgentTask: unused,
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
  const reset = () => {
    const found = host.querySelector<HTMLButtonElement>('[aria-label="Local branch is stale — reset to remote"]');
    assert(found !== null, 'Stale reviews retain their reset action'); return found;
  };
  try {
    flushSync(() => root.render(<Harness />)); await settle();
    assert(calls.includes('open') && host.querySelector('.ct-ide-surface') !== null, 'Active reviews must use the isolated IDE surface');
    event({ id: 'editor', dirty: 1, busy: false }); await settle();
    assert(unsaved === 1 && !reset().disabled, 'Source drafts keep the host guard and the confirmed reset action');
    for (const claim of ['COMPLETION', 'LOCAL_RUN', 'RECOVERY'] as const) {
      flushSync(() => update({ ...initial, task: { ...initial.task, mutationClaim: claim } })); await settle();
      assert(reset().disabled, `${claim} must disable reset`);
    }
    flushSync(() => update(initial)); await settle();
    reset().click(); await settle();
    assert(host.querySelector('dialog[open]')?.textContent?.includes('unsaved source drafts'), 'The dialog must explain source draft deletion');
    button('Cancel').click(); await settle();
    assert(!calls.includes('reset') && unsaved === 1, 'Cancelling preserves the draft');
    reset().click(); await settle(); button('Discard and reset').click(); await settle();
    assert(host.textContent?.includes('Remote fetch failed') && !calls.includes('discard drafts') && unsaved === 1, 'A failed backend reset must preserve the editor draft');
    fail = false; button('Discard and reset').click(); await settle(); await settle();
    assert(calls.filter(call => call === 'reset').length === 2 && calls.indexOf('discard drafts') > calls.lastIndexOf('reset'), 'Discard the source draft only after a successful backend reset');
    assert(calls.includes('close') && Number(unsaved) === 0 && host.querySelector('dialog[open]') === null, 'A successful reset rebuilds the editor with cleared source drafts');
    assert(host.querySelector('[aria-label="Local branch is stale — reset to remote"]') === null, 'The current branch loses its stale action');
    return 'Worktree sync preserves drafts until a confirmed reset and never starts a review';
  } finally { flushSync(() => root.unmount()); host.remove(); window.ide = oldIde; }
}
