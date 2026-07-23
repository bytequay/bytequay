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
import { renderMarkdown } from '../markdown';
import type { MarkdownRepoContext } from '../markdown';
import { ReactionAddButton } from '../pr/Reactions';
import { ReviewThreadCard } from '../pr/ReviewThreadCard';
import { ReviewThreadCard as LocalReviewThreadCard } from '../pr/localpr/ReviewThreadCard';
import type { ReactionContent } from '../pr/utils';
import type { ReviewThreadDto } from '../types';
import type { LocalPR, LocalPRComment } from '../types/localPr';
import { Av } from './atoms';
import type { TimelineItem } from './detailModel';

/** Overview-timeline cards/rows, markup ported verbatim from the prototype's
 *  generic timeline + comment/review card shapes. */

/** The add-reaction affordance, backed by the shared GitHub emoji picker. */
export function ReactionPill({ onPick }: { onPick: (content: ReactionContent) => Promise<void> }) {
  const [state, setState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const pick = async (content: ReactionContent) => {
    setState('saving');
    try {
      await onPick(content);
      setState('saved');
    }
    catch {
      setState('error');
    }
  };
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
      <ReactionAddButton
        disabled={state === 'saving'}
        onPick={content => { void pick(content); }}
        icon={(
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="9" />
            <path d="M8.5 14.5a4.2 4.2 0 0 0 7 0" />
            <path d="M9 9.5h.01" />
            <path d="M15 9.5h.01" />
          </svg>
        )}
      />
      {state === 'saving' && <span role="status" style={{ fontSize: 11.5, color: '#8b949e' }}>Adding…</span>}
      {state === 'saved' && <span role="status" style={{ fontSize: 11.5, color: '#1a7f37' }}>Reaction added</span>}
      {state === 'error' && <span role="alert" style={{ fontSize: 11.5, color: '#cf222e' }}>Could not add reaction</span>}
    </span>
  );
}

function CommitDotIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="3.5" />
      <path d="M2 12h6.5" />
      <path d="M15.5 12H22" />
    </svg>
  );
}

const shaStyle = { fontFamily: "'SF Mono',ui-monospace,Menlo,monospace", fontSize: 11.5 } as const;
const cardStyle = { position: 'relative', border: '1px solid #d5dbe1', borderRadius: 10, background: '#fff', margin: '18px 0' } as const;
const cardHeadStyle = { display: 'flex', alignItems: 'center', gap: 9, padding: '11px 16px' } as const;
const iconRowStyle = { position: 'relative', display: 'flex', alignItems: 'flex-start', gap: 10, margin: '16px 0' } as const;
const iconRowTextStyle = { fontSize: 13, color: '#59636e', lineHeight: 1.6, paddingTop: 3, minWidth: 0 } as const;

function BotPill() {
  return <span style={{ border: '1px solid #d5dbe1', borderRadius: 999, padding: '0 7px', fontSize: 11, color: '#59636e' }}>Bot</span>;
}

