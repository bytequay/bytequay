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
import { prepareAgentInput } from '../preparedInput';
import { createRoot } from 'react-dom/client';
import { installDesktopFixture } from '../../ide/fixtures/desktop';
import AgentTaskScreen, { AgentTaskBody } from '../AgentTaskScreen';
import DevelopmentLearningPanel from '../DevelopmentLearningPanel';
import type { TaskReviewOptions } from '../../pulls/agent-review/TaskReviewSummary';
import { useLocalReviewThreads } from '../../pulls/local-review/useLocalReviewThreads';
import { carriedPanelTab, PullDetailHeader } from '../../pulls/PullsScreen';
import { reviewFixture } from '../../pulls/agent-review/fixtures/review';
import { pr } from '../../pulls/fixtures/pullRequest';
import { workspaceAgentTaskRoute, workspaceReviewTaskRoute } from '../../workspaces/model';
import { loadHandled } from '../../home/attention';
import WorktreeChanges from '../../pulls/code-tests/WorktreeChanges';
import { initialAgentTaskState } from '../model';
import { createPreviewBridge, previewTask, TASK_BODY_NOOPS } from './previewBridge';
import type { TaskDiscussionDto } from '../discussions/contracts';
import type { AgentTaskDto, Bridge } from '../../types';
import type { CodeHostRef, WorktreeChangesDto, WorktreeChangesRequest } from '../../pulls/code-tests/contracts';
import { assert, settle } from '../../fixtures/harness';
import '../../css/base.css';

