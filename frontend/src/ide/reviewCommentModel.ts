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
import type { IdeContext } from './contracts';
import type { IdeRuntime } from './runtime';
import type { ReviewCodeFilesDto } from '../pulls/code-tests/contracts';
import { findingInResults, findingPill, messagesFor } from '../pulls/agent-review/model';

export type ReviewCommentTarget = { path: string; start: number | null; end: number | null; revision: string | null };
export type ReviewCommentCard = {
  id: string;
  path: string | null;
  location: string;
  kind: 'Agent' | 'GitHub' | 'Private' | 'Pending';
  status: string;
  resolved: boolean;
  outdated: boolean;
  title?: string;
  messages: { author: string; body: string; timestamp: string | null }[];
  target: ReviewCommentTarget | null;
};

const location = (path: string | null, side: string | null, start: number | null, end: number | null) => {
  if (!path) return 'Whole change';
  const prefix = side === 'LEFT' ? 'L' : 'R';
  return end === null ? path : `${path}:${prefix}${start ?? end}${start !== null && start !== end ? `–${prefix}${end}` : ''}`;
};

export function reviewCommentCards({ review, comments }: Pick<IdeContext, 'review' | 'comments'>): ReviewCommentCard[] {
  const cards: ReviewCommentCard[] = [];
  const threadRoots = new Set<number>();
  const user = comments?.viewer?.login ?? 'You';
  for (const finding of review?.findings ?? []) {
    if (!review || finding.state === 'WITHDRAWN' || !findingInResults(review, finding)) continue;
    const revision = finding.side === 'LEFT' ? review.runs.find(run => run.id === finding.runId)?.mergeBaseSha ?? null : finding.validatedHeadSha;
    cards.push({ id: `finding:${finding.id}`, path: finding.path, location: location(finding.path, finding.side, finding.startLine, finding.endLine),
      kind: 'Agent', status: findingPill(review, finding).text, resolved: finding.state === 'RESOLVED', outdated: finding.outdated, title: finding.title,
      messages: [{ author: 'Reviewer', body: finding.body, timestamp: finding.updatedAt },
        ...messagesFor(review, finding.id).map(message => ({ author: message.author === 'USER' ? user : 'Reviewer', body: message.body, timestamp: message.createdAt }))],
      target: finding.path === null ? null : { path: finding.path, start: revision ? finding.startLine : null, end: revision ? finding.endLine : null, revision } });
  }
  for (const activity of comments?.threads.activities ?? []) {
    if (activity.eventType !== 'review_thread') continue;
    const payload = activity.payload;
    const root = payload.comments.find(comment => comment.inReplyToId === null) ?? payload.comments[0];
    if (root) threadRoots.add(root.githubId);
    const current = !payload.outdated && payload.side === 'RIGHT';
    cards.push({ id: `thread:${activity.githubId}`, path: payload.path,
      location: location(payload.path, payload.side, payload.line === null ? payload.originalStartLine : payload.startLine, payload.line ?? payload.originalLine),
      kind: 'GitHub', status: '', resolved: payload.resolved, outdated: payload.outdated,
      messages: [...payload.comments.map(comment => ({ author: comment.actor ?? 'GitHub', body: comment.body ?? '', timestamp: comment.timestamp })),
        ...(comments?.pending.filter(comment => root && comment.replyToCommentId === root.githubId) ?? [])
          .map((comment): ReviewCommentCard['messages'][number] => ({ author: `${user} · Pending`, body: comment.body, timestamp: null }))],
      // GitHub's thread payload has no original commit. Old-side and outdated threads open the file without inventing a current line.
      target: payload.path === null ? null : { path: payload.path, start: current ? payload.startLine : null, end: current ? payload.line : null,
        revision: current ? review?.observedHeadSha ?? null : null } });
  }
  for (const thread of comments?.localReview.threads ?? []) {
    const revision = thread.side === 'LEFT' ? thread.baseSha : thread.headSha;
    cards.push({ id: `local:${thread.id}`, path: thread.path || null, location: location(thread.path, thread.side, thread.startLine, thread.line),
      kind: 'Private', status: '', resolved: thread.resolved, outdated: false,
      messages: thread.messages.map(message => ({ author: message.author === 'USER' ? user : 'Agent', body: message.body, timestamp: message.createdAt })),
      target: thread.path ? { path: thread.path, start: revision ? thread.startLine : null, end: revision ? thread.line : null, revision } : null });
  }
  for (const [index, comment] of (comments?.pending ?? []).entries()) {
    if (comment.replyToCommentId != null && threadRoots.has(comment.replyToCommentId)) continue;
    const revision = comment.side === 'LEFT' ? review?.runs.find(run => run.headSha === comment.commitId)?.mergeBaseSha ?? null : comment.commitId;
    cards.push({ id: `pending:${index}`, path: comment.path, location: location(comment.path, comment.side, comment.startLine, comment.line),
      kind: 'Pending', status: 'Not posted', resolved: false, outdated: false,
      messages: [{ author: user, body: comment.body, timestamp: null }],
      target: { path: comment.path, start: revision ? comment.startLine : null, end: revision ? comment.line : null, revision } });
  }
  return cards;
}

export async function openReviewComment(runtime: IdeRuntime, target: ReviewCommentTarget, files: ReviewCodeFilesDto | null) {
  if (target.path.startsWith('/') || target.path.includes('\\') || target.path.split('/').some(part => !part || part === '..' || part === '.' || part === '.git')) throw new Error('Invalid workspace path');
  const revision = target.revision ?? (target.end === null ? null : files?.revision ?? null);
  const local = runtime.workspace.with({ path: `${runtime.workspace.path}/${target.path}` });
  const dirty = files?.files.some(file => file.path === target.path && file.uncommitted)
    || runtime.api.workspace.textDocuments.some(document => document.uri.toString() === local.toString() && document.isDirty);
  const historical = revision !== null && (revision !== files?.revision || dirty);
  const resource = historical
    ? runtime.workspace.with({ scheme: 'bytequay-git', authority: '', path: '/' + target.path, query: JSON.stringify({ revision }) })
    : local;
  const selection = target.end === null ? undefined : new runtime.api.Range((target.start ?? target.end) - 1, 0, target.end - 1, 0);
  const editor = await runtime.api.window.showTextDocument(runtime.api.Uri.parse(resource.toString()), { preview: false, selection });
  if (selection) editor.revealRange(selection, runtime.api.TextEditorRevealType.InCenter);
}
