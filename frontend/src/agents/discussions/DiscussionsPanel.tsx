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


import React, { useEffect, useState } from 'react';

import { discussionLabel, replyCount, retryTarget } from './DiscussionFooter';
import { relativeTime } from '../../relativeTime';
import { renderMarkdown } from '../markdown';
import { ANSWERING_ELSEWHERE, type DiscussionMessageDto, type TaskDiscussionDto } from './contracts';
import type { TaskDiscussions } from './useTaskDiscussions';

const JUMP = (
  <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <path d="M13 3 6 10M13 3H8.6M13 3v4.4M12 10.5v2a.5.5 0 0 1-.5.5h-8a.5.5 0 0 1-.5-.5v-8a.5.5 0 0 1 .5-.5h2"
      stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);

const LOCK = (
  <svg width="11" height="11" viewBox="0 0 16 16" fill="none" aria-hidden="true" style={{ flex: 'none' }}>
    <rect x="3.5" y="7" width="9" height="6" rx="1.5" stroke="currentColor" strokeWidth="1.4" />
    <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" stroke="currentColor" strokeWidth="1.4" />
  </svg>
);

function status(discussion: TaskDiscussionDto): { label: string; kind: string; dot: boolean } | null {
  if (discussion.pending) return { label: 'Waiting', kind: 'is-waiting', dot: true };
  if (retryTarget(discussion) !== null) return { label: 'Failed', kind: 'is-failed', dot: false };
  if (discussion.resolved) return { label: 'Done', kind: 'is-done', dot: false };
  return null;
}

/** One question or one answer, with the speaker its rail and avatar name. */
function Message({ message, lane, repository }: { message: DiscussionMessageDto; lane: string; repository?: string | null }): React.ReactElement {
  const reader = message.author === 'USER';
  return (
    <div className={`agt-disc-msg${reader ? ' is-reader' : ''}`}>
      <span className="agt-disc-msg__avatar">{reader ? 'Y' : 'A'}</span>
      <div className="agt-disc-msg__main">
        <div className="agt-disc-msg__byline">
          <span>{reader ? 'You' : 'Agent'}</span>
          {reader ? ` · ${relativeTime(message.createdAt)}` : ` · ${lane} · read-only`}
        </div>
        <div className="agt-disc-msg__body">{renderMarkdown(message.body, repository)}</div>
      </div>
    </div>
  );
}

/**
 * One discussion in the panel.
 *
 * <p>The quote is the card's subject and stays pinned at the top when the card opens, because the
 * thread is about that passage and the passage may be far away in the transcript.
 */
