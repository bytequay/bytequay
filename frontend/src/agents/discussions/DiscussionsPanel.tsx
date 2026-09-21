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


import React, { useState } from 'react';

import { discussionLabel, replyCount } from './DiscussionFooter';
import { renderMarkdown } from '../markdown';
import type { SelectedPassage } from './DiscussableAnswer';
import type { TaskDiscussionDto } from './contracts';
import type { TaskDiscussions } from './useTaskDiscussions';

/**
 * One discussion, read and replied to in the side panel.
 *
 * <p>It reads as a sibling of the private local-discussion card rather than a new vocabulary: the
 * quoted passage, the exchange, a collapsed reply, and a failed question carrying its reason beside
 * a retry.
 */
export function DiscussionCard({ discussion, siblings, discussions, onJump }: {
  discussion: TaskDiscussionDto;
  siblings: TaskDiscussionDto[];
  discussions: TaskDiscussions;
  onJump?: (turnId: string) => void;
}): React.ReactElement {
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [replying, setReplying] = useState(false);
  const disabled = discussions.disabledReason;
  const failed = discussion.messages.findLast(message => message.error !== null);

  const send = async (): Promise<void> => {
    const question = draft.trim();
    if (question === '' || busy) return;
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
    <details className={`agt-disc-card${discussion.resolved ? ' is-resolved' : ''}`} data-discussion={discussion.id} open={!discussion.resolved}>
      <summary>
        <span className="agt-disc-card__label">{discussionLabel(discussion, siblings)}</span>
        <span className="agt-disc-card__count">
          {discussion.pending ? 'answering…' : replyCount(discussion) === 1 ? '1 reply' : `${replyCount(discussion)} replies`}
        </span>
        {discussion.resolved && <span className="agt-disc-card__pill">Done</span>}
      </summary>
      <blockquote className="agt-disc-card__quote">{discussion.quote}</blockquote>
      {discussion.messages.map(message => (
        <div className={`agt-disc-msg is-${message.author.toLowerCase()}`} key={message.id}>
          <span className="agt-disc-msg__who">{message.author === 'USER' ? 'You' : 'Agent'}</span>
          <div className="agt-disc-msg__body">{renderMarkdown(message.body)}</div>
          {message.error !== null && (
            <div className="agt-disc-msg__error" role="alert">
              {message.error}
              <button type="button" disabled={busy || disabled !== null} onClick={() => { void discussions.retry(discussion.id, message.id); }}>Retry</button>
            </div>
          )}
        </div>
      ))}
      <div className="agt-disc-card__actions">
        {onJump !== undefined && <button type="button" onClick={() => onJump(discussion.turnId)}>Show the passage</button>}
        <button
          type="button"
          onClick={() => { void discussions.resolve(discussion.id, !discussion.resolved, discussion.version); }}
          disabled={busy}
        >{discussion.resolved ? 'Reopen' : 'Mark done'}</button>
      </div>
      {disabled !== null ? <p className="agt-disc-card__closed">{disabled}</p>
        : failed !== undefined && !replying ? null
        : replying ? (
          <div className="agt-disc-card__composer">
            <textarea
              aria-label="Ask a follow-up"
              value={draft}
              disabled={busy || discussion.pending}
              onChange={event => setDraft(event.target.value)}
            />
            <button type="button" disabled={busy || discussion.pending || draft.trim() === ''} onClick={() => { void send(); }}>
              {discussion.pending ? 'Waiting for the answer' : 'Ask'}
            </button>
          </div>
        ) : <button className="agt-disc-card__reply" type="button" onClick={() => setReplying(true)}>Reply</button>}
    </details>
  );
}

/**
 * The passage the reader highlighted, waiting for its first question. A discussion cannot exist
 * without one, so the composer is where a selection becomes a thread.
 */
function NewDiscussion({ passage, discussions, onDone }: {
  passage: SelectedPassage;
  discussions: TaskDiscussions;
  onDone: () => void;
}): React.ReactElement {
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const ask = async (): Promise<void> => {
    const question = draft.trim();
    if (question === '' || busy) return;
    setBusy(true);
    try {
      await discussions.open({ ...passage, question });
      onDone();
    }
    finally {
      setBusy(false);
    }
  };
  return (
    <div className="agt-disc-card agt-disc-card--new">
      <blockquote className="agt-disc-card__quote">{passage.quote}</blockquote>
      <div className="agt-disc-card__composer">
        <textarea aria-label="Ask about this passage" autoFocus value={draft} disabled={busy}
          onChange={event => setDraft(event.target.value)} />
        <button type="button" disabled={busy || draft.trim() === ''} onClick={() => { void ask(); }}>Ask</button>
        <button type="button" disabled={busy} onClick={onDone}>Cancel</button>
      </div>
    </div>
  );
}

/** Every discussion on the task, in the order their passages appear in the transcript. */
export function DiscussionsPanel({ discussions, pending, onPendingDone, onJump }: {
  discussions: TaskDiscussions;
  pending?: SelectedPassage | null;
  onPendingDone?: () => void;
  onJump?: (turnId: string) => void;
}): React.ReactElement {
  const rows = discussions.discussions;
  const composer = pending == null || onPendingDone === undefined ? null
    : <NewDiscussion passage={pending} discussions={discussions} onDone={onPendingDone} />;
  if (rows.length === 0 && composer === null) {
    return (
      <p className="agr-card__empty">
        Highlight part of an answer and ask about it. The thread stays here, and the task&apos;s own
        conversation is untouched.
      </p>
    );
  }
  return (
    <div className="agt-disc-list">
      {discussions.error !== null && <div className="agt-panel__empty" role="alert">{discussions.error}</div>}
      {composer}
      {rows.map(discussion => (
        <DiscussionCard key={discussion.id} discussion={discussion} siblings={rows} discussions={discussions} onJump={onJump} />
      ))}
    </div>
  );
}
