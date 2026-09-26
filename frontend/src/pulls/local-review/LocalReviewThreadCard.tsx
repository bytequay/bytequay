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
import { useRef, useState } from 'react';
import Avatar from '../../Avatar';
import BetterWordsButton from '../BetterWordsButton';
import MarkdownBody from '../MarkdownBody';
import MarkdownEditor from '../MarkdownEditor';
import { ReviewThreadCard, ReviewThreadMessage } from '../ReviewThreadCard';
import type { LocalReviewThreadActions, LocalReviewThreadDto } from './types';

export default function LocalReviewThreadCard({ thread, onReply, onResolve, onRetry, disabledReason: codeDisabledReason, learningDisabledReason,
  showCode = true, outdated = false }: LocalReviewThreadActions & {
  thread: LocalReviewThreadDto;
  showCode?: boolean;
  outdated?: boolean;
}) {
  const disabledReason = thread.learningAnchor != null && learningDisabledReason !== undefined ? learningDisabledReason : codeDisabledReason;
  const [replying, setReplying] = useState(false);
  const [body, setBody] = useState('');
  const [preview, setPreview] = useState(false);
  const [pending, setPending] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const inFlight = useRef(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const busy = pending !== null;
  const prefix = thread.side === 'LEFT' ? 'L' : 'R';
  const start = thread.startLine ?? thread.line;
  const range = start === thread.line ? `${prefix}${thread.line}` : `${prefix}${start}–${prefix}${thread.line}`;
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
  const controls = replying ? <footer>
    <MarkdownEditor value={body} onChange={setBody} ariaLabel="Reply to local review" placeholder="Reply privately to this thread…"
      repository={thread.repo} preview={preview} onPreviewChange={setPreview} textareaRef={textareaRef} isPrivate
      statusMessage={disabledReason}
      error={error} busy={busy} autoFocus onSubmit={reply}
      // Escape must not throw away a half-written reply.
      onCancel={() => { if (!body.trim()) close(); }}>
      <BetterWordsButton text={body} textareaRef={textareaRef} repository={thread.repo} workspaceId={thread.workspaceId}
        disabled={preview || busy} onError={cause => setError(cause === null ? null : cause instanceof Error ? cause.message : 'Could not improve the reply.')} />
      <button type="button" disabled={busy} onClick={close}>Cancel</button>
      <button className="is-primary" type="button" disabled={busy || !body.trim() || Boolean(disabledReason)} title={disabledReason ?? undefined}
        onClick={reply}>{pending === 'reply' ? 'Replying…' : 'Reply to agent'}</button>
    </MarkdownEditor>
  </footer> : <footer>
    <button className="pr-thread-reply__open" type="button" disabled={busy || Boolean(disabledReason)} title={disabledReason ?? undefined}
      onClick={() => { setError(null); setPreview(false); setReplying(true); }}>Reply</button>
    {error && <span className="pr-thread-reply__error" role="alert">{error}</span>}
    {disabledReason && <span className="pr-local-review__note" role="status">{disabledReason}</span>}
    <button className="pr-thread-resolve" type="button" disabled={busy}
      onClick={() => { void mutate('resolve', () => onResolve(thread.id, !thread.resolved)); }}>
      {pending === 'resolve' ? 'Saving…' : thread.resolved ? 'Reopen' : 'Resolve'}
    </button>
  </footer>;
  const snapshot = showCode && thread.code ? <div className="pr-local-review__snapshot">
    <div>{outdated ? 'Recorded code' : 'Selected code'} · {thread.side === 'LEFT' ? 'base' : 'head'}{' '}
      <code>{(thread.side === 'LEFT' ? thread.baseSha : thread.headSha)?.slice(0, 8) ?? 'at recorded PR revision'}</code></div>
    <pre>{thread.code}</pre>
  </div> : undefined;
  return <ReviewThreadCard localId={thread.id} resolved={thread.resolved} outdated={outdated}
    privateLabel={thread.learningAnchor == null ? undefined : 'Summary · Private'}
    location={thread.learningAnchor != null ? <span className="pr-local-review__location agr-learning-card__title">{thread.learningAnchor.title}</span>
      : showCode ? <code className="pr-local-review__location" title={thread.path}>{thread.path}:{range}</code>
      : <span className="pr-local-review__location">Comment on {start === thread.line ? 'line' : 'lines'} <strong>{range}</strong></span>}
    code={snapshot} controls={controls}>
    {thread.learningAnchor != null && <ReviewThreadMessage timestamp={thread.createdAt}
      author={<><Avatar login="Agent" size={22} allowGitHubFallback={false} /><strong>Agent</strong><span>Summary at time of question</span></>}>
      <MarkdownBody body={thread.learningAnchor.body} repository={thread.repo} />
    </ReviewThreadMessage>}
    {thread.messages.map(message => <ReviewThreadMessage key={message.id} timestamp={message.createdAt}
      author={<><Avatar login={message.author === 'USER' ? 'You' : 'Agent'} size={22} allowGitHubFallback={false} />
        <strong>{message.author === 'USER' ? 'You' : 'Agent'}</strong></>}
      menu={message.author === 'USER' && message.deliveryState !== null && message.deliveryState !== 'COMPLETED'
        && <span className="pr-local-review__delivery">{message.deliveryState === 'UNSENT' ? 'Not sent'
          : message.deliveryState.toLowerCase().replaceAll('_', ' ')}</span>}>
      <MarkdownBody body={message.body} repository={thread.repo} />
      {message.deliveryError && <p className="pr-local-review__delivery-error" role="alert">{message.deliveryError}</p>}
      {message.author === 'USER' && message.deliveryState === 'UNSENT' && <div className="pr-local-review__retry">
        <button type="button" disabled={busy || Boolean(disabledReason)} title={disabledReason ?? undefined}
          onClick={() => { void mutate(`retry:${message.id}`, () => onRetry(thread.id, message.id)); }}>
          {pending === `retry:${message.id}` ? 'Retrying…' : 'Retry'}
        </button>
      </div>}
    </ReviewThreadMessage>)}
  </ReviewThreadCard>;
}

export function localReviewMatchesRevision(thread: LocalReviewThreadDto, baseSha?: string, headSha?: string): boolean {
  return thread.learningAnchor == null && thread.headSha !== null && thread.headSha === headSha && thread.baseSha === (baseSha ?? null);
}
