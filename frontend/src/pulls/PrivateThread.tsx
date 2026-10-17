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
import { useRef, useState, type ReactNode } from 'react';
import { getCached, setCached } from '../dataCache';
import { ChevronRight, LoaderCircle, MessagesSquare } from 'lucide-react';
import Avatar from '../Avatar';
import { relativeTime } from '../relativeTime';
import { ANSWERING_ELSEWHERE, isSummaryRequest, parseCommentSummary, type CommentSummaryDto, type TaskDiscussionDto } from '../agents/discussions/contracts';
import { replyCount, retryTarget } from '../agents/discussions/DiscussionFooter';
import type { DiscussionBridgePort, TaskDiscussions } from '../agents/discussions/useTaskDiscussions';
import type { PullCommentTarget, PullRequestDto } from '../types';
import ReviewComposer from './agent-review/ReviewComposer';
import MarkdownBody from './MarkdownBody';
import { remoteErrorMessage } from '../remoteError';
import LocalCard, { LocalCardMessage } from './local-review/LocalCard';

/** What the conversation's host supplies; absent where the pull request has none, so nothing here renders there. */
export type PrivateThreadSource = {
  /** This pull request's comment threads, by comment key. */
  threads: ReadonlyMap<string, TaskDiscussionDto>;
  busy: boolean;
  /** Why a send waits while another question on the host is answering. */
  busyReason: string;
  disabledReason: string | null;
  /** Setup blocks sending, while the comment's button and editable question stay available. */
  sendDisabledReason?: string | null;
  sendingLabel?: string;
  setup?: ReactNode;
  modelSettingsHref?: string;
  ask: (key: string, question: string) => Promise<void>;
  resolve: (thread: TaskDiscussionDto, resolved: boolean) => Promise<void>;
  retry: (thread: TaskDiscussionDto, messageId: string) => Promise<void>;
  draftReply: (thread: TaskDiscussionDto) => Promise<string>;
};

/** The source plus the client-only view state the pull-request tab owns. */
export type PrivateThreads = PrivateThreadSource & {
  /** At most one thread is forward per pull-request view. */
  forward: string | null;
  show: boolean;
  /**
   * Each thread's unsent text, by comment key. Held here, not in the card: the card remounts whenever
   * another comment comes forward.
   */
  drafts: ReadonlyMap<string, string>;
  setForward: (key: string | null) => void;
  setShow: (show: boolean) => void;
  /** An empty draft drops the key. */
  setDraft: (key: string, draft: string, ifUnchanged?: string) => void;
};

export function privateThreadKey(kind: PullCommentTarget, githubId: number): string {
  return `${kind}:${githubId}`;
}

export function hiddenThreadCount(threads: PrivateThreads): number {
  return threads.show ? 0 : [...threads.threads.values()].filter(thread => thread.messages.length > 0).length;
}

/** The host task's private threads on one pull request's comments, keyed by comment. */
export function privateThreadSource(discussions: TaskDiscussions, pr: Pick<PullRequestDto, 'repo' | 'number'>,
  busyReason = ANSWERING_ELSEWHERE): PrivateThreadSource {
  return {
    threads: new Map(discussions.commentThreads.flatMap(thread => thread.comment !== null
      && thread.comment.repo.toLowerCase() === pr.repo.toLowerCase() && thread.comment.number === pr.number
      ? [[thread.comment.key, thread] as const] : [])),
    busy: discussions.busy,
    busyReason,
    disabledReason: discussions.disabledReason,
    ask: async (key, question) => {
      await discussions.askComment({ repo: pr.repo, number: pr.number, key, question });
    },
    resolve: (thread, resolved) => discussions.resolve(thread.id, resolved, thread.version),
    retry: (thread, messageId) => discussions.retry(thread.id, messageId),
    draftReply: thread => discussions.draftReply(thread.id),
  };
}

/** Private discussions through the review routes, which reach its workflow-owned task. */
export function reviewThreadBridge(reviewId: string): DiscussionBridgePort {
  return {
    listTaskDiscussions: () => window.bridge.listReviewDiscussions(reviewId),
    askTaskDiscussionComment: request => window.bridge.askReviewCommentThread(reviewId, request.key, request.question),
    resolveTaskDiscussion: request => window.bridge.resolveReviewDiscussion(reviewId, request.discussionId, request.resolved, request.expectedVersion),
    retryTaskDiscussion: request => window.bridge.retryReviewDiscussion(reviewId, request.discussionId, request.messageId),
    draftTaskDiscussionReply: request => window.bridge.draftReviewCommentReply(reviewId, request.discussionId),
    openTaskDiscussion: request => window.bridge.openReviewDiscussion(reviewId, { turnId: request.turnId, quote: request.quote, occurrence: request.occurrence, question: request.question }),
    askTaskDiscussion: request => window.bridge.askReviewDiscussion(reviewId, request.discussionId, request.question),
  };
}