function CommentCard({ item, repoCtx, onReaction }: {
  item: Extract<TimelineItem, { kind: 'comment' }>;
  repoCtx: MarkdownRepoContext;
  onReaction?: (commentId: number, content: ReactionContent) => Promise<void>;
}) {
  const remoteId = item.remoteId;
  return (
    <div style={cardStyle}>
      <div style={{ ...cardHeadStyle, borderBottom: '1px solid #eef1f4' }}>
        <Av login={item.author} size={24} square={item.bot} />
        <span style={{ fontSize: 13 }}>
          <span style={{ color: '#17191c', fontWeight: 400 }}>{item.author}</span>
          {item.bot && <> <BotPill /></>}
        </span>
        <span style={{ marginLeft: 'auto', fontSize: 12.5, color: '#8b949e' }}>{item.time}</span>
        <span style={{ color: '#8b949e', cursor: 'pointer', letterSpacing: 1, fontWeight: 700 }}>···</span>
      </div>
      <div
        className="md-body"
        style={{ padding: '13px 18px', fontSize: 13.5, lineHeight: 1.65, color: '#1f2328' }}
        dangerouslySetInnerHTML={{ __html: renderMarkdown(item.body, repoCtx) }}
      />
      {item.replies.map(reply => (
        <div key={reply.id} style={{ padding: '11px 16px 4px', display: 'flex', gap: 9, alignItems: 'flex-start', borderTop: '1px solid #eef1f4' }}>
          <Av login={reply.author} size={22} square={reply.bot} />
          <span style={{ minWidth: 0 }}>
            <span style={{ fontSize: 12.5 }}>
              <span style={{ color: '#17191c', fontWeight: 400 }}>{reply.author}</span>{' '}
              <span style={{ color: '#8b949e', fontSize: 11.5 }}>{reply.time}</span>
            </span>
            <div
              className="md-body"
              style={{ fontSize: 13, color: '#1f2328', lineHeight: 1.6 }}
              dangerouslySetInnerHTML={{ __html: renderMarkdown(reply.body, repoCtx) }}
            />
          </span>
        </div>
      ))}
      {remoteId !== null && onReaction !== undefined && (
        <div style={{ padding: item.replies.length > 0 ? '8px 16px 12px' : '0 16px 12px' }}>
          <ReactionPill onPick={content => onReaction(remoteId, content)} />
        </div>
      )}
    </div>
  );
}

function ReviewCard({
  item, repoCtx, threads, prAuthor, prHtmlUrl, onThreadReply, onThreadReact, onThreadSetResolved,
  onOpenCommentLocation,
}: {
  item: Extract<TimelineItem, { kind: 'review' }>;
  repoCtx: MarkdownRepoContext;
  threads: ReviewThreadDto[];
  prAuthor: string | null;
  prHtmlUrl: string;
  onThreadReply?: (rootGithubId: number, body: string) => Promise<void>;
  onThreadReact?: (commentGithubId: number, content: ReactionContent) => Promise<void>;
  onThreadSetResolved?: (rootGithubId: number, resolved: boolean) => Promise<void>;
  onOpenCommentLocation?: (filePath: string, line: number | null, side: 'LEFT' | 'RIGHT') => void;
}) {
  const reviewSummary = (
    <div style={cardStyle}>
      <div style={cardHeadStyle}>
        <Av login={item.author} size={24} square={item.bot} />
        <span style={{ fontSize: 13, fontWeight: 400, color: '#17191c' }}>{item.author}</span>
        {item.bot && <BotPill />}
        {item.verdict === 'approved' && (
          <span style={{ fontSize: 11.5, fontWeight: 600, color: '#1a7f37', background: '#dafbe1', border: '1px solid rgba(31,136,61,0.25)', borderRadius: 999, padding: '2px 10px' }}>Approved</span>
        )}
        {item.verdict === 'changes' && (
          <span style={{ fontSize: 11.5, fontWeight: 600, color: '#cf222e', background: '#ffebe9', border: '1px solid rgba(207,34,46,0.25)', borderRadius: 999, padding: '2px 10px' }}>Changes requested</span>
        )}
        {item.verdict === 'commented' && (
          <span style={{ fontSize: 11.5, fontWeight: 600, color: '#57606a', background: '#f6f8fa', border: '1px solid #d8dee4', borderRadius: 999, padding: '2px 10px' }}>Commented</span>
        )}
        {item.verdict === 'dismissed' && (
          <span style={{ fontSize: 11.5, fontWeight: 600, color: '#57606a', background: '#f6f8fa', border: '1px solid #d8dee4', borderRadius: 999, padding: '2px 10px' }}>Dismissed</span>
        )}
        <span style={{ marginLeft: 'auto', fontSize: 12.5, color: '#8b949e' }}>{item.time}</span>
      </div>
      {item.body !== null && (
        <div
          className="md-body"
          style={{ padding: '0 18px 14px', fontSize: 13.5, lineHeight: 1.65, color: '#1f2328' }}
          dangerouslySetInnerHTML={{ __html: renderMarkdown(item.body, repoCtx) }}
        />
      )}
    </div>
  );
  if (threads.length === 0) return reviewSummary;
  return (
    <>
      {(item.body !== null || item.verdict !== 'commented') && reviewSummary}
      <div className="pl-review-thread-list">
        {threads.map(thread => (
          <ReviewThreadCard
            key={thread.rootGithubId}
            thread={thread}
            prAuthor={prAuthor}
            prHtmlUrl={prHtmlUrl}
            repoContext={repoCtx}
            compact
            onReply={onThreadReply === undefined
              ? undefined
              : body => onThreadReply(thread.rootGithubId, body)}
            onReact={onThreadReact}
            onSetResolved={onThreadSetResolved}
            onLocationClick={onOpenCommentLocation === undefined || thread.filePath === null
              ? undefined
              : () => onOpenCommentLocation(thread.filePath!, thread.line, (thread.side ?? 'RIGHT') as 'LEFT' | 'RIGHT')}
          />
        ))}
      </div>
    </>
  );
}

