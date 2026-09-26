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
};

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

/** Why a question cannot be sent while another discussion on the task is being answered. */
export const ANSWERING_ELSEWHERE = 'The agent is answering another question on this task';

function object(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('Invalid discussion');
  return value as Record<string, unknown>;
}
function text(value: unknown): string {
  if (typeof value !== 'string') throw new Error('Invalid discussion text');
  return value;
}
function count(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error('Invalid discussion number');
  return value;
}
function flag(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new Error('Invalid discussion flag');
  return value;
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
  };
}

export function parseTaskDiscussions(value: unknown): TaskDiscussionDto[] {
  if (!Array.isArray(value)) throw new Error('Invalid discussion list');
  return value.map(parseTaskDiscussion);
}