type PrivateThreadView = Pick<PrivateThreads, 'forward' | 'show' | 'drafts'>;

/** A PR page keeps its drafts across navigation to setup; other hosts keep their existing local view state. */
export function usePrivateThreads(source: PrivateThreadSource | undefined, pullRequestKey?: string): PrivateThreads | undefined {
  const cacheKey = pullRequestKey === undefined ? null : `private-thread-drafts:${pullRequestKey}`;
  const [view, setView] = useState<PrivateThreadView>(() => (cacheKey === null ? undefined : getCached<PrivateThreadView>(cacheKey))
    ?? { forward: null, show: true, drafts: new Map() });
  const live = useRef(view);
  live.current = view;
  const update = (change: (current: PrivateThreadView) => PrivateThreadView) => {
    // A send can finish after navigation; update the saved draft even after this view unmounts.
    const next = change((cacheKey === null ? undefined : getCached<PrivateThreadView>(cacheKey)) ?? live.current);
    live.current = next;
    if (cacheKey !== null) setCached(cacheKey, next);
    setView(next);
  };
  return source === undefined ? undefined : {
    ...source, ...view,
    setDraft: (key, draft, ifUnchanged) => update(current => {
      if (ifUnchanged !== undefined && (current.drafts.get(key) ?? '') !== ifUnchanged) return current;
      const drafts = new Map(current.drafts);
      if (draft === '') drafts.delete(key);
      else drafts.set(key, draft);
      return { ...current, drafts };
    }),
    setForward: forward => update(current => ({ ...current, forward, show: forward !== null || current.show })),
    setShow: show => update(current => ({ ...current, show, forward: show ? current.forward : null })),
  };
}

/** One line of a comment, stripped of the markdown that reads as noise when it cannot render. */
export function commentExcerpt(body: string): string {
  const line = body
    .replace(/```[^\n]*\n?/g, ' ')
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^\s{0,3}(?:#{1,6}|>|[-*+]|\d+[.)])\s+/gm, '')
    .replace(/[`*_~]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  return line.length <= 160 ? line : `${line.slice(0, 159).trimEnd()}…`;
}

/** The agent's draft addressed to the commenter, after whatever the user already wrote. */
export function withMention(draft: string, text: string, login: string): string {
  const mentioned = new RegExp(`(^|[^\\w-])@${login.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![\\w-])`, 'i').test(text);
  const reply = mentioned || login === '' ? text : `@${login} ${text}`;
  return draft.trim() === '' ? reply : `${draft.trimEnd()}\n\n${reply}`;
}

function held(threads: PrivateThreads, threadKey: string): TaskDiscussionDto | null {
  const thread = threads.threads.get(threadKey);
  return thread !== undefined && thread.messages.length > 0 && threads.forward !== threadKey ? thread : null;
}

function replies(thread: TaskDiscussionDto): string {
  const count = replyCount(thread);
  return count === 1 ? '1 reply' : `${count} replies`;
}

export function PrivateThreadsToggle({ threads }: { threads: PrivateThreads }) {
  const hidden = hiddenThreadCount(threads);
  const title = threads.show ? 'Hide private threads' : `Show private threads${hidden > 0 ? ` (${hidden} hidden)` : ''}`;
  return (
    <button type="button" className="pr-private-toggle" aria-label="Private threads" aria-pressed={threads.show}
      title={title} onClick={() => threads.setShow(!threads.show)}>
      <MessagesSquare size={16} strokeWidth={1.7} aria-hidden="true" />
    </button>
  );
}

/**
 * The header chip of a thread behind its comment, then Ask agent, which sit right before the comment's menu. Only other people's
 * comments offer Ask agent; a thread already behind the user's own comment keeps its chip.
 */