export default function PullTimeline({
  items, repo, prAuthor = null, prHtmlUrl = '', reviewThreadsByRemoteId, onCommentReaction,
  onThreadReply, onThreadReact, onThreadSetResolved, localPr,
  onLocalReply, onLocalResolve, onLocalReopen, onLocalDismiss, currentUserLogin, onSubmitLocalReview,
  onAskAgentThread, onOpenCommentLocation,
}: {
  items: TimelineItem[];
  repo: string;
  prAuthor?: string | null;
  prHtmlUrl?: string;
  reviewThreadsByRemoteId?: ReadonlyMap<number, ReviewThreadDto[]>;
  onCommentReaction?: (commentId: number, content: ReactionContent) => Promise<void>;
  onThreadReply?: (rootGithubId: number, body: string) => Promise<void>;
  onThreadReact?: (commentGithubId: number, content: ReactionContent) => Promise<void>;
  onThreadSetResolved?: (rootGithubId: number, resolved: boolean) => Promise<void>;
  localPr?: LocalPR;
  onLocalReply?: (root: LocalPRComment, body: string) => Promise<void>;
  onLocalResolve?: (commentId: string) => Promise<void>;
  onLocalReopen?: (commentId: string) => Promise<void>;
  onLocalDismiss?: (commentId: string) => Promise<void>;
  currentUserLogin?: string | null;
  onSubmitLocalReview?: (commentIds: string[]) => Promise<void>;
  onAskAgentThread?: (root: LocalPRComment) => void;
  /** Jump to a file-line comment's location on the Changes tab. */
  onOpenCommentLocation?: (filePath: string, line: number | null, side: 'LEFT' | 'RIGHT') => void;
}) {
  const [owner, name] = repo.split('/');
  const repoCtx: MarkdownRepoContext = { owner: owner ?? repo, repo: name ?? repo };
  return (
    <>
      {items.map(item => {
        switch (item.kind) {
          case 'commit':
            return (
              <div key={item.id} style={iconRowStyle}>
                <span style={{ width: 26, height: 26, borderRadius: '50%', background: '#eef1f4', border: '2px solid #fff', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: '#59636e', flexShrink: 0 }}>
                  <CommitDotIcon />
                </span>
                <span style={iconRowTextStyle}>
                  <span style={{ color: '#1f2328' }}>{item.message}</span> · <span style={shaStyle}>{item.sha}</span> · {item.time}
                </span>
              </div>
            );
          case 'merged':
            return (
              <div key={item.id} style={iconRowStyle}>
                <span style={{ width: 26, height: 26, borderRadius: '50%', background: '#8250df', border: '2px solid #fff', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: '#fff', flexShrink: 0 }}>
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="6" cy="5.5" r="2.4" />
                    <circle cx="6" cy="18.5" r="2.4" />
                    <circle cx="18" cy="12" r="2.4" />
                    <path d="M6 8v8" />
                    <path d="M6 8a7.5 7.5 0 0 0 7.5 4H15" />
                  </svg>
                </span>
                <span style={{ ...iconRowTextStyle, minWidth: undefined }}>
                  <span style={{ color: '#17191c', fontWeight: 400 }}>{item.author}</span> merged commit{item.sha !== null && <> <span style={shaStyle}>{item.sha}</span></>} into{' '}
                  <span style={{ ...shaStyle, color: '#0969da', background: '#ddf4ff', borderRadius: 5, padding: '1px 7px' }}>{item.base}</span> · {item.time}
                </span>
              </div>
            );
          case 'review':
            return (
              <ReviewCard
                key={item.id}
                item={item}
                repoCtx={repoCtx}
                threads={item.remoteId === null ? [] : reviewThreadsByRemoteId?.get(item.remoteId) ?? []}
                prAuthor={prAuthor}
                prHtmlUrl={prHtmlUrl}
                onThreadReply={onThreadReply}
                onThreadReact={onThreadReact}
                onThreadSetResolved={onThreadSetResolved}
                onOpenCommentLocation={onOpenCommentLocation}
              />
            );
          case 'review-activity':
            return (
              <div key={item.id} style={iconRowStyle}>
                <span style={{ width: 26, height: 26, borderRadius: '50%', background: '#f3e8ff', border: '2px solid #fff', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: '#8250df', flexShrink: 0 }}>
                  {item.activity === 'started' ? '◉' : '↻'}
                </span>
                <span style={iconRowTextStyle}>
                  <span style={{ color: '#17191c', fontWeight: 400 }}>{item.author}</span>{' '}
                  {item.activity === 'started'
                    ? 'started an adversarial code review'
                    : 'started addressing the adversarial review comments'}
                  {item.iteration !== null && ` · pass ${item.iteration}`} · {item.time}
                </span>
              </div>
            );
          case 'local-thread': {
            const root = item.comments[0];
            if (root === undefined || localPr === undefined) return null;
            const resolved = root.resolvedAt !== null || root.dismissedAt !== null;
            const findingBacked = root.author === 'agent' && root.findingId != null;
            const taskReviewClosed = localPr.origin === 'task' && localPr.status !== 'local-open';
            const statusLabel = resolved || taskReviewClosed
              ? undefined
              : root.author === 'brain'
                ? 'WAITING FOR DEV'
                : findingBacked
                  ? item.submitted ? 'WAITING FOR DEV' : 'PENDING REVIEW'
                : root.author === 'you'
                  ? item.submitted ? 'SENT TO DEV' : 'PENDING REVIEW'
                  : undefined;
            return (
              <div key={item.id} style={{ margin: '16px 0', marginLeft: 36 }}>
                <LocalReviewThreadCard
                  pr={localPr}
                  comments={item.comments}
                  compact
                  statusLabel={statusLabel}
                  currentUserLogin={currentUserLogin}
                  onReply={onLocalReply === undefined || resolved && root.author === 'brain'
                    ? undefined
                    : (_rootId, body) => onLocalReply(root, body)}
                  onSetResolved={(root.author === 'you' || root.author === 'brain' || findingBacked)
                      && (onLocalResolve !== undefined || onLocalReopen !== undefined)
                    ? next => { if (next) onLocalResolve?.(root.id); else onLocalReopen?.(root.id); }
                    : undefined}
                  onDismiss={!resolved && (root.author === 'you' || root.author === 'brain' || findingBacked)
                      && onLocalDismiss !== undefined
                    ? () => onLocalDismiss(root.id)
                    : undefined}
                  onSubmitToDev={!resolved && (root.author === 'you' || findingBacked) && !item.submitted
                      && onSubmitLocalReview !== undefined
                    ? () => onSubmitLocalReview([root.id])
                    : undefined}
                  onAskAgent={onAskAgentThread !== undefined ? () => onAskAgentThread(root) : undefined}
                  onOpenLocation={onOpenCommentLocation}
                />
              </div>
            );
          }
          case 'comment':
            return <CommentCard key={item.id} item={item} repoCtx={repoCtx} onReaction={onCommentReaction} />;
        }
      })}
    </>
  );
}
