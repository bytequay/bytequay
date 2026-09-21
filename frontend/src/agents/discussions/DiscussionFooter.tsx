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


import React from 'react';

import type { TaskDiscussionDto } from './contracts';

/** How long a label may run before it is cut; the row truncates in CSS beyond this. */
const MAXIMUM_LABEL = 48;

/**
 * The reader-facing name of a discussion, derived rather than stored.
 *
 * <p>Two threads on the same repeated text carry byte-identical quotes, and the transcript only
 * separates them when their mentions fall in different blocks. When a sibling shares the label the
 * mention ordinal is appended, so the panel — which has no position at all — can still tell them
 * apart.
 */
export function discussionLabel(discussion: TaskDiscussionDto, siblings: TaskDiscussionDto[]): string {
  const quote = discussion.quote.replace(/\s+/g, ' ').trim();
  const label = quote.length <= MAXIMUM_LABEL ? quote : `${quote.slice(0, MAXIMUM_LABEL).trimEnd()}…`;
  const shared = siblings.some(other => other.id !== discussion.id
    && other.quote.replace(/\s+/g, ' ').trim() === quote);
  return shared ? `${label} · ${ordinal(discussion.occurrence)} mention` : label;
}

function ordinal(value: number): string {
  const tens = value % 100;
  if (tens >= 11 && tens <= 13) return `${value}th`;
  return `${value}${['th', 'st', 'nd', 'rd'][value % 10] ?? 'th'}`;
}

/** Completed answers, which is what the reader counts as replies. */
export function replyCount(discussion: TaskDiscussionDto): number {
  return discussion.messages.filter(message => message.author === 'AGENT').length;
}

/**
 * The reply-count rows under one block of an answer.
 *
 * <p>A row whose newest question has no answer yet reads as answering rather than showing a count,
 * because a count of zero and a question in flight are different things to a reader.
 */
export function DiscussionFooter({ discussions, siblings, unanchored, onOpen }: {
  discussions: TaskDiscussionDto[];
  siblings: TaskDiscussionDto[];
  /** The passage spans more than one block, so this row sits at the end of the answer instead. */
  unanchored?: boolean;
  onOpen?: (discussionId: string) => void;
}): React.ReactElement | null {
  if (discussions.length === 0) return null;
  return (
    <div className={`agt-disc-footer${unanchored === true ? ' is-unanchored' : ''}`}>
      {discussions.map(discussion => {
        const replies = replyCount(discussion);
        return (
          <button
            className="agt-disc-footer__row"
            key={discussion.id}
            type="button"
            data-discussion={discussion.id}
            title={unanchored === true ? 'This passage spans more than one block' : undefined}
            onClick={onOpen === undefined ? undefined : () => onOpen(discussion.id)}
          >
            {discussion.resolved && <span aria-hidden="true" className="agt-disc-footer__done">✓</span>}
            <span className="agt-disc-footer__label">{discussionLabel(discussion, siblings)}</span>
            <span className="agt-disc-footer__count">
              {discussion.pending ? 'answering…' : replies === 1 ? '1 reply' : `${replies} replies`}
            </span>
          </button>
        );
      })}
    </div>
  );
}