export function PrivateThreadControls({ threads, threadKey, own }: { threads: PrivateThreads; threadKey: string; own: boolean }) {
  const thread = held(threads, threadKey);
  const failed = thread !== null && !thread.pending && retryTarget(thread) !== null;
  return <>
    {thread !== null && !threads.show && (
      <button type="button" className="pr-private-chip is-hidden" onClick={() => threads.setShow(true)}>Private thread hidden</button>
    )}
    {thread !== null && threads.show && (
      <button type="button" className={`pr-private-chip${thread.resolved && !thread.pending && !failed ? ' is-resolved' : ''}`}
        title={thread.resolved ? 'Open the resolved private thread' : 'Bring the private thread forward'}
        onClick={() => threads.setForward(threadKey)}>
        {thread.pending ? 'Local · answering… ↑'
          : failed ? 'Local · couldn’t answer ↑'
            : thread.resolved ? <><span className="pr-private-chip__check" aria-hidden="true">✓</span>Resolved · {replies(thread)} ↑</>
              : `Local · ${replies(thread)} ↑`}
      </button>
    )}
    {!own && <button type="button" className="pr-ask-agent" disabled={threads.disabledReason !== null}
      title={threads.disabledReason ?? 'Ask the agent about this comment. Private.'} onClick={() => threads.setForward(threadKey)}>
      Ask agent
    </button>}
  </>;
}

/** The green edge of a thread that sits behind its comment. */
export function PrivateThreadTab({ threads, threadKey }: { threads: PrivateThreads; threadKey: string }) {
  if (held(threads, threadKey) === null || !threads.show) return null;
  return <button type="button" className="pr-private-tab" aria-label="Open the local thread" title="Open the local thread"
    onClick={() => threads.setForward(threadKey)} />;
}

const VALID: Record<CommentSummaryDto['valid'], [string, string]> = {
  yes: ['Valid', 'is-ready'], partly: ['Partly valid', 'is-warn'], no: ['Not valid', 'is-neutral'], unsure: ['Unsure', 'is-neutral'],
};
const ACT: Record<CommentSummaryDto['do'], [string, string]> = {
  yes: ['Do', 'is-ready'], your_call: ['Your call', 'is-provisional'], no: ['Skip', 'is-neutral'],
};
const SIZE: Record<Exclude<CommentSummaryDto['size'], ''>, [string, string]> = {
  trivial: ['Trivial', 'is-neutral'], small: ['Small', 'is-neutral'], big: ['Big', 'is-warn'],
};

/** Reviewer-derived text, so every field stays plain text: never markdown, never a link. */
function CommentSummary({ summary }: { summary: CommentSummaryDto }) {
  const pills = [VALID[summary.valid], ACT[summary.do], summary.do === 'no' || summary.size === '' ? null : SIZE[summary.size]];
  return (
    <div className="pr-private-summary">
      <p>{summary.ask}</p>
      <p className="pr-private-summary__pills">
        {pills.map(pill => pill !== null && <span key={pill[0]} className={`agr-pill ${pill[1]}`}>{pill[0]}</span>)}
      </p>
      <p><strong>Why:</strong> {summary.why}</p>
      {summary.plan !== '' && <p><strong>Plan:</strong> {summary.plan}</p>}
    </div>
  );
}

/**
 * A comment with its private thread brought forward: the comment folds to one line and the thread
 * opens in its place, so the comments before and after it keep their positions.
 */