export function DiscussionCard({ discussion, repository, siblings, discussions, open, onToggle, onJump, onHover }: {
  discussion: TaskDiscussionDto;
  /** `owner/repo` a bare `#1234` in the thread refers to. */
  repository?: string | null;
  siblings: TaskDiscussionDto[];
  discussions: TaskDiscussions;
  open: boolean;
  onToggle: () => void;
  /** Shows the passage this thread is about, or the whole answer when no thread is named. */
  onJump?: (target: { turnId: string; discussionId?: string }) => void;
  onHover?: (discussionId: string | null) => void;
}): React.ReactElement {
  const [draft, setDraft] = useState('');
  const [replying, setReplying] = useState(false);
  const [busy, setBusy] = useState(false);
  const readOnly = discussions.disabledReason;
  const pill = status(discussion);
  const label = discussionLabel(discussion, siblings);
  const replies = replyCount(discussion);
  const failure = discussion.pending ? null : retryTarget(discussion);
  const blocked = discussions.busy ? ANSWERING_ELSEWHERE : undefined;
  const asked = discussion.messages.find(message => message.author === 'USER');

  const send = async (): Promise<void> => {
    const question = draft.trim();
    if (question === '' || busy || discussions.busy) return;
    setBusy(true);
    try {
      await discussions.ask(discussion.id, question);
      setDraft('');
      setReplying(false);
    }
    finally {
      setBusy(false);
    }
  };

  return (
    <div
      className={`agt-disc-card${open ? ' is-open' : ''}${discussion.resolved ? ' is-done' : ''}`}
      data-discussion={discussion.id}
      onMouseEnter={() => onHover?.(discussion.id)}
      onMouseLeave={() => onHover?.(null)}
    >
      <button className="agt-disc-card__head" type="button" aria-expanded={open} onClick={onToggle}>
        <span className="agt-disc-card__rail" />
        <span className="agt-disc-card__main">
          <span className={`agt-disc-card__quote${label.mono ? ' is-mono' : ''}`}>{discussion.quote}</span>
          <span className="agt-disc-card__meta">
            <span className="agt-disc-card__who">
              {asked === undefined ? 'You' : `You · ${relativeTime(asked.createdAt)}`}
            </span>
            {pill !== null && (
              <span className={`agt-disc-pill-tag ${pill.kind}`}>
                {pill.dot && <span className="agt-disc-pill-tag__dot" />}
                {pill.label}
              </span>
            )}
            <span className="agt-disc-card__spacer" />
            <span className="agt-disc-card__count">{replies === 1 ? '1 reply' : `${replies} replies`}</span>
          </span>
          {!open && discussion.messages.length > 0 && (
            <span className="agt-disc-card__preview">{discussion.messages[discussion.messages.length - 1].body}</span>
          )}
        </span>
      </button>

      {open && (
        <div className="agt-disc-card__body">
          <div className="agt-disc-card__messages">
            {discussion.messages.map(message => (
              <Message key={message.id} message={message} lane={discussion.providerLane} repository={repository} />
            ))}
            {discussion.pending && (
              <div className="agt-disc-msg">
                <span className="agt-disc-msg__avatar">A</span>
                <div className="agt-disc-msg__main">
                  <div className="agt-disc-msg__byline"><span>Agent</span>{` · ${discussion.providerLane} · read-only`}</div>
                  <div className="agt-disc-msg__shimmer">Reading the task history and the repository…</div>
                </div>
              </div>
            )}
          </div>

          {failure !== null && (
            <div className="agt-disc-fail" role="alert">
              <span>{failure.error ?? 'The answer was interrupted.'}</span>
              <button type="button" disabled={busy || readOnly !== null || discussions.busy} title={blocked}
                onClick={() => { void discussions.retry(discussion.id, failure.id); }}>Retry</button>
            </div>
          )}

          {readOnly !== null ? <p className="agt-disc-card__closed">{readOnly}</p>
            : replying ? (
              <div className="agt-disc-reply">
                <textarea
                  rows={2}
                  autoFocus
                  aria-label="Reply"
                  placeholder="Reply…"
                  value={draft}
                  disabled={busy || discussion.pending}
                  onChange={event => setDraft(event.target.value)}
                  onKeyDown={event => {
                    if (event.key === 'Escape') { setDraft(''); setReplying(false); }
                    else if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) void send();
                  }}
                />
                <div className="agt-disc-reply__foot">
                  <span className="agt-disc-reply__private">{LOCK}Private · reads code, never writes</span>
                  <button className="agt-disc-reply__cancel" type="button" onClick={() => { setDraft(''); setReplying(false); }}>Cancel</button>
                  <button className="agt-disc-reply__ask" type="button" disabled={busy || discussions.busy || draft.trim() === ''}
                    title={blocked} onClick={() => { void send(); }}>Ask</button>
                </div>
              </div>
            ) : (
              <div className="agt-disc-card__actions">
                {!discussion.resolved && <button className="agt-disc-card__reply" type="button" onClick={() => setReplying(true)}>Reply</button>}
                <button className="agt-disc-card__done" type="button" disabled={busy}
                  onClick={() => { void discussions.resolve(discussion.id, !discussion.resolved, discussion.version); }}>
                  {discussion.resolved ? 'Reopen' : 'Mark done'}
                </button>
                <span className="agt-disc-card__spacer" />
                {onJump !== undefined && (
                  <button className="agt-disc-card__jump" type="button" title="Show passage in transcript"
                    aria-label="Show passage in transcript"
                    onClick={() => onJump({ turnId: discussion.turnId, discussionId: discussion.id })}>{JUMP}</button>
                )}
              </div>
            )}
        </div>
      )}
    </div>
  );
}

