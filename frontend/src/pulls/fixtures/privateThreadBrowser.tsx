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
import { createRoot } from 'react-dom/client';
import '../../css/base.css';
import '../../css/markdown-editor.css';
import '../../css/pulls.css';
import '../../css/agent-review.css';
import { TaskPullRequestView } from '../../agents/TaskPullRequestPanel';
import { privateThreadSource } from '../PrivateThread';
import { useTaskDiscussions, type DiscussionBridgePort } from '../../agents/discussions/useTaskDiscussions';
import { SUMMARY_REQUEST, type AskCommentDiscussionRequest, type TaskDiscussionDto } from '../../agents/discussions/contracts';
import type { Bridge, PullRequestDetailDto } from '../../types';
import { assert, settle } from '../../fixtures/harness';
import { EMPTY_TIMELINE_PAYLOAD } from '../model';
import { remotePullRequest } from '../PullsScreen';

const AT = '2026-09-27T00:00:00Z';
const ROOT = 61;
const detail: PullRequestDetailDto = {
  id: 7, repo: 'owner/repo', number: 7, title: 'Resolve owners', htmlUrl: null, state: 'open', draft: false,
  mergeable: null, mergeableState: null, author: 'alice', authorAvatarUrl: null, createdAt: AT, updatedAt: AT,
  mergedAt: null, baseRef: 'main', headRef: 'feature', body: 'Resolves owners.', labels: [], assignees: [], milestone: null,
  reviewers: [], additions: 1, deletions: 1, changedFiles: 1, approvalCount: 0, ciStatus: null, checkRuns: [], unavailableSections: [],
  viewerCanWrite: true,
  recentActivity: [
    { actor: 'bob', eventType: 'reviewed', timestamp: AT, body: null, githubId: 50, payload: { ...EMPTY_TIMELINE_PAYLOAD, state: 'COMMENTED' } },
    { actor: 'bob', eventType: 'review_thread', timestamp: AT, body: null, githubId: 70, payload: { ...EMPTY_TIMELINE_PAYLOAD,
      path: 'src/Owner.java', line: 3, originalLine: 3, side: 'RIGHT',
      comments: [{ githubId: ROOT, actor: 'bob', actorAvatarUrl: null, actorBot: false, timestamp: AT, body: 'Rename `helper` to `resolveOwner`.',
        htmlUrl: null, reviewId: 50, inReplyToId: null, reactions: [] }] } },
    { actor: 'alice', eventType: 'commented', timestamp: AT, body: 'Thanks, renaming it now.', githubId: 80, payload: EMPTY_TIMELINE_PAYLOAD },
  ],
};
const pr = remotePullRequest(detail, 'alice');
const SUMMARY = JSON.stringify({ ask: 'Rename helper to resolveOwner.', valid: 'yes', why: 'The name hides what it does.',
  do: 'yes', plan: 'Rename it and its two callers.', size: 'trivial' });

let refresh: () => Promise<void> = async () => {};

function Harness({ bridge }: { bridge: DiscussionBridgePort }) {
  const discussions = useTaskDiscussions({ taskId: 'task-1', workspaceId: 'workspace-1' }, { bridge });
  refresh = discussions.refresh;
  return (
    <div className="pull-detail__body" style={{ height: 880 }}>
      <TaskPullRequestView pr={pr} workspaceId="workspace-1" detail={detail} loading={false} error={null}
        onRefresh={async () => {}} onDetailUpdate={() => {}} onDetailChange={() => {}}
        privateThreads={privateThreadSource(discussions, pr)} />
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
        comment: { repo: request.repo, number: request.number, key: request.key },
        messages: [{ id: 'hidden', author: 'USER', body: request.question === '' ? SUMMARY_REQUEST : request.question, createdAt: AT, error: null }] };
      stored.set(thread.id, thread);
      return thread;
    },
    draftTaskDiscussionReply: () => new Promise<string>(resolve => { finishDraft = resolve; }),
  };
  const originalBridge = window.bridge;
  const pageBridge: Pick<Bridge, 'getUserProfile' | 'replyToReviewComment'> = {
    getUserProfile: async () => ({ login: 'alice', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0, following: 0,
      bio: null, location: null, company: null, email: null }),
    replyToReviewComment: async (...request) => { posted.push(request); },
  };
  Object.defineProperty(window, 'bridge', { configurable: true, value: pageBridge });
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
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
      const asks = host.querySelectorAll('.pr-ask-agent').length;
      assert(asks === 1 && host.textContent?.includes('Thanks, renaming it now.') === true,
        `Only bob's review comment may offer Ask agent, not alice's own comment; found ${asks}`);
    });

    await step('Ask agent brings the thread forward and Cmd+Enter asks for the summary', async () => {
      button(host, 'Ask agent').click();
      await settle();
      assert(host.querySelector('.pr-private-forward') !== null && host.textContent?.includes('on bob’s comment · only the agent sees this') === true,
        'Ask agent must bring the private thread forward in place');
      const field = host.querySelector<HTMLTextAreaElement>('[aria-label="Private question to the agent"]');
      assert(field !== null, 'The private composer did not open');
      field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true }));
      await settle();
      const request = asked[0];
      assert(asked.length === 1 && request.key === `review_comment:${ROOT}` && request.question === ''
        && request.repo === 'owner/repo' && request.number === 7, `Cmd+Enter asked ${JSON.stringify(asked)}`);
      assert(host.textContent?.includes('Agent is answering…') === true && !host.textContent.includes(SUMMARY_REQUEST),
        'The hidden summary request must show as answering, never as a message');
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

    await step('The metadata icon hides and shows private threads, closing the forward card', async () => {
      const toggle = host.querySelector<HTMLButtonElement>('.pr-meta > .pr-private-toggle');
      const labels = host.querySelector<HTMLButtonElement>('.pr-meta__picker:last-of-type button');
      assert(toggle !== null && labels !== null, 'The private threads toggle must sit beside the metadata');
      assert(toggle.getBoundingClientRect().left > labels.getBoundingClientRect().right,
        'The toggle must sit to the right of the labels');
      assert(host.querySelector('.pr-private-strip') === null && toggle.textContent === '', 'The old text toolbar must be absent');
      assert(toggle.getAttribute('aria-pressed') === 'true' && toggle.getAttribute('title') === 'Hide private threads', 'The toggle must start on');
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
        && host.textContent?.includes('Posts to GitHub as you') === true && posted.length === 0,
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
    flushSync(() => root.unmount());
    host.remove();
    Object.defineProperty(window, 'bridge', { configurable: true, value: originalBridge });
  }
  return results;
}