export async function run(keepMounted = false, showChanges = false, zoomChanges = false): Promise<string> {
  const desktop = installDesktopFixture();
  const task: AgentTaskDto = { ...previewTask('workspace-1', 'task-1'), interactive: true, checkoutRemovedAt: null, mutationClaim: null };
  const requests: { host: CodeHostRef; request: WorktreeChangesRequest }[] = [];
  const publishedPr = pr({ id: 7, repo: 'owner/repo', number: 7, title: 'Published implementation', author: 'developer',
    htmlUrl: 'https://github.com/owner/repo/pull/7', updatedAt: '2026-09-26T00:00:00Z',
    draft: true, ciStatus: null, mergeable: null, mergeableState: null,
  });
  const publishedFiles = [{ filename: 'published.txt', status: 'added', additions: 1, deletions: 0, patch: '@@ -0,0 +1 @@\n+Published implementation' }];
  const selectedCommits: string[] = [];
  let linkPullRequest = () => {};
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
      return { source: 'WORKING_COPY' as const, revision: 'commit123', truncated: false,
        sourceFingerprint: 'working-copy', savedExperiment: false,
        files: [{ path: 'notes.txt', changedInPr: true, addedInPr: false, onlyInWorkingCopy: false, uncommitted: true }] };
    },
    readCodeFile: async () => ({ path: 'notes.txt', content: 'local\n', contentHash: 'hash', editable: true,
      readOnlyReason: null, unavailableReason: null, prPatch: null, lineChanges: [] }),
    listCodeRuns: async () => [],
  } satisfies Pick<Bridge, 'findTaskAgentReview' | 'listLocalReviewComments' | 'getAgentTask' | 'readWorktreeChanges'
    | 'listCodeFiles' | 'readCodeFile' | 'listCodeRuns'>;
  const originalBridge = window.bridge;
  Object.assign(window, { bridge });
  const host = document.createElement('div');
  host.style.cssText = 'width:1400px;height:850px';
  document.body.append(host);
  const root = createRoot(host);
  // The Summary's own reviewer arrives with the options the side panel passes for an interactive task.
  const Summary = ({ review }: { review: TaskReviewOptions }) => {
    const localReview = useLocalReviewThreads(null);
    return <DevelopmentLearningPanel task={task} repository="owner/repo" refreshKey={0} refining={false} disabledReason={null}
      localReview={localReview} bridge={window.bridge} review={review} />;
  };
  const Harness = () => {
    const [tab, setTab] = useState<'pr' | 'files' | 'changes' | 'review' | 'code' | 'agents' | 'discussions'>('pr');
    const [linked, setLinked] = useState(false);
    linkPullRequest = () => setLinked(true);
    return <AgentTaskBody {...TASK_BODY_NOOPS} models={[]} state={{ ...initialAgentTaskState(), task, connection: 'open' }}
      panelOpen panelTab={tab} onSelectPanelTab={setTab} repository="owner/repo" pullRequests={linked ? [publishedPr] : []}
      pullRequestChanges={{ files: publishedFiles, allFiles: publishedFiles, commits: [], loading: false, error: null,
        selectCommit: sha => selectedCommits.push(sha) }}
      learning={(onSelectCommit, review) => <><textarea aria-label="Summary draft" defaultValue="Keep this explanation" />
        <button type="button" onClick={() => onSelectCommit('published-sha')}>View published commit</button>
        {review !== undefined && <Summary review={review} />}</>} />;
  };
  const click = (label: string): void => {
    const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.trim() === label && !item.closest('[hidden]'));
    assert(button !== undefined, `Missing visible button: ${label}`);
    button.click();
  };
  try {
    flushSync(() => root.render(<Harness />));
    await settle();
    assert(host.querySelector('[aria-label="Task destinations"]')?.querySelectorAll('button').length === 3, 'PR, WorkBench and the current Developer stay at the top before a reviewer exists');
    click('WorkBench'); await settle();
    const firstView = host.querySelector('[aria-label="WorkBench views"] button');
    assert(firstView?.textContent?.trim() === 'Changes' && firstView.getAttribute('aria-pressed') === 'true',
      'Opening WorkBench without a PR must select its leftmost Changes tab');
    click('Summary'); await settle();
    assert(host.textContent?.includes('Review changes') === true, 'Local tasks must offer a separate reviewer before creating a PR');
    const summary = host.querySelector('.agr-development-learning');
    click('Changes'); await settle();
    const picker = host.querySelector<HTMLSelectElement>('[aria-label="Change scope or commit"]');
    assert(picker !== null, 'Local Changes must load without PR details');
    assert(host.querySelector('[aria-label="Changes source"]') === null && picker.closest('.pull-detail__header') !== null,
      'Local Changes must keep its scope control in the header without a source selector');
    assert(host.querySelectorAll('.pull-changes__toolbar').length === 1, 'Changes must have exactly one toolbar');
    assert(host.querySelector('.pull-file-tree') === null, 'A task side panel must start with the changed-file sidebar closed');
    picker.value = 'UNCOMMITTED'; picker.dispatchEvent(new Event('change', { bubbles: true })); await settle();
    assert(requests.at(-1)?.request.scope === 'UNCOMMITTED', 'Uncommitted selection must read the task working copy');
    picker.value = 'commit:commit123'; picker.dispatchEvent(new Event('change', { bubbles: true })); await settle();
    assert(requests.at(-1)?.request.commit === 'commit123', 'Clicking a local commit must read its changed files');
    click('Tears of time'); await settle();
    assert(host.querySelector('.pull-changes__toolbar') === null, 'Hidden Changes must not leave a toolbar in the header');
    const editor = host.querySelector<HTMLElement>('.ct-ide-surface');
    assert(editor !== null && desktop.opens === 1, 'Code must open one native editor without PR or review prerequisites');
    desktop.report(1); await settle();
    click('PR'); await settle();
    assert(host.textContent?.includes('No pull request yet') === true, 'The PR destination must show its actual empty state');
    assert(getComputedStyle(editor).display !== 'none' && editor.closest('[hidden]') !== null, 'The code editor must be hidden through its kept ancestor');
    click('WorkBench'); await settle();
    assert(host.querySelector('.ct-ide-surface') === editor && desktop.opens === 1, 'Switching destinations must retain the editor and unsaved draft');
    assert(desktop.opens === 1, 'Switching destinations must not reopen the native editor');
    click('Summary'); await settle();
    assert(host.querySelector('.agr-development-learning') === summary && summary !== null && getComputedStyle(summary).display !== 'none', 'Summary must remain mounted and become visible again');
    assert(host.querySelector('.ct-ide-surface')?.closest('[hidden]') !== null, 'Returning to Summary must hide the retained editor');
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
    if (!keepMounted) {
      const localReads = requests.length;
      flushSync(() => linkPullRequest()); await settle();
      assert(host.querySelector('.agt-panel .pull-diff-file')?.textContent?.includes('Published implementation') === true
        && host.querySelector('[aria-label="Change scope or commit"]') === null && requests.length === localReads,
      'A PR arriving while local Changes is open must immediately show only the published diff');
      assert(host.querySelector('[aria-label="Changes source"]') === null, 'Published Changes must not offer a source selector');
      click('Summary'); await settle();
      click('View published commit'); await settle();
      assert(selectedCommits.join() === 'published-sha' && host.querySelector('.agt-panel .pull-diff-file')?.textContent?.includes('Published implementation'),
        'Summary commit links must select the published commit without reopening local Changes');
      click('Tears of time'); await settle();
      assert(host.querySelector('.ct-ide-surface') === editor && desktop.opens === 1,
        'Linking a PR and navigating its changes must preserve the task working-copy draft');
    }
    if (keepMounted && !showChanges) { click('Tears of time'); await settle(); }
    if (keepMounted && zoomChanges) {
      const ZoomPreview = () => {
        const [toolbar, setToolbar] = useState<HTMLElement | null>(null);
        return <section className="pull-detail is-zoomed zoom-card is-full">
          <PullDetailHeader pr={null} detail={null} tab="changes"
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
    if (!keepMounted) { flushSync(() => root.unmount()); host.remove(); desktop.dispose(); Object.assign(window, { bridge: originalBridge }); }
  }
}

/** The whole task screen, because opening and closing a utility tab is the wrapper's state, not the body's. */
export async function utilityTabs(): Promise<string> {
  const bridge = createPreviewBridge('workspace-1');
  const answer = bridge.events.find(event => event.type === 'assistant_message' && event.payload.phase === 'FINAL');
  assert(answer?.turnId != null, 'The fixture must hold a finished answer');
  const at = '2026-08-20T10:02:00Z';
  const discussion: TaskDiscussionDto = { id: 'd1', taskId: answer.taskId, turnId: answer.turnId, quote: 'authentication regression',
    occurrence: 1, resolved: false, pending: false, providerLane: 'codex', version: 1, createdAt: at, updatedAt: at, comment: null,
    messages: [{ id: 'm1', author: 'USER', body: 'Which one?', createdAt: at, error: null },
      { id: 'm2', author: 'AGENT', body: 'The token refresh.', createdAt: at, error: null }] };
  // Only an interactive task has discussions, and its Summary asks the page bridge for a review.
  const getTask = bridge.getAgentTask;
  bridge.getAgentTask = async (taskId, workspaceId) => ({ ...await getTask(taskId, workspaceId), interactive: true });
  const getTurn = bridge.getAgentTurn;
  bridge.getAgentTurn = async turnId => ({ ...await getTurn(turnId), id: turnId });
  bridge.listTaskDiscussions = async () => [discussion];
  const originalBridge = window.bridge;
  Object.defineProperty(window, 'bridge', { configurable: true, value: { ...bridge, findTaskAgentReview: async (): Promise<null> => null,
    readWorktreeChanges: async (): Promise<WorktreeChangesDto> => ({ scope: 'ALL', selectedCommit: null, sourceFingerprint: 'working-copy',
      baseSha: 'base', headSha: 'head', baseLabel: 'main', branch: 'feature', truncated: false,
      commits: [], staged: [], unstaged: [], untracked: [], files: [] }),
  } });
  const host = document.createElement('div');
  host.style.cssText = 'width:1400px;height:850px';
  document.body.append(host);
  const root = createRoot(host);
  const footer = async (): Promise<HTMLButtonElement> => {
    for (let frame = 0; frame < 60; frame++) {
      const row = host.querySelector<HTMLButtonElement>('.agt-disc-row');
      if (row !== null) return row;
      await settle();
    }
    throw new Error('The answer never showed its discussion footer');
  };
  const panel = () => host.querySelector('.agt-panel')?.getAttribute('aria-label');
  const tab = () => host.querySelector('[aria-label="Task destinations"]')?.textContent?.includes('Discussions') === true;
  try {
    flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={answer.taskId} bridge={bridge} onBack={() => {}} />));
    await footer();
    prepareAgentInput(answer.taskId, 'Selected test failure: expected 7, actual 6'); await settle();
    prepareAgentInput(answer.taskId, 'Please investigate this failure.'); await settle();
    assert([...host.querySelectorAll<HTMLTextAreaElement>('textarea')].some(input => input.value ===
      'Selected test failure: expected 7, actual 6\n\nPlease investigate this failure.'),
    'Code tools must append to the existing composer without replacing its draft');
    assert(!bridge.commands.some(command => /startAgentTurn|queueAgentInput|steerAgentTurn/.test(command.name)),
      'Preparing selected evidence must never submit an agent command');
    const attentionKey = `task:${answer.taskId}`;
    assert(host.querySelector('.attention-visit-status') === null, 'An opened task must not show a Viewed label');
    assert(loadHandled()[attentionKey] === undefined, 'The task must stay in attention while its detail remains open');
    host.querySelector<HTMLButtonElement>('[aria-label="Show or hide the side panel"]')?.click(); await settle();
    assert(panel() === 'Changes' && !tab(), 'The panel must default to Changes without showing an unopened Discussions tab');
    (await footer()).click(); await settle();
    assert(panel() === 'Discussions' && tab(), 'A discussion footer must open its tab');
    assert(host.querySelector('[aria-label="WorkBench views"]') === null, 'A top-level utility tab must not keep showing the WorkBench views');
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim() === 'WorkBench')?.click(); await settle();
    assert(panel() === 'Changes' && tab(), 'An opened tab must stay while another view shows');
    (await footer()).click(); await settle();
    host.querySelector<HTMLButtonElement>('[aria-label="Close Discussions"]')?.click(); await settle();
    assert(panel() === 'Changes' && !tab(), 'Closing the showing tab must remove it and return to the view it covered');
    (await footer()).click(); await settle();
    assert(panel() === 'Discussions' && tab(), 'The footer must reopen a closed tab');
    assert(loadHandled()[attentionKey] === undefined, 'Switching task utility tabs must not consume its notification');
    flushSync(() => root.render(null)); await settle();
    assert(loadHandled()[attentionKey]?.label === 'Viewed', 'Leaving a task detail must acknowledge the displayed revision as Viewed');
    return 'Discussions opens from the conversation and closes back to the covered view';
  }
  finally {
    flushSync(() => root.unmount()); host.remove(); Object.defineProperty(window, 'bridge', { configurable: true, value: originalBridge });
  }
}

