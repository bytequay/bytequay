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
import { count, flag, object, text } from '../../pulls/wireParse';


/** One discussion as the backend projects it. The seed and provider session are not sent. */
export type TaskDiscussionDto = {
  id: string;
  taskId: string;
  turnId: string;
  quote: string;
  occurrence: number;
  resolved: boolean;
  pending: boolean;
  providerLane: string;
  messages: DiscussionMessageDto[];
  version: number;
  createdAt: string;
  updatedAt: string;
  /** The GitHub comment a private thread is about; null on a passage discussion. */
  comment: DiscussionCommentDto | null;
  replyDraft: string | null;
  replyDraftError: string | null;
};

export type DiscussionCommentDto = { repo: string; number: number; key: string };

export type DiscussionMessageDto = {
  id: string;
  author: 'USER' | 'AGENT';
  body: string;
  createdAt: string;
  error: string | null;
};

export type DiscussionQuery = { taskId: string; workspaceId: string };
export type OpenDiscussionRequest = DiscussionQuery & {
  turnId: string; quote: string; occurrence: number; question: string;
};
export type AskDiscussionRequest = DiscussionQuery & { discussionId: string; question: string };
export type ResolveDiscussionRequest = DiscussionQuery & { discussionId: string; resolved: boolean; expectedVersion: number };
export type RetryDiscussionRequest = DiscussionQuery & { discussionId: string; messageId: string };
export type AskCommentDiscussionRequest = DiscussionQuery & { repo: string; number: number; key: string; question: string };
export type DraftDiscussionReplyRequest = DiscussionQuery & { discussionId: string };

/** Why a question cannot be sent while another discussion on the task is being answered. */
export const ANSWERING_ELSEWHERE = 'The agent is answering another question on this task';

/** Mirrors DiscussionPrompt.SUMMARY_REQUEST: the hidden question a private thread's summary answers. */
export const SUMMARY_REQUEST = 'Summarize this review comment.';

export function isSummaryRequest(message: DiscussionMessageDto): boolean {
  return message.author === 'USER' && message.body === SUMMARY_REQUEST;
}

export type CommentSummaryDto = {
  ask: string;
  valid: 'yes' | 'partly' | 'no' | 'unsure';
  why: string;
  do: 'yes' | 'no' | 'your_call';
  plan: string;
  size: '' | 'trivial' | 'small' | 'big';
};

const SUMMARY_VALUES: { [K in keyof CommentSummaryDto]: readonly string[] | null } = {
  ask: null, valid: ['yes', 'partly', 'no', 'unsure'], why: null, do: ['yes', 'no', 'your_call'], plan: null, size: ['', 'trivial', 'small', 'big'],
};

/** The verdict the backend stored as an agent message, or null when the body is anything else. */
export function parseCommentSummary(body: string): CommentSummaryDto | null {
  let value: unknown;
  try {
    value = JSON.parse(body);
  }
  catch {
    return null;
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null;
  const row = value as Record<string, unknown>;
  const keys = Object.keys(SUMMARY_VALUES) as (keyof CommentSummaryDto)[];
  if (Object.keys(row).length !== keys.length) return null;
  for (const key of keys) {
    const field = row[key];
    const allowed = SUMMARY_VALUES[key];
    if (typeof field !== 'string' || (allowed !== null && !allowed.includes(field))) return null;
  }
  return row as CommentSummaryDto;
}

export function parseDiscussionReplyDraft(value: unknown): string {
  return text(object(value).text);
}

function comment(value: unknown): DiscussionCommentDto | null {
  if (value == null) return null;
  const row = object(value);
  return { repo: text(row.repo), number: count(row.number), key: text(row.key) };
}
function message(value: unknown): DiscussionMessageDto {
  const row = object(value);
  const author = text(row.author);
  if (author !== 'USER' && author !== 'AGENT') throw new Error('Invalid discussion author');
  return {
    id: text(row.id),
    author,
    body: text(row.body),
    createdAt: text(row.createdAt),
    error: row.error == null ? null : text(row.error),
  };
}

export function parseTaskDiscussion(value: unknown): TaskDiscussionDto {
  const row = object(value);
  if (!Array.isArray(row.messages)) throw new Error('Invalid discussion messages');
  return {
    id: text(row.id),
    taskId: text(row.taskId),
    turnId: text(row.turnId),
    quote: text(row.quote),
    occurrence: count(row.occurrence),
    resolved: flag(row.resolved),
    pending: flag(row.pending),
    providerLane: text(row.providerLane),
    messages: row.messages.map(message),
    version: count(row.version),
    createdAt: text(row.createdAt),
    updatedAt: text(row.updatedAt),
    comment: comment(row.comment),
    replyDraft: row.replyDraft == null ? null : text(row.replyDraft),
    replyDraftError: row.replyDraftError == null ? null : text(row.replyDraftError),
  };
}

export function parseTaskDiscussions(value: unknown): TaskDiscussionDto[] {
  if (!Array.isArray(value)) throw new Error('Invalid discussion list');
  return value.map(parseTaskDiscussion);
}
