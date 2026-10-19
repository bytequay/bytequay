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
import { useReviewDraft } from '../ReviewDrafts';
import Avatar from '../../Avatar';
import UserAvatar from '../../UserAvatar';
import CopyLinkButton from '@bytequay/ui/CopyLinkButton';
import BetterWordsButton from '../BetterWordsButton';
import MarkdownBody from '../MarkdownBody';
import MarkdownEditor from '../MarkdownEditor';
import { commentIndexTarget } from '../ReviewCommentIndex';
import LocalCard, { LocalCardMessage, SummaryCardTitle } from './LocalCard';
import { canSendAgain, localReviewConversation } from './conversation';
import type { LocalReviewThreadActions, LocalReviewThreadDto, LocalReviewThreads } from './types';

export default function LocalReviewThreadCard({ thread, viewer, onReply, onResolve, onRetry, disabledReason: codeDisabledReason, learningDisabledReason,
  showCode = true, outdated = false, badges }: LocalReviewThreadActions & Pick<LocalReviewThreads, 'viewer'> & {
  thread: LocalReviewThreadDto;
  showCode?: boolean;
  outdated?: boolean;
  /** Actions the page adds beside the copy-link button. */
  badges?: ReactNode;
}) {
  const learning = thread.learningAnchor ?? null;
  const working = thread.messages.some(message => message.author === 'USER'
    && (message.deliveryState === 'ACCEPTED' || message.deliveryState === 'RUNNING' || message.deliveryState === 'STARTING'));
  const queued = thread.messages.some(message => message.author === 'USER' && message.deliveryState === 'QUEUED');
  const waitingReason = working ? 'Agent responding'
    : queued ? 'Your comment is queued. You can reply when the agent finishes.' : null;
  const disabledReason = waitingReason ?? (learning !== null && learningDisabledReason !== undefined ? learningDisabledReason : codeDisabledReason);
  const [replying, setReplying] = useReviewDraft(`local:${thread.id}:replying`, false);
  const [body, setBody] = useReviewDraft(`local:${thread.id}:body`, '');
  const [preview, setPreview] = useState(false);
  const [pending, setPending] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const inFlight = useRef(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const busy = pending !== null;
  const prefix = thread.side === 'LEFT' ? 'L' : 'R';
  const start = thread.startLine ?? thread.line;
  const range = start === thread.line ? `${prefix}${thread.line}` : `${prefix}${start}–${prefix}${thread.line}`;
  const learningSha = learning?.cardId.startsWith('commit:') ? learning.cardId.slice('commit:'.length) : undefined;
  const close = () => { setReplying(false); setBody(''); setPreview(false); setError(null); };
  const mutate = async (action: string, operation: () => Promise<void>) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setPending(action);
    setError(null);
    try { await operation(); if (action === 'reply') close(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not update the local review.'); }
    finally { inFlight.current = false; setPending(null); }
  };
  const reply = () => {
    if (!body.trim() || disabledReason) return;
    void mutate('reply', () => onReply(thread.id, body.trim()));
  };
  const progress = working && <span className="pr-local-review__progress" role="status"><span className="sr-only">Agent responding</span></span>;
  const controls = replying ? <footer className="lrc__foot is-composing">
    {progress}
    <MarkdownEditor value={body} onChange={setBody} ariaLabel="Reply to local review" placeholder="Reply privately to this thread…"
      repository={thread.repo} preview={preview} onPreviewChange={setPreview} textareaRef={textareaRef} isPrivate
      statusMessage={working ? null : disabledReason}
      error={error} busy={busy || waitingReason !== null} autoFocus onSubmit={reply}
      // Escape must not throw away a half-written reply.
      onCancel={() => { if (!body.trim()) close(); }}>
      <BetterWordsButton text={body} textareaRef={textareaRef} repository={thread.repo} workspaceId={thread.workspaceId}
        disabled={preview || busy || waitingReason !== null} onError={cause => setError(cause === null ? null : cause instanceof Error ? cause.message : 'Could not improve the reply.')} />
      <button type="button" disabled={busy} onClick={close}>Cancel</button>
      <button className="is-primary" type="button" disabled={busy || !body.trim() || Boolean(disabledReason)} title={disabledReason ?? undefined}
        onClick={reply}>{pending === 'reply' ? 'Replying…' : 'Reply to agent'}</button>
    </MarkdownEditor>
  </footer> : <footer className="lrc__foot">
    {progress}
    <button className="lrc__ask" type="button" disabled={busy || Boolean(disabledReason)} title={disabledReason ?? undefined}
      onClick={() => { setError(null); setPreview(false); setReplying(true); }}>Reply</button>
    {error && <span className="lrc__error" role="alert">{error}</span>}
    {!working && disabledReason && <span className="pr-local-review__note" role="status">{disabledReason}</span>}
    <button className="lrc__action" type="button" disabled={busy || waitingReason !== null} title={waitingReason ?? undefined}
      onClick={() => { void mutate('resolve', () => onResolve(thread.id, !thread.resolved)); }}>
      {pending === 'resolve' ? 'Saving…' : thread.resolved ? 'Reopen' : 'Resolve'}
    </button>
  </footer>;
  const snapshot = showCode && thread.code ? <div className="pr-local-review__snapshot">
    <div>{outdated ? 'Recorded code' : 'Selected code'} · {thread.side === 'LEFT' ? 'base' : 'head'}{' '}
      <code>{(thread.side === 'LEFT' ? thread.baseSha : thread.headSha)?.slice(0, 8) ?? 'at recorded PR revision'}</code></div>
    <pre>{thread.code}</pre>
  </div> : undefined;
  return <LocalCard tone={learning === null ? 'local' : learningSha === undefined ? 'purpose' : 'commit'}
    resolved={thread.resolved} outdated={outdated} attributes={{ 'data-local-review-thread': thread.id }}
    badges={<>{badges}<CopyLinkButton label="conversation" value={localReviewConversation(thread, outdated)} /></>}
    title={learning !== null ? <SummaryCardTitle sha={learningSha} tooltip={learning.title} />
      : showCode ? <code className="lrc__title is-path" title={thread.path}><bdi dir="ltr">{thread.path}:{range}</bdi></code>
      : <span className="lrc__title">Comment on {start === thread.line ? 'line' : 'lines'} <strong>{range}</strong></span>}
    code={snapshot} controls={controls}>
    {learning !== null && <LocalCardMessage timestamp={thread.createdAt} author="Developer"
      avatar={<Avatar login="Agent" size={22} allowGitHubFallback={false} />}
      tag={<span className="lrc-message__meta">Summary at time of question</span>}>
      <MarkdownBody body={learning.body} repository={thread.repo} />
    </LocalCardMessage>}
    {thread.messages.map(message => <LocalCardMessage key={message.id} timestamp={message.createdAt}
      author={message.author === 'USER' ? 'You' : 'Agent'}
      attributes={commentIndexTarget(message.author === 'USER' ? 'human' : 'agent',
        `${message.author === 'USER' ? 'You' : 'Agent'} · ${learning?.title ?? `${thread.path}:${range}`} · ${thread.resolved ? 'Resolved' : 'Open'} · ${outdated ? 'Outdated' : ''}`, message.body)}
      avatar={message.author === 'USER' && viewer?.login
        ? <UserAvatar login={viewer.login} avatarUrl={viewer.avatarUrl} size={22} />
        : <Avatar login={message.author === 'USER' ? 'You' : 'Agent'} size={22} allowGitHubFallback={false} />}
      menu={message.author === 'USER' && message.deliveryState !== null && message.deliveryState !== 'COMPLETED'
        && <span className="pr-local-review__delivery">{message.deliveryState === 'UNSENT' ? 'Not sent'
          : message.deliveryState.toLowerCase().replaceAll('_', ' ')}</span>}>
      <MarkdownBody body={message.body} repository={thread.repo} />
      {message.deliveryError && <p className="pr-local-review__delivery-error" role="alert">{message.deliveryError}</p>}
      {message.author === 'USER' && canSendAgain(message.deliveryState) && <div className="pr-local-review__retry">
        <button type="button" disabled={busy || Boolean(disabledReason)} title={disabledReason ?? undefined}
          onClick={() => { void mutate(`retry:${message.id}`, () => onRetry(thread.id, message.id)); }}>
          {message.deliveryState === 'UNSENT' ? (pending === `retry:${message.id}` ? 'Retrying…' : 'Retry')
            : pending === `retry:${message.id}` ? 'Sending…' : 'Send again'}
        </button>
      </div>}
    </LocalCardMessage>)}
  </LocalCard>;
}

export function localReviewMatchesRevision(thread: LocalReviewThreadDto, baseSha?: string, headSha?: string): boolean {
  return thread.learningAnchor == null && thread.headSha !== null && thread.headSha === headSha && thread.baseSha === (baseSha ?? null);
}