/** Developer and Reviewer change the conversation only: the screen a switch opens keeps the panel tab the user was on. */
export async function agentSwitch(published = false, utility = false): Promise<string> {
  const bridge = createPreviewBridge('workspace-1');
  const taskId = bridge.events[0].taskId;
  const review = reviewFixture({ id: 'review-1', workspaceId: 'workspace-1', sourceTaskId: published ? null : taskId });
  const publishedPr = pr({ repo: review.repo, number: review.number });
  if (published) bridge.listAgentTaskPullRequests = async () => [publishedPr];
  const getTask = bridge.getAgentTask;
  bridge.getAgentTask = async (id, workspaceId) => ({ ...await getTask(id, workspaceId), interactive: true });
  const originalBridge = window.bridge;
  Object.defineProperty(window, 'bridge', { configurable: true, value: { ...bridge,
    findTaskAgentReview: async () => published ? null : review,
    findAgentReview: async () => review,
    fetchPullRequestDetailByRef: async () => { throw new Error('Details unavailable'); },
    refreshPullRequestDetailByRef: async () => { throw new Error('Details unavailable'); },
    fetchPrDiffFiles: async (): ReturnType<Bridge['fetchPrDiffFiles']> => [],
    fetchPrDiffCommits: async (): ReturnType<Bridge['fetchPrDiffCommits']> => [],
    readWorktreeChanges: async (): Promise<WorktreeChangesDto> => ({ scope: 'ALL', selectedCommit: null, sourceFingerprint: 'working-copy',
      baseSha: 'base', headSha: 'head', baseLabel: 'main', branch: 'feature', truncated: false,
      commits: [], staged: [], unstaged: [], untracked: [], files: [] }),
  } });
  const host = document.createElement('div');
  host.style.cssText = 'width:1400px;height:850px';
  document.body.append(host);
  const root = createRoot(host);
  const go = async (route: string): Promise<void> => {
    const changed = new Promise(resolve => window.addEventListener('hashchange', resolve, { once: true }));
    window.location.hash = route;
    await changed;
  };
  const agent = (name: string) => [...host.querySelectorAll<HTMLButtonElement>('[aria-label="Agents"] button')].find(button => button.textContent?.trim().startsWith(name));
  const panel = async (label: string): Promise<boolean> => {
    for (let frame = 0; frame < 60 && host.querySelector('.agt-panel')?.getAttribute('aria-label') !== label; frame++) await settle();
    return host.querySelector('.agt-panel')?.getAttribute('aria-label') === label;
  };
  const developerRoute = workspaceAgentTaskRoute('workspace-1', taskId);
  const reviewerRoute = workspaceReviewTaskRoute('workspace-1', review.id);
  try {
    // The reviewer's header, on the pull request tab.
    flushSync(() => root.render(<PullDetailHeader pr={null} detail={null} tab={utility ? null : 'overview'} review={review} developerTask={{ workspaceId: review.workspaceId, taskId }} agent="reviewer" onTab={() => {}}
      onRefresh={async () => {}} onReviewSubmitted={() => {}} refreshing={false} pendingComments={[]} onPendingCommentsChange={() => {}}
      onNavigate={(route) => { void go(route); }} />));
    await settle();
    assert(agent('Reviewer')?.getAttribute('aria-current') === 'true' && agent('Developer')?.getAttribute('aria-current') === null, 'The reviewer screen must mark Reviewer as the current agent');
    agent('Developer')?.click(); await settle();
    assert(window.location.hash === developerRoute, 'Developer must open the development task');
    flushSync(() => root.render(<AgentTaskScreen key="developer" workspaceId="workspace-1" taskId={taskId} bridge={bridge} onBack={() => {}} />));
    assert(await panel(utility ? 'Changes' : published ? `Pull request #${publishedPr.number}` : 'Pull request'), 'The development task must open its panel on the tab the reviewer was on, not on its default Changes');
    assert(agent('Developer')?.getAttribute('aria-current') === 'true', 'The development task must mark Developer as the current agent');
    for (let frame = 0; frame < 30 && agent('Reviewer') === undefined; frame++) await settle();
    assert(agent('Reviewer') !== undefined, 'The reviewer must remain reachable after opening Developer, including PR-only reviews');
    const currentDeveloper = agent('Developer');
    currentDeveloper?.click(); await settle();
    assert(agent('Developer') === currentDeveloper && agent('Reviewer') !== undefined,
      'Clicking the current conversation must preserve both tabs');
    const top = currentDeveloper?.getBoundingClientRect().top;
    const scroll = host.querySelector<HTMLElement>('.agt-panel__scroll, .pull-detail__body');
    scroll?.scrollTo(0, scroll.scrollHeight); await settle();
    assert(agent('Developer')?.getBoundingClientRect().top === top, 'Conversation tabs must stay fixed above scrolling content');
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim().startsWith('WorkBench'))?.click();
    assert(await panel('Changes'), 'WorkBench must open on Changes');
    agent('Reviewer')?.click(); await settle();
    assert(window.location.hash === reviewerRoute && carriedPanelTab() === (published ? 'files' : 'changes'), 'Reviewer must open the review with the tab the developer was on');
    await go('#/workspaces/workspace-1/tasks');
    await go(reviewerRoute);
    assert(carriedPanelTab() === null, 'Opening the review another way must get its own default tab');
    return 'An agent switch keeps the panel tab';
  }
  finally {
    flushSync(() => root.unmount()); host.remove(); Object.defineProperty(window, 'bridge', { configurable: true, value: originalBridge });
    window.location.hash = '';
  }
}
