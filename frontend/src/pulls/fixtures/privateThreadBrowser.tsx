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
import { flushSync } from 'react-dom';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { timelineActivity, timelineComment, prDetail } from './pullRequest';
import '../../css/base.css';
import '../../css/markdown-editor.css';
import '../../css/pulls.css';
import '../../css/agent-review.css';
import { TaskPullRequestView } from '../../agents/TaskPullRequestPanel';
import { privateThreadSource } from '../PrivateThread';
import { useTaskDiscussions, type DiscussionBridgePort } from '../../agents/discussions/useTaskDiscussions';
import { SUMMARY_REQUEST, type AskCommentDiscussionRequest, type TaskDiscussionDto } from '../../agents/discussions/contracts';
import type { Bridge } from '../../types';
import type { FixableComment } from '../../agents/taskFix';
import { element as fixtureElement, assert, settle } from '../../fixtures/harness';
import { remotePullRequest } from '../PullsScreen';

const AT = '2026-09-27T00:00:00Z';
const ROOT = 61;
const detail = prDetail({
  id: 7, repo: 'owner/repo', number: 7, title: 'Resolve owners', htmlUrl: 'https://github.com/owner/repo/pull/7', headRef: 'alice/resolve-owners-through-the-shared-helper-and-its-callers',
  mergeable: null, mergeableState: null, author: 'alice', createdAt: AT, updatedAt: AT,
  body: 'Resolves owners.', deletions: 1,
  recentActivity: [
    timelineActivity({ eventType: 'reviewed', timestamp: AT, githubId: 50 }, { state: 'COMMENTED' }),
    timelineActivity({ timestamp: AT, githubId: 70 }, {
      path: 'src/Owner.java', line: 3, originalLine: 3, side: 'RIGHT',
      comments: [timelineComment({ githubId: ROOT, timestamp: AT, body: 'Rename `helper` to `resolveOwner`.', reviewId: 50 })] }),
    timelineActivity({ actor: 'alice', eventType: 'commented', timestamp: AT, body: 'Thanks, renaming it now.', githubId: 80 }),
  ],
});
const pr = remotePullRequest(detail, 'alice');
const SUMMARY = JSON.stringify({ ask: 'Rename helper to resolveOwner.', valid: 'yes', why: 'The name hides what it does.',
  do: 'yes', plan: 'Rename it and its two callers.', size: 'trivial' });

let refresh: () => Promise<void> = async () => {};
const fixes: FixableComment[] = [];

function Harness({ bridge }: { bridge: DiscussionBridgePort }) {
  const discussions = useTaskDiscussions({ taskId: 'task-1', workspaceId: 'workspace-1' }, { bridge });
  refresh = discussions.refresh;
  return (
    <div className="pull-detail__body" style={{ height: 880 }}>
      <TaskPullRequestView pr={pr} workspaceId="workspace-1" detail={detail} loading={false} error={null}
        onRefresh={async () => {}} onDetailUpdate={() => {}} onDetailChange={() => {}}
        privateThreads={{ ...privateThreadSource(discussions, pr), fix: async comment => { fixes.push(comment); } }} />
    </div>
  );
}