/**
 * Every discussion on the task, grouped by the answer it hangs under.
 *
 * <p>Groups run in transcript order and passages within a group in document order, not the order
 * the questions were asked, so the panel reads as a map of the answer rather than a log.
 */
export function DiscussionsPanel({ discussions, repository, turns, focus, onJump, onHover }: {
  discussions: TaskDiscussions;
  /** `owner/repo` a bare `#1234` in a thread refers to. */
  repository?: string | null;
  /** Turn ordinals and synopses for the answers on screen, for the sticky group headers. */
  turns?: { turnId: string; ordinal: number; synopsis: string }[];
  /**
   * The discussion a footer asked to see. It is a fresh object per request, so asking for the same
   * one twice opens it again after the reader has folded it.
   */
  focus?: { id: string } | null;
  onJump?: (target: { turnId: string; discussionId?: string }) => void;
  onHover?: (discussionId: string | null) => void;
}): React.ReactElement {
  const [openId, setOpenId] = useState<string | null>(focus?.id ?? null);
  const [asked, setAsked] = useState(focus);
  if (focus !== asked) {
    setAsked(focus);
    if (focus != null) setOpenId(focus.id);
  }
  useEffect(() => {
    if (openId === null) return;
    // The card a footer points at is usually further down the list than the passage it hangs under.
    document.querySelector(`.agt-disc-card[data-discussion="${CSS.escape(openId)}"]`)?.scrollIntoView({ block: 'nearest' });
  }, [openId]);
  const rows = discussions.discussions;
  if (rows.length === 0) {
    return (
      <div className="agt-disc-empty">
        <p className="agt-disc-empty__title">No discussions yet</p>
        <p className="agt-disc-empty__body">
          Highlight text in a finished answer to ask about it. A discussion stays beside the task — it
          never enters the conversation, and the agent answering it can read the repository but not
          change it.
        </p>
      </div>
    );
  }

  const order = new Map((turns ?? []).map((turn, index) => [turn.turnId, index]));
  const byTurn = new Map<string, TaskDiscussionDto[]>();
  for (const discussion of rows) byTurn.set(discussion.turnId, [...byTurn.get(discussion.turnId) ?? [], discussion]);
  const groups = [...byTurn.entries()]
    .sort(([left], [right]) => (order.get(left) ?? Number.MAX_SAFE_INTEGER) - (order.get(right) ?? Number.MAX_SAFE_INTEGER));

  return (
    <div className="agt-disc agt-disc-list">
      {discussions.error !== null && <div className="agt-panel__empty" role="alert">{discussions.error}</div>}
      {groups.map(([turnId, cards]) => {
        const turn = (turns ?? []).find(candidate => candidate.turnId === turnId);
        return (
          <div className="agt-disc-group" key={turnId}>
            {turn !== undefined && (
              <div className="agt-disc-group__head">
                <span className="agt-disc-group__turn">TURN {turn.ordinal}</span>
                <span className="agt-disc-group__synopsis">{turn.synopsis}</span>
                {onJump !== undefined && (
                  <button className="agt-disc-group__jump" type="button" title="Show answer in transcript"
                    aria-label="Show answer in transcript" onClick={() => onJump({ turnId })}>{JUMP}</button>
                )}
              </div>
            )}
            <div className="agt-disc-group__cards">
              {cards.map(discussion => (
                <DiscussionCard
                  key={discussion.id}
                  discussion={discussion}
                  repository={repository}
                  siblings={rows}
                  discussions={discussions}
                  open={openId === discussion.id}
                  onToggle={() => setOpenId(current => (current === discussion.id ? null : discussion.id))}
                  onJump={turn === undefined ? undefined : onJump}
                  onHover={onHover}
                />
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}
