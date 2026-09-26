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
import { AgentTaskBody } from '../AgentTaskScreen';
import { PullDetailHeader } from '../../pulls/PullsScreen';
import WorktreeChanges from '../../pulls/code-tests/WorktreeChanges';
import { initialAgentTaskState } from '../model';
import { previewTask, TASK_BODY_NOOPS } from './previewBridge';
import type { AgentTaskDto, Bridge } from '../../types';
import type { CodeHostRef, WorktreeChangesRequest } from '../../pulls/code-tests/contracts';
import { assert, settle } from '../../fixtures/harness';
import '../../css/base.css';

export async function run(keepMounted = false, showChanges = false, zoomChanges = false): Promise<string> {
  const task: AgentTaskDto = { ...previewTask('workspace-1', 'task-1'), interactive: true, checkoutRemovedAt: null, mutationClaim: null };
  const requests: { host: CodeHostRef; request: WorktreeChangesRequest }[] = [];
  let fileReads = 0;
  const bridge = {
    findTaskAgentReview: async () => null,
    listLocalReviewComments: async () => [],
    getAgentTask: async () => task,
    readWorktreeChanges: async (host, request) => {
      requests.push({ host, request });
      return { scope: request.scope, selectedCommit: request.commit ?? null, sourceFingerprint: 'working-copy', baseSha: 'base123', headSha: 'commit123', baseLabel: 'main', branch: 'feature', truncated: false,
        commits: [{ sha: 'commit123', parentSha: 'base123', message: 'Local implementation', author: 'developer', authoredAt: null }],
        staged: [], unstaged: ['notes.txt'], untracked: [],
        files: [{ filename: 'notes.txt', status: 'modified', additions: 1, deletions: 1, patch: '@@ -1 +1 @@\n-old\n+local' }] };
    },
    listCodeFiles: async () => {
      fileReads++;
      return { source: 'WORKING_COPY' as const, revision: 'commit123', truncated: false, codeGraph: 'OFF' as const,
        sourceFingerprint: 'working-copy', savedExperiment: false,
        files: [{ path: 'notes.txt', changedInPr: true, addedInPr: false, onlyInWorkingCopy: false, uncommitted: true }] };
    },
    readCodeFile: async () => ({ path: 'notes.txt', content: 'local\n', contentHash: 'hash', editable: true,
      readOnlyReason: null, unavailableReason: null, prPatch: null, lineChanges: [] }),
    listCodeRuns: async () => [],
    readCodeOutline: async () => ({ status: 'UNAVAILABLE' as const, symbols: [] }),
  } satisfies Pick<Bridge, 'findTaskAgentReview' | 'listLocalReviewComments' | 'getAgentTask' | 'readWorktreeChanges'
    | 'listCodeFiles' | 'readCodeFile' | 'listCodeRuns' | 'readCodeOutline'>;
  const originalBridge = window.bridge;
  Object.assign(window, { bridge });
  const host = document.createElement('div');
  host.style.cssText = 'width:1400px;height:850px';
  document.body.append(host);
  const root = createRoot(host);
  const Harness = () => {
    const [tab, setTab] = useState<'pr' | 'files' | 'changes' | 'review' | 'code' | 'agents' | 'discussions'>('review');
    return <AgentTaskBody {...TASK_BODY_NOOPS} models={[]} state={{ ...initialAgentTaskState(), task, connection: 'open' }}
      panelOpen panelTab={tab} onSelectPanelTab={setTab} repository="owner/repo" learning={<textarea aria-label="Summary draft" defaultValue="Keep this explanation" />} />;
  };
  const click = (label: string): void => {
    const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.trim() === label && !item.closest('[hidden]'));
    assert(button !== undefined, `Missing visible button: ${label}`);
    button.click();
  };
  try {
    flushSync(() => root.render(<Harness />));
    await settle();
    assert(host.querySelector('[aria-label="Task destinations"]')?.querySelectorAll('button').length === 2, 'Only PR and WorkBench are top-level until the conversation opens a utility tab');
    assert(host.textContent?.includes('Review changes') === true, 'Local tasks must offer a separate reviewer before creating a PR');
    const summary = host.querySelector('.agr-development-learning');
    click('Changes'); await settle();
    const picker = host.querySelector<HTMLSelectElement>('[aria-label="Change scope or commit"]');
    assert(picker !== null, 'Local Changes must load without PR details');
    assert(host.querySelector('[aria-label="Changes source"]')?.closest('.pull-detail__header') !== null
      && picker.closest('.pull-detail__header') !== null, 'Source and scope controls must share the header');
    assert(host.querySelectorAll('.pull-changes__toolbar').length === 1, 'Changes must have exactly one toolbar');
    assert(host.querySelector('.pull-file-tree') === null, 'A task side panel must start with the changed-file sidebar closed');
    picker.value = 'UNCOMMITTED'; picker.dispatchEvent(new Event('change', { bubbles: true })); await settle();
    assert(requests.at(-1)?.request.scope === 'UNCOMMITTED', 'Uncommitted selection must read the task working copy');
    picker.value = 'commit:commit123'; picker.dispatchEvent(new Event('change', { bubbles: true })); await settle();
    assert(requests.at(-1)?.request.commit === 'commit123', 'Clicking a local commit must read its changed files');
    click('Code & Tests'); await settle();
    assert(host.querySelector('.pull-changes__toolbar') === null, 'Hidden Changes must not leave a toolbar in the header');
    click('notes.txt'); await settle();
    const editor = host.querySelector<HTMLElement>('.cm-editor');
    assert(editor !== null, 'Code must open without PR or review prerequisites');
    const view = EditorView.findFromDOM(editor);
    assert(view !== null, 'The file must have a live editor');
    view.dispatch({ changes: { from: 0, insert: 'unsaved ' } }); await settle();
    click('PR'); await settle();
    assert(host.textContent?.includes('No pull request yet') === true, 'The PR destination must show its actual empty state');
    assert(getComputedStyle(editor).display !== 'none' && editor.closest('[hidden]') !== null, 'The code editor must be hidden through its kept ancestor');
    click('WorkBench'); await settle();
    assert(host.querySelector('.cm-editor') === editor && view.state.doc.toString() === 'unsaved local\n', 'Switching destinations must retain the editor and unsaved draft');
    assert(fileReads === 1, 'Switching destinations must not recreate the code host');
    click('Summary'); await settle();
    assert(host.querySelector('.agr-development-learning') === summary && summary !== null && getComputedStyle(summary).display !== 'none', 'Summary must remain mounted and become visible again');
    assert(host.querySelector('.cm-editor')?.closest('[hidden]') !== null, 'Returning to Summary must hide the retained editor');
    click('Changes'); await settle();
    assert(host.querySelector<HTMLSelectElement>('[aria-label="Change scope or commit"]')?.value === 'commit:commit123', 'Returning to Changes must retain the selected commit');
    assert(requests.every(item => item.host.kind === 'task' && item.host.taskId === task.id), 'Local exploration must stay on the development worktree');
    const divider = host.querySelector<HTMLElement>('[aria-label="Resize side panel"]');
    assert(divider !== null, 'Task WorkBench must retain its resizable panel');
    const start = divider.getBoundingClientRect().left;
    divider.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, button: 0, clientX: start }));
    window.dispatchEvent(new MouseEvent('mousemove', { clientX: start + 500 }));
    window.dispatchEvent(new MouseEvent('mouseup')); await settle();
    const pane = host.querySelector<HTMLElement>('.agt-panel');
    const metadata = host.querySelector<HTMLDetailsElement>('.wb-changes__details');
    assert(pane !== null && metadata !== null, 'The narrow panel must retain revision metadata');
    metadata.open = true; await settle();
    const bounds = metadata.querySelector('.wb-changes__details-body')?.getBoundingClientRect();
    assert(bounds !== undefined && bounds.width > 100 && bounds.left >= pane.getBoundingClientRect().left
      && bounds.right <= pane.getBoundingClientRect().right, 'Revision details must fit the narrow panel');
    metadata.open = false;
    if (keepMounted && !showChanges) { click('Code & Tests'); await settle(); }
    if (keepMounted && zoomChanges) {
      const ZoomPreview = () => {
        const [toolbar, setToolbar] = useState<HTMLElement | null>(null);
        return <section className="pull-detail is-zoomed zoom-card is-full">
          <PullDetailHeader pr={null} detail={null} tab="changes" workingCopyAvailable changesSource="working"
            onChangesToolbar={setToolbar} onTab={() => {}} onRefresh={async () => {}} refreshing={false}
            onReviewSubmitted={() => {}} pendingComments={[]} onPendingCommentsChange={() => {}}
            zoom={{ full: true, toggle: () => {}, close: () => {} }} />
          <div className="pull-detail__body pull-detail__changes"><WorktreeChanges host={{ kind: 'task', taskId: task.id, workspaceId: task.workspaceId }}
            toolbarTarget={toolbar} defaultTreeOpen selection={{ commitSha: 'commit123', token: 1 }} /></div>
        </section>;
      };
      flushSync(() => root.render(<ZoomPreview />)); await settle();
    }
    return 'Local WorkBench preserves commit selection, summary and unsaved code before a PR exists';
  }
  finally {
    if (!keepMounted) { flushSync(() => root.unmount()); host.remove(); Object.assign(window, { bridge: originalBridge }); }
  }
}
