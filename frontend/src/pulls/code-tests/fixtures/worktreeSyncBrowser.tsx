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
import { EditorView } from '@codemirror/view';
import CodeTestsTab, { type CodeTestsBridge } from '../CodeTestsTab';
import type { ReviewCodeFileDto } from '../contracts';
import type { AgentReviewDto } from '../../agent-review/contracts';
import { reviewFixture } from '../../agent-review/fixtures/review';
import { pr } from '../../fixtures/pullRequest';
import { assert, settle } from '../../../fixtures/harness';
import '../../../css/base.css';

const path = 'src/test/java/ExampleTest.java';
const oldHead = reviewFixture().runs[0].headSha;
const newHead = 'a'.repeat(40);
const deletedHead = 'b'.repeat(40);
const resetHead = 'e'.repeat(40);
const initialReview = reviewFixture({ worktreeSync: {
  state: 'CURRENT', headSha: oldHead, targetHeadSha: oldHead, dirty: false, reason: null,
} });
const diskFile = (content = 'class ExampleTest {}\n', contentHash = 'original-hash'): ReviewCodeFileDto => ({
  path, content, contentHash, editable: true, readOnlyReason: null, unavailableReason: null, prPatch: null, lineChanges: [],
});

export async function run(keepMounted = false, stopAt: 'blocked' | 'current' | null = null): Promise<string> {
  let head = oldHead;
  let staleListing = true;
  let deleted = false;
  let file = diskFile();
  let delayRead = false;
  let pendingRead: ((value: ReviewCodeFileDto) => void) | null = null;
  let rejectRead: ((failure: Error) => void) | null = null;
  const saves: { text: string; expectedHash: string }[] = [];
  const syncs: { reviewId: string; expectedHead: string }[] = [];
  let failSync = true;
  let finishSync: ((value: AgentReviewDto) => void) | null = null;
  let unsaved = 0;
  let setReview: (value: AgentReviewDto) => void = () => { throw new Error('Not mounted'); };
  const unused = async (): Promise<never> => { throw new Error('Unexpected bridge call'); };
  const bridge: CodeTestsBridge = {
    listCodeFiles: async () => ({ source: 'WORKING_COPY', revision: staleListing ? 'c'.repeat(40) : head, sourceFingerprint: head, codeGraph: 'OFF', truncated: false,
      savedExperiment: false, files: deleted ? [] : [{ path, changedInPr: true, addedInPr: false, onlyInWorkingCopy: false, uncommitted: false }] }),
    readCodeFile: async (_host, _path, expectedRevision) => {
      assert(expectedRevision !== undefined, 'Every editor read must identify its file-list revision');
      if (expectedRevision !== head) throw new Error('The working copy revision changed; refresh the file list before reading this file');
      if (delayRead) return new Promise<ReviewCodeFileDto>((resolve, reject) => { pendingRead = resolve; rejectRead = reject; });
      assert(!deleted, 'A deleted file with an unsaved draft must keep its readable editor');
      return file;
    },
    listCodeRuns: async () => [], readCodeOutline: async () => ({ status: 'UNAVAILABLE', symbols: [] }),
    saveCodeFile: async (_host, savedPath, text, expectedHash) => {
      assert(savedPath === path, 'Save must target the open draft');
      saves.push({ text, expectedHash });
      return { conflict: { currentContent: file.content, currentHash: file.contentHash ?? '' } };
    },
    syncReviewWorktree: async (reviewId, expectedHead) => {
      syncs.push({ reviewId, expectedHead });
      if (failSync) throw new Error('Remote fetch failed');
      return new Promise<AgentReviewDto>(resolve => { finishSync = resolve; });
    },
    searchCodeSymbols: unused, startCodeRun: unused, stopCodeRun: unused, readCodeRunOutput: unused,
    discardReviewCodeChanges: unused, openCodeTerminal: unused, writeReviewTerminal: unused, resizeReviewTerminal: unused,
    onReviewTerminalEvent: () => () => {}, listAgentReviews: unused, startAgentReview: unused,
    getAgentReview: unused, subscribeAgentReview: unused, getAgentTask: unused,
  };
  const host = document.createElement('div');
  host.style.cssText = 'height:820px;width:1050px';
  document.body.append(host);
  const root = createRoot(host);
  const Harness = () => {
    const [review, update] = useState(initialReview); setReview = update;
    return <CodeTestsTab workspaceId="workspace-1" review={review} onReviewChange={update} active ownReviewStream={false}
      bridge={bridge} onUnsavedChange={count => { unsaved = count; }} comments={{
        repo: review.repo, number: review.number ?? 1, canReview: true, viewer: null, pending: [], onPendingChange: () => {},
        threads: { activities: [], pr: pr({}), unavailable: false, onRefresh: async () => {}, onDetailUpdate: () => {} },
        onDetailChange: () => {}, localReview: { threads: [], onReply: async () => {}, onResolve: async () => {}, onRetry: async () => {} },
      }} />;
  };
  const button = (text: string) => {
    const value = [...host.querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent?.trim() === text);
    assert(value !== undefined, `Missing ${text}`); return value;
  };
  const staleButton = () => {
    const value = host.querySelector<HTMLButtonElement>('[aria-label="Local branch is stale — reset to remote"]');
    assert(value !== null, 'A stale working copy must have a toolbar reset icon'); return value;
  };
  try {
    flushSync(() => root.render(<Harness />)); await settle();
    button('ExampleTest.java').click(); await settle();
    assert(host.textContent?.includes('The working copy revision changed') === true && host.querySelector('.is-commentable') === null,
      'A checkout change between listing and reading must not expose content or comments under the old revision');
    staleListing = false;
    button('Retry').click(); await settle();
    assert(host.querySelector('.ct-code.is-commentable') !== null, 'Retry must obtain a fresh listing before making the matching file commentable');
    const editor = host.querySelector<HTMLElement>('.cm-editor');
    assert(editor !== null, 'Test source must open in Code & Tests');
    const view = EditorView.findFromDOM(editor);
    assert(view !== null, 'The editor must expose its draft');
    view.dispatch({ changes: { from: 0, insert: '// my unsaved test\n' } }); await settle();
    const draft = view.state.doc.toString();
    assert(unsaved === 1, 'The host must know a draft is unsaved');
    flushSync(() => setReview({ ...initialReview, observedHeadSha: newHead, worktreeSync: {
      state: 'BLOCKED', headSha: oldHead, targetHeadSha: newHead, dirty: true, reason: 'Local edits',
    } })); await settle();
    assert(!staleButton().disabled && host.querySelector('.ct-sync') === null,
      'An idle stale working copy must show an enabled toolbar reset icon without a warning row');
    assert(syncs.length === 0, 'Rendering a stale branch must not reset it');
    for (const claim of ['COMPLETION', 'LOCAL_RUN', 'RECOVERY'] as const) {
      flushSync(() => setReview({ ...initialReview, task: { ...initialReview.task, mutationClaim: claim }, worktreeSync: {
        state: 'BLOCKED', headSha: oldHead, targetHeadSha: newHead, dirty: true, reason: 'Local edits',
      } })); await settle();
      assert(staleButton().disabled, `${claim} must disable resetting the working copy`);
    }
    flushSync(() => setReview({ ...initialReview, state: 'CLOSED', worktreeSync: {
      state: 'BLOCKED', headSha: oldHead, targetHeadSha: newHead, dirty: true, reason: 'Local edits',
    } })); await settle();
    const closedReset = host.querySelector<HTMLButtonElement>('[aria-label="Local branch is stale — reset to remote"]');
    assert(closedReset === null || closedReset.disabled, 'An ended review must not allow resetting its working copy');
    flushSync(() => setReview({ ...initialReview, observedHeadSha: newHead, worktreeSync: {
      state: 'BLOCKED', headSha: oldHead, targetHeadSha: newHead, dirty: true, reason: 'Local edits',
    } })); await settle();
    if (keepMounted && stopAt === 'blocked') return 'Blocked sync preview';
    head = newHead; file = diskFile('class ExampleTest { int latest; }\n', 'new-hash'); delayRead = true;
    flushSync(() => setReview({ ...initialReview, observedHeadSha: newHead, worktreeSync: {
      state: 'CURRENT', headSha: newHead, targetHeadSha: newHead, dirty: false, reason: null,
    } })); await settle();
    assert(host.querySelector('[aria-label="Local branch is stale — reset to remote"]') === null && host.querySelector('.ct-sync') === null,
      'A current working copy must have no stale icon or warning even when review results cover an older head');
    assert(host.querySelector('.cm-editor') === editor && view.state.doc.toString() === draft,
      'Auto-sync must retain the mounted editor and draft while new disk content loads');
    assert(host.querySelector('.ct-code.is-commentable') === null, 'A pending reread must not expose comments on the prior file generation');
    button('Save').click(); await settle();
    assert(saves[0]?.expectedHash === 'original-hash' && saves[0]?.text === draft,
      'A save during refresh must retain the original hash guard');
    assert(host.textContent?.includes('changed on disk after you started editing') === true,
      'A conflicting save must show the existing conflict choices');
    assert(pendingRead !== null, 'A changed worktree head must trigger a file reread without needing a task version change');
    (pendingRead as (value: ReviewCodeFileDto) => void)(file); delayRead = false; await settle();
    button('Keep my draft').click(); await settle();
    if (keepMounted && stopAt === 'current') return 'Caught-up sync preview';
    head = deletedHead; delayRead = true; rejectRead = null;
    flushSync(() => setReview({ ...initialReview, observedHeadSha: deletedHead, worktreeSync: {
      state: 'CURRENT', headSha: deletedHead, targetHeadSha: deletedHead, dirty: false, reason: null,
    } })); await settle();
    assert(rejectRead !== null, 'The file listed before checkout deletion must have a pending reread');
    deleted = true;
    (rejectRead as (failure: Error) => void)(new Error('File no longer exists')); delayRead = false; await settle();
    assert(host.querySelector('.cm-editor') === editor && view.state.doc.toString() === draft && unsaved === 1,
      'A failed reread after checkout deletion must retain the mounted draft editor');
    assert(host.textContent?.includes('Could not refresh ExampleTest.java. Your unsaved draft is preserved.') === true && button('Save').disabled,
      'A failed reread must explain the preserved draft and block saving until refreshed');
    assert(host.querySelector('.ct-code.is-commentable') === null, 'A refused or failed reread must keep comments disabled on retained content');
    button('Retry').click(); await settle();
    assert(host.querySelector('.cm-editor') === editor && view.state.doc.toString() === draft && unsaved === 1,
      'Deleting the file remotely must retain its open draft and unsaved guard');
    assert(host.textContent?.includes('This file was removed from the working copy') === true && button('Save').disabled,
      'A deleted draft must remain readable with an explanation and disabled save');
    if (!keepMounted) {
      flushSync(() => setReview({ ...initialReview, observedHeadSha: resetHead, worktreeSync: {
        state: 'BLOCKED', headSha: deletedHead, targetHeadSha: resetHead, dirty: true, reason: 'Local edits',
      } })); await settle();
      staleButton().click(); await settle();
      assert(host.querySelector('dialog[open]')?.textContent?.includes('Reset working copy to remote?') === true,
        'Reset must ask for confirmation');
      const confirmation = host.querySelector('dialog[open]')?.textContent ?? '';
      assert(confirmation.includes('untracked') && confirmation.includes('commits') && confirmation.includes('draft'),
        'Confirmation must explain that drafts, local commits and untracked changes will be discarded');
      button('Cancel').click(); await settle();
      assert(syncs.length === 0 && view.state.doc.toString() === draft && unsaved === 1,
        'Cancelling must preserve the draft and avoid the reset endpoint');
      staleButton().click(); await settle(); button('Discard and reset').click(); await settle();
      assert(syncs[0]?.reviewId === initialReview.id && syncs[0]?.expectedHead === deletedHead,
        'Reset must identify the review and the local head shown to the user');
      assert(host.textContent?.includes('Remote fetch failed') === true && view.state.doc.toString() === draft && unsaved === 1,
        'A failed reset must show the error and preserve unsaved work');
      if (host.querySelector('dialog[open]') !== null) { button('Cancel').click(); await settle(); }
      failSync = false;
      staleButton().click(); await settle(); button('Discard and reset').click(); await settle();
      assert(finishSync !== null && staleButton().disabled && button('Resetting…').disabled,
        'A pending reset must disable repeat submission');
      deleted = false; head = resetHead; file = diskFile('class ExampleTest { int remote; }\n', 'reset-hash');
      const resetReview: AgentReviewDto = { ...initialReview, observedHeadSha: resetHead, worktreeSync: {
        state: 'CURRENT' as const, headSha: resetHead, targetHeadSha: resetHead, dirty: false, reason: null,
      } };
      (finishSync as (value: AgentReviewDto) => void)(resetReview); await settle(); await settle();
      assert(Number(unsaved) === 0 && host.querySelector('dialog[open]') === null,
        'A successful confirmed reset must discard drafts and close its dialog');
      if (host.querySelector('.cm-editor') === null) { button('ExampleTest.java').click(); await settle(); }
      const resetEditor = host.querySelector<HTMLElement>('.cm-editor');
      assert(resetEditor !== null && EditorView.findFromDOM(resetEditor)?.state.doc.toString() === file.content,
        'A successful reset must reread the remote file instead of retaining discarded draft content');
      assert(Number(syncs.length) === 2 && resetReview.runs[0].headSha === oldHead
        && host.querySelector('[aria-label="Local branch is stale — reset to remote"]') === null,
      'Reset must clear the stale indicator without launching or updating a review');
    }
    return 'Worktree sync preserves drafts until a confirmed reset and never starts a review';
  }
  finally { if (!keepMounted) { flushSync(() => root.unmount()); host.remove(); } }
}
