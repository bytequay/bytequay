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
import type { LocalPRComment } from '../types/localPr';
import { presentFinding, type AgentFindingPresentation } from '../review/AgentEvidence';
import type { AgentReviewData } from '../review/agentReviewTypes';
import { QUICK_REVIEW_AUTHOR, workflowActorRole } from '../pr/localpr/prViewMeta';

/** "R42" for a single line, or "L40 to R42" for a multi-line range — shared
 *  by every diff-comment composer so the copy reads identically everywhere. */
export function rangeLabel(
  side: 'LEFT' | 'RIGHT', line: number, startLine?: number | null, startSide?: 'LEFT' | 'RIGHT' | null,
): string {
  const prefix = (s: 'LEFT' | 'RIGHT') => (s === 'LEFT' ? 'L' : 'R');
  if (startLine == null || startLine === line) return `${prefix(side)}${line}`;
  return `${prefix(startSide ?? side)}${startLine} to ${prefix(side)}${line}`;
}

export function commentLineLabel(c: Pick<DiffInlineComment, 'side' | 'lineNumber' | 'startLine' | 'startSide'>): string | null {
  if (c.lineNumber === null) return null;
  return rangeLabel(c.side, c.lineNumber, c.startLine, c.startSide);
}

export type DiffInlineComment = {
  id: string;
  filePath: string | null;
  lineNumber: number | null;
  side: 'LEFT' | 'RIGHT';
  startLine: number | null;
  startSide: 'LEFT' | 'RIGHT' | null;
  author: string;
  body: string;
  origin: 'local' | 'remote';
  parentCommentId: string | null;
  resolved: boolean;
  dismissed: boolean;
  pending?: boolean;
  sourceLabel?: string;
  /** Epoch ms — drives the relative-time chip. Omit to hide it. */
  createdAtMs?: number;
  finding?: AgentFindingPresentation;
};

/** Still-open local drafts that would be swept into the next submission — the
 *  set the Submit-review drawer's pending list and toolbar count show. */
export function isPendingLocalComment(c: LocalPRComment): boolean {
  return c.parentCommentId === null && c.origin === 'local' && c.publishedAt === null
    && c.strippedOnPushAt === null && c.resolvedAt === null && c.dismissedAt === null;
}

/** A user-owned or quick-review draft the backend can include in the next
 * review batch. The owning surface decides whether that batch goes to
 * Development (task Local Review) or GitHub (an external PR). */
export function isPublishableReviewDraft(c: LocalPRComment): boolean {
  const author = c.author.trim().toLowerCase();
  return isPendingLocalComment(c) && (author === 'you' || author === QUICK_REVIEW_AUTHOR);
}

export function diffInlineCommentFromLocalPr(c: LocalPRComment, reviewOrIndex?: AgentReviewData | number): DiffInlineComment {
  const review = typeof reviewOrIndex === 'number' ? undefined : reviewOrIndex;
  const role = c.origin === 'local'
    ? (c.findingId != null ? 'brain' : workflowActorRole(c.author))
    : null;
  return {
    id: c.id,
    filePath: c.filePath,
    lineNumber: c.lineNumber,
    side: c.side,
    startLine: c.startLine,
    startSide: c.startSide,
    author: role ?? c.author,
    body: c.body,
    origin: c.origin,
    parentCommentId: c.parentCommentId,
    resolved: c.resolvedAt !== null,
    dismissed: c.dismissedAt !== null,
    pending: isPendingLocalComment(c),
    sourceLabel: role?.toUpperCase(),
    createdAtMs: c.createdAt,
    finding: c.findingId == null || review === undefined ? undefined : presentFinding(review, c.findingId),
  };
}
