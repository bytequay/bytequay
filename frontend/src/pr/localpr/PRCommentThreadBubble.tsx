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
import Avatar from '../../Avatar';
import MarkdownComposer from '../../MarkdownComposer';
import { MarkdownProse } from '../../threads/MarkdownProse';
import { AgentFindingContent, presentFinding } from '../../review/AgentEvidence';
import type { AgentReviewData } from '../../review/agentReviewTypes';
import type { LocalPR, LocalPRComment } from '../../types/localPr';
import { actorRole, agoLabel, displayName } from './prViewMeta';
import { TimelineBubble } from './TimelineBubble';

/** One PR-level local conversation. Agent findings and ordinary comments share
 * the same Markdown/reply behavior instead of falling through to plain text. */
export function PRCommentThreadBubble({ pr, comments, reviewData, onReply }: {
  pr: LocalPR;
  comments: LocalPRComment[];
  reviewData?: AgentReviewData;
  onReply?: (rootCommentId: string, body: string) => void | Promise<void>;
}) {
  const root = comments[0];
  const replies = comments.slice(1);
  const [replying, setReplying] = useState(false);
  const [body, setBody] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (root === undefined) return null;

  const pending = root.origin === 'local' && root.publishedAt === null
    && root.resolvedAt === null && root.dismissedAt === null;
  const finding = root.findingId == null || reviewData === undefined
    ? undefined
    : presentFinding(reviewData, root.findingId);
  const canReply = pending && onReply !== undefined;

  const submit = async () => {
    const text = body.trim();
    if (text.length === 0 || onReply === undefined) return;
    setSending(true);
    setError(null);
    try {
      await onReply(root.id, text);
      setBody('');
      setReplying(false);
    }
    catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
    finally {
      setSending(false);
    }
  };

  return (
    <TimelineBubble
      actor={root.author}
      role={actorRole(root.author, pr)}
      action={finding === undefined ? 'commented' : 'opened an agent review finding'}
      time={root.createdAt}
      pending={pending}
    >
      {finding === undefined
        ? <MarkdownProse text={root.body} />
        : <AgentFindingContent view={finding} body={root.body} pending={pending} />}

      {replies.length > 0 && (
        <div className="pr-local-replies">
          {replies.map(reply => {
            const role = actorRole(reply.author, pr);
            return (
              <article className="pr-local-reply" key={reply.id}>
                <Avatar login={displayName(reply.author)} size={24} className={`pr-avatar ${role === 'other' ? '' : role}`} />
                <div>
                  <header>
                    <b>{displayName(reply.author)}</b>
                    {role === 'author' && <span className="pr-badge author">Author</span>}
                    <time>{agoLabel(reply.createdAt)}</time>
                  </header>
                  <MarkdownProse text={reply.body} />
                </div>
              </article>
            );
          })}
        </div>
      )}

      {canReply && (replying ? (
        <div className="pr-local-reply-composer">
          <MarkdownComposer
            value={body}
            onChange={setBody}
            placeholder="Reply locally — Markdown supported."
            rows={3}
            disabled={sending}
            autoFocus
            textareaClassName="pr-local-reply-composer__input"
          />
          <div className="pr-local-reply-composer__actions">
            <button type="button" className="btn sm primary" disabled={sending || body.trim().length === 0} onClick={() => { void submit(); }}>
              {sending ? 'Replying…' : 'Reply'}
            </button>
            <button type="button" className="btn sm" disabled={sending} onClick={() => { setReplying(false); setBody(''); setError(null); }}>
              Cancel
            </button>
          </div>
          {error !== null && <small className="pr-local-reply-composer__error">{error}</small>}
        </div>
      ) : (
        <button type="button" className="pr-local-reply-stub" onClick={() => setReplying(true)}>
          Reply locally…
        </button>
      ))}
    </TimelineBubble>
  );
}