function button(host: ParentNode, text: string): HTMLButtonElement {
  const found = [...host.querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent?.trim() === text);
  assert(found !== undefined, `Missing ${text} button`);
  return found;
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const asked: AskCommentDiscussionRequest[] = [];
  const posted: Parameters<Bridge['replyToReviewComment']>[] = [];
  const stored = new Map<string, TaskDiscussionDto>();
  let finishDraft: (text: string) => void = () => {};
  let draftCalls = 0;
  const unused = async (): Promise<never> => { throw new Error('This fixture only asks about comments'); };
  const bridge: DiscussionBridgePort = {
    listTaskDiscussions: async () => [...stored.values()],
    openTaskDiscussion: unused,
    askTaskDiscussion: unused,
    retryTaskDiscussion: unused,
    resolveTaskDiscussion: unused,
    askTaskDiscussionComment: async request => {
      asked.push(request);
      const thread: TaskDiscussionDto = { id: 'thread-1', taskId: request.taskId, turnId: 'review-comment', quote: request.key, occurrence: 1,
        resolved: false, pending: true, providerLane: 'codex', version: 1, createdAt: AT, updatedAt: AT,
        comment: { repo: request.repo, number: request.number, key: request.key }, replyDraft: null, replyDraftError: null,
        messages: [{ id: 'hidden', author: 'USER', body: request.question === '' ? SUMMARY_REQUEST : request.question, createdAt: AT, error: null }] };
      stored.set(thread.id, thread);
      return thread;
    },
    draftTaskDiscussionReply: () => { draftCalls++; return new Promise<string>(resolve => { finishDraft = resolve; }); },
  };
  const originalBridge = window.bridge;
  const pageBridge: Pick<Bridge, 'getUserProfile' | 'getWorkspaceHostingViewer' | 'fetchWorkspaceHostingAttention' | 'replyToReviewComment' | 'listAgentReviews'> = {
    listAgentReviews: async () => [],
    getWorkspaceHostingViewer: async () => (await window.bridge.getUserProfile?.().catch(() => null))?.login ?? null,
    fetchWorkspaceHostingAttention: async () => ({ login: (await window.bridge.getUserProfile?.().catch(() => null))?.login ?? null, pullRequests: await window.bridge.fetchAttentionPrs?.().catch(() => []) ?? [] }),
    getUserProfile: async () => ({ login: 'alice', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0, following: 0,
      bio: null, location: null, company: null, email: null }),
    replyToReviewComment: async (...request) => { posted.push(request); },
  };
  Object.defineProperty(window, 'bridge', { configurable: true, value: pageBridge });
  const { host, root, dispose } = createFixtureRoot();
  const step = async (name: string, body: () => Promise<void>): Promise<void> => {
    try {
      await body();
      results.push({ name });
    }
    catch (failure) {
      results.push({ name, error: failure instanceof Error ? failure.message : String(failure) });
    }
  };
  const chip = (): HTMLButtonElement | null => host.querySelector<HTMLButtonElement>('.pr-private-chip');
  const draftReply = async (): Promise<void> => {
    chip()?.click();
    await settle();
    button(host, 'Draft with agent').click();
    await settle();
  };
  try {
    flushSync(() => root.render(<Harness bridge={bridge} />));
    await settle();

    await step('Only other people’s comments offer Ask agent', async () => {
      const asks = [...host.querySelectorAll('.pr-ask-agent')].filter(ask => ask.textContent === 'Ask agent').length;
      assert(asks === 1 && host.textContent?.includes('Thanks, renaming it now.') === true,
        `Only bob's review comment may offer Ask agent, not alice's own comment; found ${asks}`);
    });

    await step('Fix with agent hands bob’s review comment to the task once, with its lines and link', async () => {
      assert([...host.querySelectorAll('button')].filter(fix => fix.textContent === 'Fix with agent').length === 1,
        'Only bob’s review comment may offer Fix with agent');
      button(host, 'Fix with agent').click();
      await settle();
      assert(JSON.stringify(fixes) === JSON.stringify([{ author: 'bob', body: 'Rename `helper` to `resolveOwner`.',
        url: 'https://github.com/owner/repo/pull/7', location: 'src/Owner.java:R3' }]), `Fix sent ${JSON.stringify(fixes)}`);
      assert(button(host, 'Sent to agent').disabled, 'A sent fix cannot be sent again from the same button');
    });

    await step('Ask agent brings the thread forward and Cmd+Enter asks for the summary', async () => {
      button(host, 'Ask agent').click();
      await settle();
      assert(host.querySelector('.pr-private-forward') !== null && host.textContent?.includes('on bob’s comment · only the agent sees this') === true,
        'Ask agent must bring the private thread forward in place');
      const field = fixtureElement<HTMLTextAreaElement>('[aria-label="Private question to the agent"]', host, 'The private composer did not open');
      field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true }));
      await settle();
      const request = asked[0];
      assert(asked.length === 1 && request.key === `review_comment:${ROOT}` && request.question === ''
        && request.repo === 'owner/repo' && request.number === 7, `Cmd+Enter asked ${JSON.stringify(asked)}`);
      assert(host.textContent?.includes('Agent is answering…') === true && !host.textContent.includes(SUMMARY_REQUEST),
        'The hidden summary request must show as answering, never as a message');
      const waiting = host.querySelector<HTMLElement>('.agr-composer footer .agr-composer__waiting');
      const reply = button(host, 'Reply');
      assert(waiting !== null && waiting.textContent === '' && waiting.querySelector('.pr-task-spinner') !== null
        && waiting.getAttribute('aria-label') === 'Wait for the agent’s answer', 'Waiting must use an accessible running icon in the action row');
      assert(host.querySelector('.agr-composer .pull-diff-review__status') === null
        && Math.abs(waiting.getBoundingClientRect().top - reply.getBoundingClientRect().top) < 1,
      'Waiting must not add a separate status line');
      const pendingField = host.querySelector<HTMLTextAreaElement>('[aria-label="Private question to the agent"]');
      assert(pendingField !== null && !pendingField.disabled && reply.disabled, 'The pending composer must allow editing but block sending');
      pendingField.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true }));
      await settle();
      assert(asked.length === 1, 'Cmd+Enter must not send while the agent is answering');
    });

    await step('Back sends the answered thread behind its comment as a 6px tab and a chip', async () => {
      const thread = stored.get('thread-1');
      assert(thread !== undefined, 'The thread was not stored');
      stored.set(thread.id, { ...thread, pending: false, version: 2,
        messages: [...thread.messages, { id: 'summary', author: 'AGENT', body: SUMMARY, createdAt: AT, error: null }] });
      await refresh();
      await settle();
      assert(host.querySelector('.pr-private-summary')?.textContent?.includes('Plan: Rename it and its two callers.') === true,
        'The summary must render as the verdict');
      button(host, 'Back to comment').click();
      await settle();
      const tab = host.querySelector<HTMLElement>('.pr-private-tab');
      assert(tab !== null && getComputedStyle(tab).width === '6px', `The tab is ${tab === null ? 'missing' : getComputedStyle(tab).width} wide`);
      assert(chip()?.textContent === 'Local · 1 reply ↑', `The chip reads "${chip()?.textContent ?? ''}"`);
    });

    await step('The branch-row icon hides and shows private threads, closing the forward card', async () => {
      const toggle = host.querySelector<HTMLButtonElement>('.pr-overview__branches > .pr-private-toggle');
      assert(toggle !== null, 'The private threads toggle must sit in the branch row');
      // Beside the metadata chips it took a line of its own once the labels filled the row.
      assert(toggle.previousElementSibling?.getAttribute('aria-label') === 'Open in browser', 'The toggle must follow Open in browser');
      host.style.width = '640px';
      const state = fixtureElement<HTMLElement>('.pr-overview__branches > .pr-state', host, 'The state pill is missing');
      const branch = fixtureElement<HTMLElement>('.pr-overview__head-branch code', host, 'The head branch is missing');
      assert(toggle.getBoundingClientRect().top < state.getBoundingClientRect().bottom && branch.scrollWidth > branch.clientWidth,
        'A long head branch must shrink so the toggle stays on the state pill\'s line');
      host.style.width = '';
      assert(host.querySelector('.pr-private-strip') === null && toggle.textContent === '', 'The old text toolbar must be absent');
      assert(toggle.getAttribute('aria-label') === 'Private threads' && toggle.getAttribute('aria-pressed') === 'true' && toggle.getAttribute('title') === 'Hide private threads', 'The toggle must start on');
      chip()?.click();
      await settle();
      assert(host.querySelector('.pr-private-forward') !== null, 'The thread must open before hiding');
      toggle.click();
      await settle();
      assert(chip()?.textContent === 'Private thread hidden' && toggle.getAttribute('title') === 'Show private threads (1 hidden)'
        && toggle.getAttribute('aria-pressed') === 'false' && host.querySelector('.pr-private-tab') === null
        && host.querySelector('.pr-private-forward') === null, 'Hiding must close the thread and leave its hidden marker');
      toggle.click();
      await settle();
      assert(host.querySelector('.pr-private-tab') !== null && toggle.getAttribute('aria-pressed') === 'true',
        'The toggle must show the threads again');
      toggle.click();
      await settle();
      chip()?.click();
      await settle();
      assert(host.querySelector('.pr-private-tab') !== null && toggle.getAttribute('aria-pressed') === 'true',
        'The hidden marker must also show the threads again');
    });

    await step('Draft with agent fills the reply editor with a mention and posts nothing', async () => {
      await draftReply();
      assert(host.querySelector('.pr-thread-draft')?.textContent?.includes('Agent is drafting a reply from your private thread…') === true
        && host.querySelector('.pr-private-forward') === null, 'Drafting must send the thread back and show its progress');
      finishDraft('Renamed it to resolveOwner.');
      await settle();
      const editor = host.querySelector<HTMLTextAreaElement>('[aria-label="Reply to review thread"]');
      assert(editor?.value === '@bob Renamed it to resolveOwner.', `The editor holds "${editor?.value ?? ''}"`);
      assert(host.querySelector('.pr-thread-draft__badge')?.textContent === 'Draft · not posted'
        && host.textContent?.includes('Posts as you') === true && posted.length === 0,
      'The draft must be marked unposted and nothing may reach GitHub');
    });

    await step('Discard clears the draft', async () => {
      button(host, 'Discard').click();
      await settle();
      assert(host.querySelector('[aria-label="Reply to review thread"]') === null && host.querySelector('.pr-thread-draft__badge') === null
        && posted.length === 0, 'Discard must drop the draft without posting');
    });

    await step('A late draft leaves the caret where the user types, and Cmd+Enter never posts it', async () => {
      await draftReply();
      chip()?.click();
      await settle();
      const composer = host.querySelector<HTMLTextAreaElement>('[aria-label="Private question to the agent"]');
      assert(composer !== null && document.activeElement === composer, 'Bringing the thread forward must focus its composer');
      finishDraft('Renamed it to resolveOwner.');
      await settle();
      const editor = host.querySelector<HTMLTextAreaElement>('[aria-label="Reply to review thread"]');
      assert(editor !== null && editor.value === '@bob Renamed it to resolveOwner.', `The editor holds "${editor?.value ?? ''}"`);
      assert(document.activeElement === composer, 'The draft must land without taking the caret from the private composer');
      editor.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true }));
      await settle();
      assert(posted.length === 0 && host.querySelector('.pr-thread-draft__badge') !== null, 'Cmd+Enter must never post an agent draft');
      button(host, 'Back to comment').click();
      await settle();
      button(host, 'Discard').click();
      await settle();
    });

    await step('Use draft loads the saved automatic reply without another model call or publication', async () => {
      const thread = stored.get('thread-1');
      assert(thread !== undefined, 'The private thread must exist');
      const calls = draftCalls;
      stored.set(thread.id, { ...thread, replyDraft: 'I will rename the helper.', version: thread.version + 1 });
      await refresh();
      await settle();
      chip()?.click();
      await settle();
      assert(host.textContent?.includes('Private draft · review and edit before posting.') === true, 'The automatic draft must remain private');
      button(host, 'Use draft').click();
      await settle();
      const editor = host.querySelector<HTMLTextAreaElement>('[aria-label="Reply to review thread"]');
      assert(editor?.value === '@bob I will rename the helper.', 'The saved draft must fill the editable reply composer');
      assert(draftCalls === calls && posted.length === 0, 'Using a saved draft must neither regenerate nor publish it');
      button(host, 'Discard').click();
      stored.set(thread.id, thread);
      await refresh();
      await settle();
    });

    await step('Comment posts the draft once, to the thread root', async () => {
      await draftReply();
      finishDraft('Renamed it to resolveOwner.');
      await settle();
      button(host, 'Comment').click();
      await settle();
      assert(posted.length === 1 && posted[0][0] === 'owner/repo' && posted[0][1] === 7 && posted[0][2] === ROOT
        && posted[0][3] === '@bob Renamed it to resolveOwner.', `Comment posted ${JSON.stringify(posted)}`);
      assert(host.querySelector('.pr-thread-draft__badge') === null, 'A posted reply is no longer a draft');
    });
  }
  finally {
    dispose();
    Object.defineProperty(window, 'bridge', { configurable: true, value: originalBridge });
  }
  return results;
}
