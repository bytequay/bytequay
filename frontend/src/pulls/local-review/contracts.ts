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

import { flag, object, text } from '../../pulls/wireParse';
import type { LocalReviewMessageDto, LocalReviewThreadDto } from './types';

export type LocalReviewQuery = { taskId: string; workspaceId: string } | { repo: string; number: number; workspaceId?: string };
export type LocalReviewTarget = { taskId: string; workspaceId: string; threadId: string };
export type CreateDevelopmentLearningCommentRequest = {
  taskId: string; workspaceId: string; idempotencyKey: string;
  summaryTurnId: string; cardId: string; id: string; messageId: string; body: string; expectedTaskVersion: number;
};
export type CreateLocalReviewRequest = {
  taskId: string;
  workspaceId: string;
  idempotencyKey: string;
  id: string;
  messageId: string;
  repo: string;
  number: number;
  path: string;
  side: 'LEFT' | 'RIGHT';
  startLine: number | null;
  line: number;
  baseSha: string | null;
  headSha: string | null;
  code: string;
  body: string;
  expectedTaskVersion: number;
};
export type ReplyLocalReviewRequest = LocalReviewTarget & {
  idempotencyKey: string; messageId: string; body: string; expectedTaskVersion: number;
};
export type ResolveLocalReviewRequest = LocalReviewTarget & {
  idempotencyKey: string; resolved: boolean; expectedVersion: number;
};
export type RetryLocalReviewRequest = LocalReviewTarget & { messageId: string; expectedTaskVersion: number };

function optionalText(value: unknown): string | null { return value == null ? null : text(value); }
function count(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('Invalid local review number');
  return value;
}
function list<T>(value: unknown, parse: (entry: unknown) => T): T[] {
  if (!Array.isArray(value)) throw new Error('Invalid local review list');
  return value.map(parse);
}
function message(value: unknown): LocalReviewMessageDto {
  const row = object(value);
  const author = text(row.author);
  if (author !== 'USER' && author !== 'AGENT') throw new Error('Invalid local review author');
  return {
    id: text(row.id), author, body: text(row.body), createdAt: text(row.createdAt),
    turnId: optionalText(row.turnId), queuedInputId: optionalText(row.queuedInputId),
    messageEventId: optionalText(row.messageEventId), deliveryState: optionalText(row.deliveryState),
    deliveryError: optionalText(row.deliveryError),
  };
}
export function parseLocalReviewThread(value: unknown): LocalReviewThreadDto {
  const row = object(value);
  const side = text(row.side);
  if (side !== 'LEFT' && side !== 'RIGHT') throw new Error('Invalid local review side');
  const line = count(row.line);
  const startLine = row.startLine == null ? null : count(row.startLine);
  const anchor = row.learningAnchor == null ? null : object(row.learningAnchor);
  if (anchor === null && (line === 0 || (startLine !== null && (startLine === 0 || startLine > line)))) throw new Error('Invalid local review range');
  return {
    ...(row.learningAnchor === undefined ? {} : { learningAnchor: anchor === null ? null : {
      turnId: text(anchor.turnId), cardId: text(anchor.cardId), title: text(anchor.title), body: text(anchor.body),
    } }),
    id: text(row.id), taskId: text(row.taskId), taskVersion: count(row.taskVersion), workspaceId: text(row.workspaceId),
    repo: text(row.repo), number: count(row.number), path: text(row.path), side, startLine, line,
    baseSha: optionalText(row.baseSha), headSha: optionalText(row.headSha), code: text(row.code),
    resolved: flag(row.resolved), version: count(row.version),
    createdAt: text(row.createdAt), updatedAt: text(row.updatedAt), messages: list(row.messages, message),
  };
}
export function parseLocalReviewThreads(value: unknown): LocalReviewThreadDto[] { return list(value, parseLocalReviewThread); }
