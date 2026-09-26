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

import { relativeTime } from '../../relativeTime';
import type { DiscussionMessageDto, TaskDiscussionDto } from './contracts';

/** The label budget, before the row's own pixel-width ellipsis. */
const BUDGET = 48;
/** Threads on one block stack; beyond this they are counted rather than listed. */
const STACK = 3;

/**
 * The reader-facing name of a discussion, derived from its quote.
 *
 * <p>Prose is quoted and cut at a word boundary; a bare code token is mono and unquoted, because it
 * is a name rather than a sentence; a quote with no prose to show becomes a typed label that says
 * what kind of thing was quoted instead of pretending.
 */
export function discussionLabel(discussion: TaskDiscussionDto, siblings: TaskDiscussionDto[]): { text: string; mono: boolean; typed: boolean } {
  const quote = discussion.quote.replace(/\s+/g, ' ').trim();
  const bare = quote.replace(/^`|`$/g, '');
  // A single token, code-shaped and unspaced: a name, so it keeps mono and loses the quote marks.
  if (!bare.includes(' ') && /[._#$/]|[a-z][A-Z]|^[A-Z0-9_]+$/.test(bare)) {
    const text = bare.length <= BUDGET ? bare : middle(bare);
    return { text: `${text}${mention(discussion, siblings)}`, mono: true, typed: false };
  }
  if (bare === '') return { text: 'Quoted passage', mono: true, typed: true };
  // A list marker is not part of what was said.
  const prose = bare.replace(/^(?:[-*+]|\d+[.)])\s+/, '');
  // Two passages on one answer can truncate to the same words; extend past where they diverge so
  // the two rows do not read as the same sentence twice.
  const plain = (other: { quote: string }): string => other.quote.replace(/\s+/g, ' ').trim().replace(/^(?:[-*+]|\d+[.)])\s+/, '');
  const clash = siblings.find(other => other.id !== discussion.id && cut(plain(other)) === cut(prose));
  const budget = clash === undefined ? BUDGET : divergence(prose, plain(clash));
  return { text: `“${cut(prose, budget)}”${mention(discussion, siblings)}`, mono: false, typed: false };
}

/**
 * Two threads on the same repeated text carry byte-identical quotes, which no amount of label can
 * separate. The transcript tells them apart by position, and only when their mentions fall in
 * different blocks; the panel has no position at all, so the mention is named.
 */
function mention(discussion: TaskDiscussionDto, siblings: TaskDiscussionDto[]): string {
  const same = siblings.some(other => other.id !== discussion.id
    && other.quote.replace(/\s+/g, ' ').trim() === discussion.quote.replace(/\s+/g, ' ').trim());
  return same ? ` \u00b7 ${ordinal(discussion.occurrence)} mention` : '';
}

function ordinal(value: number): string {
  const tens = value % 100;
  if (tens >= 11 && tens <= 13) return `${value}th`;
  return `${value}${['th', 'st', 'nd', 'rd'][value % 10] ?? 'th'}`;
}

/** How much of a passage must be shown before it stops reading as another one. */
function divergence(left: string, right: string): number {
  const words = left.split(' ');
  let at = 0;
  for (let index = 0; index < words.length; index++) {
    at += words[index].length + (index === 0 ? 0 : 1);
    // One past the diverging word, so cut's own word-boundary search cannot trim it back off.
    if (!right.startsWith(left.slice(0, at))) return Math.max(BUDGET, at + 1);
  }
  return BUDGET;
}

/** Cuts at the last word boundary under budget, dropping trailing punctuation. */
function cut(text: string, budget = BUDGET): string {
  const clean = text.replace(/[.,;:!?]+$/, '');
  if (clean.length <= budget) return clean;
  const head = clean.slice(0, budget);
  const boundary = head.lastIndexOf(' ');
  return `${(boundary > 0 ? head.slice(0, boundary) : head).replace(/[.,;:!?]+$/, '')}…`;
}

/** One token over budget keeps its head and its tail; a left cut loses what identifies it. */
function middle(token: string): string {
  const keep = Math.floor((BUDGET - 1) / 2);
  return `${token.slice(0, keep)}…${token.slice(-keep)}`;
}

/** Completed answers, which is what a reader counts as replies. */
export function replyCount(discussion: TaskDiscussionDto): number {
  return discussion.messages.filter(message => message.author === 'AGENT').length;
}

/** The question Retry re-runs: a trailing question that failed, or one orphaned by a restart. */
export function retryTarget(discussion: TaskDiscussionDto): DiscussionMessageDto | null {
  const last = discussion.messages.at(-1);
  return last !== undefined && last.author === 'USER' && (last.error !== null || !discussion.pending) ? last : null;
}

function failed(discussion: TaskDiscussionDto): boolean {
  return retryTarget(discussion) !== null && !discussion.pending;
}

/**
 * The reply-count rows under one block of an answer.
 *
 * <p>They are the quietest rows on the screen: no border, no background at rest, tucked into the
 * paragraph gap. The count carries the state, so a thread waiting on an answer and one that could
 * not be answered keep the row's height and drop the age, which is less useful than either.
 */
export function DiscussionFooter({ discussions, siblings, focused, unanchored, onOpen, onHover }: {
  discussions: TaskDiscussionDto[];
  siblings: TaskDiscussionDto[];
  focused?: string | null;
  /** The passage spans more than one block, so these sit at the end of the answer instead. */
  unanchored?: boolean;
  onOpen?: (discussionId: string) => void;
  onHover?: (discussionId: string | null) => void;
}): React.ReactElement | null {
  if (discussions.length === 0) return null;
  const shown = discussions.slice(0, STACK);
  const rest = discussions.length - shown.length;
  return (
    <div className={`agt-disc-rows${unanchored === true ? ' is-unanchored' : ''}`}>
      {shown.map(discussion => {
        const label = discussionLabel(discussion, siblings);
        const done = discussion.resolved;
        const broken = failed(discussion);
        const replies = replyCount(discussion);
        return (
          <button
            className={`agt-disc-row${done ? ' is-done' : ''}${broken ? ' is-failed' : ''}${focused === discussion.id ? ' is-focused' : ''}`}
            key={discussion.id}
            type="button"
            data-discussion={discussion.id}
            aria-current={focused === discussion.id}
            title={unanchored === true ? 'This passage spans more than one block' : undefined}
            onMouseEnter={() => onHover?.(discussion.id)}
            onMouseLeave={() => onHover?.(null)}
            onFocus={() => onHover?.(discussion.id)}
            onBlur={() => onHover?.(null)}
            onClick={() => onOpen?.(discussion.id)}
          >
            <span className="agt-disc-row__glyph">
              <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                {done
                  ? <path d="m3.5 8.5 3 3 6-7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
                  : <path d="M2.5 3.6h11v7.2H7l-3.2 2.4v-2.4H2.5z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />}
              </svg>
            </span>
            <span className={`agt-disc-row__label${label.mono ? ' is-mono' : ''}${label.typed ? ' is-typed' : ''}`}>{label.text}</span>
            <span className="agt-disc-row__count">
              {discussion.pending ? 'answering…' : broken ? 'couldn’t answer' : replies === 1 ? '1 reply' : `${replies} replies`}
            </span>
            <span className="agt-disc-row__time">
              {discussion.pending || broken ? '' : `· ${relativeTime(discussion.updatedAt, { suffix: false })}`}
            </span>
          </button>
        );
      })}
      {rest > 0 && <span className="agt-disc-rows__more">{`+${rest} more in Discussions`}</span>}
    </div>
  );
}