export function PrivateThreadForward({ threads, threadKey, author, avatarUrl, timestamp, body, repository, viewerLogin, onDraftReply }: {
  threads: PrivateThreads;
  threadKey: string;
  author: string;
  avatarUrl: string | null;
  timestamp: string | null;
  body: string;
  repository: string;
  viewerLogin?: string;
  /** Review-thread comments only: their thread has a GitHub reply editor to fill. */
  onDraftReply?: (thread: TaskDiscussionDto) => void;
}) {
  const [unfolded, setUnfolded] = useState(true);
  const draft = threads.drafts.get(threadKey) ?? '';
  const setDraft = (value: string): void => threads.setDraft(threadKey, value);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const thread = threads.threads.get(threadKey);
  const started = thread !== undefined && thread.messages.length > 0;
  const failure = thread === undefined || thread.pending ? null : retryTarget(thread);

  const run = async (action: () => Promise<void>): Promise<boolean> => {
    setSending(true);
    setError(null);
    try {
      await action();
      return true;
    }
    catch (cause) {
      setError(remoteErrorMessage(cause));
      return false;
    }
    finally {
      setSending(false);
    }
  };
  const send = async (): Promise<void> => {
    if (sending || threads.busy || threads.sendDisabledReason || (started && draft.trim() === '')) return;
    if (await run(() => threads.ask(threadKey, draft.trim()))) threads.setDraft(threadKey, '', draft);
  };
  const cancel = (): void => {
    setDraft('');
    if (!started) threads.setForward(null);
  };

  const errorNote = error !== null && <span className="pr-thread-reply__error" role="alert">{error}</span>;
  const controls = threads.disabledReason !== null
    ? <footer className="lrc__foot"><span className="pr-local-review__note" role="status">{threads.disabledReason}</span></footer>
    : thread?.resolved === true
      ? <footer className="lrc__foot pr-private-resolved">
        <span>The GitHub thread is unchanged.</span>
        {errorNote}
        <button type="button" className="lrc__action" disabled={sending}
          onClick={() => { void run(() => threads.resolve(thread, false)); }}>Reopen</button>
      </footer>
      : <footer className="lrc__foot is-composing pr-private-compose">
        <ReviewComposer repository={repository} value={draft} autoFocus busy={sending}
          ariaLabel="Private question to the agent"
          placeholder={started ? 'Reply to the agent…' : `Ask about ${author}’s comment…`}
          primaryLabel={started ? 'Reply' : 'Ask agent'} allowEmpty={!started} sendingLabel={threads.sendingLabel}
          disabledReason={threads.sendDisabledReason}
          waitingReason={threads.busy ? (thread?.pending === true ? 'Wait for the agent’s answer' : threads.busyReason) : null}
          extra={threads.setup}
          secondary={started && thread !== undefined ? <button type="button" disabled={sending}
            onClick={() => { void run(() => threads.resolve(thread, true)).then(done => { if (done) threads.setForward(null); }); }}>
            Resolve locally</button> : undefined}
          onChange={setDraft} onSubmit={send} onCancel={cancel} />
        {errorNote}
        {(error !== null || failure !== null) && threads.modelSettingsHref && <p className="pr-local-review__note">
          <a href={threads.modelSettingsHref}>Model settings</a>
        </p>}
      </footer>;

  return (
    <div className="pr-private-forward">
      <div className="pr-private-fold">
        <div className="pr-private-fold__line">
          <button type="button" className="pr-private-fold__toggle" aria-expanded={unfolded}
            title={unfolded ? 'Fold comment' : 'Show comment'} onClick={() => setUnfolded(value => !value)}>
            <ChevronRight size={11} strokeWidth={2.2} aria-hidden="true" className={unfolded ? 'is-open' : undefined} />
            <Avatar login={author} avatarUrl={avatarUrl} size={18} />
            <strong>{author}</strong>
            <time>{relativeTime(timestamp)}</time>
            {!unfolded && <span className="pr-private-fold__excerpt">{commentExcerpt(body)}</span>}
          </button>
          <button type="button" className="pr-private-fold__back" onClick={() => threads.setForward(null)}>Back to comment</button>
        </div>
        {unfolded && <div className="pr-private-fold__body"><MarkdownBody body={body} repository={repository} /></div>}
      </div>
      <div className="pr-private-thread">
        <LocalCard resolved={thread?.resolved ?? false} open chip="Private" controls={controls}
          title={<span className="lrc__title"><strong>Private thread</strong> · on {author}’s comment · only the agent sees this</span>}>
          {thread?.messages.filter(message => !isSummaryRequest(message)).map(message => {
            const agent = message.author === 'AGENT';
            const summary = agent ? parseCommentSummary(message.body) : null;
            return <LocalCardMessage key={message.id} timestamp={message.createdAt} author={agent ? 'Agent' : 'You'}
              avatar={agent
                ? <Avatar login="Agent" size={22} allowGitHubFallback={false} />
                : <Avatar login={viewerLogin ?? 'You'} size={22} allowGitHubFallback={viewerLogin !== undefined} className="pr-private-you" />}
              menu={onDraftReply !== undefined && thread !== undefined && (
                <button type="button" className="pr-private-draft" disabled={threads.disabledReason !== null || replyCount(thread) === 0}
                  title="Agent drafts a GitHub reply from this thread. Nothing is posted." onClick={() => onDraftReply(thread)}>
                  Draft with agent
                </button>)}>
              {summary !== null ? <CommentSummary summary={summary} /> : <MarkdownBody body={message.body} repository={repository} />}
            </LocalCardMessage>;
          })}
          {failure !== null && thread !== undefined && (
            <div className="pr-private-retry" role="alert">
              <span>{failure.error ?? (isSummaryRequest(failure) ? 'The summary was interrupted.' : 'The answer was interrupted.')}</span>
              <button type="button" disabled={threads.busy || sending || threads.disabledReason !== null}
                title={threads.busy ? threads.busyReason : undefined}
                onClick={() => { void run(() => threads.retry(thread, failure.id)); }}>Retry</button>
            </div>
          )}
          {thread?.pending === true && (
            <LocalCardMessage timestamp={null} author="Agent" avatar={<Avatar login="Agent" size={22} allowGitHubFallback={false} />}>
              <p className="pr-private-answering"><LoaderCircle className="pr-task-spinner" size={14} aria-hidden="true" /> Agent is answering…</p>
            </LocalCardMessage>
          )}
        </LocalCard>
      </div>
    </div>
  );
}
