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
import { findingInResults } from '../pulls/agent-review/model';
import type { IdeContext } from './contracts';
import { codeHostKey, parseCodeHost } from '../pulls/code-tests/contracts';
import { TEST_IDENTIFIER } from '../pulls/code-tests/runModel';

const READ_METHODS = new Set(['getRepositoryAvatar', 'getGitHubUserProfile', 'fetchGitHubCodeSnippet', 'listCodeFiles', 'readCodeFile', 'readCodeBlame', 'readCodeHistory', 'readHistoryCommit', 'readHistorySnapshot', 'listCodeRuns', 'readCodeRunOutput', 'readWorktreeChanges', 'getAgentReview', 'getAgentTask']);
const PRIVATE_METHODS = new Set(['run.ask', 'run.agent', 'stopCodeRun', 'openInAppBrowser', 'github.post', 'github.reply', 'github.resolve', 'github.edit', 'github.react', 'comments.ask', 'comments.reply', 'comments.resolve', 'comments.retry', 'comments.finding', 'comments.refresh', 'comments.pending', 'comments.detail', 'review.changed', 'askAgentReview', 'resolveAgentReviewFinding', 'reopenAgentReviewFinding', 'betterWords', 'finding.ask', 'finding.resolve', 'finding.reopen', 'finding.add', 'finding.post', 'finding.editDraft', 'finding.remove', 'finding.fix']);

function text(value: unknown, limit = 30_000): asserts value is string {
  if (typeof value !== 'string' || value.length > limit || value.includes('\0')) throw new Error('Invalid IDE action text');
}
function id(value: unknown): asserts value is string { text(value, 100); if (!value) throw new Error('Missing action target'); }
function integer(value: unknown): asserts value is number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) throw new Error('Invalid action version or offset');
}
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid IDE action data');
  return value as Record<string, unknown>;
}
function file(value: unknown): void {
  text(value, 4_096);
  if (!value || value.startsWith('/') || value.includes('\\') || value.split('/').some(part => !part || part === '..' || part === '.' || part === '.git')) throw new Error('Invalid workspace path');
}
function count(args: unknown[], minimum: number, maximum = minimum): void {
  if (args.length < minimum || args.length > maximum) throw new Error('Invalid IDE action arguments');
}

/** Main checks the current, parent-supplied task context before any request reaches an app callback. */
export function validateIdeAction(method: unknown, args: unknown, context: IdeContext | null, userGesture: boolean): asserts args is unknown[] {
  if (!context || typeof method !== 'string' || !Array.isArray(args)) throw new Error('The editor has no active task context');
  if (!READ_METHODS.has(method) && !PRIVATE_METHODS.has(method)) throw new Error('This operation is unavailable in the editor');
  if (!READ_METHODS.has(method) && !['comments.refresh', 'review.changed', 'comments.detail'].includes(method) && !userGesture) {
    throw new Error('This action requires a user click or keypress in the editor');
  }
  if (method === 'getRepositoryAvatar') {
    if (args.length < 1 || args.length > 2) throw new Error('Invalid avatar arguments');
    text(args[0], 39);
    if (!/^[a-z\d](?:[a-z\d-]{0,37}[a-z\d])?$/i.test(args[0])
      || (args[1] !== undefined && typeof args[1] !== 'boolean')) throw new Error('Invalid repository avatar request');
    return;
  }
  if (method === 'getGitHubUserProfile') {
    count(args, 1); text(args[0], 45);
    if (!/^[a-z0-9][a-z0-9-]{0,38}(?:\[bot\])?$/i.test(args[0])) throw new Error('Invalid profile login');
    return;
  }
  if (method === 'openInAppBrowser') {
    count(args, 1); text(args[0], 8_192);
    const url = new URL(args[0]);
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) throw new Error('Only web links can be opened from a review');
    return;
  }
  if (method === 'fetchGitHubCodeSnippet') {
    count(args, 1);
    const query = object(args[0]);
    if (`${query.owner}/${query.repo}` !== context.comments?.repo) throw new Error('The code preview belongs to a different repository');
    if (typeof query.commit !== 'string' || !/^[a-f0-9]{40,64}$/.test(query.commit)) throw new Error('Invalid code preview revision');
    file(query.path); integer(query.startLine); integer(query.endLine);
    if (query.startLine === 0 || query.endLine < query.startLine || query.endLine - query.startLine >= 200) throw new Error('Invalid code preview range');
    return;
  }
  if (method === 'run.ask') {
    count(args, 2); id(args[0]); text(args[1], 30_000);
    if (!args[1].trim() || (context.review ?? context.task)?.state !== 'OPEN') throw new Error('Open a task to prepare an agent request');
    return;
  }
  if (method === 'run.agent') {
    count(args, 2); id(args[0]);
    // The names reach the agent without the user reading them first, so only code identifiers pass.
    if (!Array.isArray(args[1]) || args[1].length > 20 || !args[1].every(name => typeof name === 'string' && TEST_IDENTIFIER.test(name))) throw new Error('Invalid test names');
    if (context.host.kind !== 'review' || context.review?.state !== 'OPEN') throw new Error('Open a review to ask its agent');
    return;
  }
  if (method === 'stopCodeRun') {
    count(args, 2); id(args[1]);
    if (codeHostKey(parseCodeHost(args[0])) !== codeHostKey(context.host)) throw new Error('Different run owner');
    return;
  }
  const review = context.review;
  const comments = context.comments;
  if (['listCodeFiles', 'readCodeFile', 'readCodeBlame', 'readCodeHistory', 'readHistoryCommit', 'readHistorySnapshot', 'listCodeRuns', 'readCodeRunOutput', 'readWorktreeChanges'].includes(method)) {
    if (codeHostKey(parseCodeHost(args[0])) !== codeHostKey(context.host)) throw new Error('The code request names a different task');
    switch (method) {
      case 'listCodeFiles': case 'listCodeRuns': count(args, 1); return;
      case 'readCodeHistory': case 'readHistoryCommit': case 'readHistorySnapshot': {
        count(args, 2);
        const query = object(args[1]);
        if (method !== 'readCodeHistory' || query.revision !== undefined) {
          if (typeof query.revision !== 'string' || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(query.revision)) throw new Error('Invalid history revision');
        }
        if (query.parent !== undefined && (typeof query.parent !== 'string' || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(query.parent))) throw new Error('Invalid history parent');
        if (query.path !== undefined || method === 'readHistorySnapshot') file(query.path);
        if (query.offset !== undefined) { integer(query.offset); if (query.offset > 1_000_000) throw new Error('History offset is too large'); }
        return;
      }
      case 'readCodeBlame': count(args, 2); file(args[1]); return;
      case 'readCodeFile': count(args, 2, 3); file(args[1]); if (args[2] !== undefined) text(args[2], 64); return;
      case 'readCodeRunOutput': count(args, 3); id(args[1]); integer(args[2]); return;
      case 'readWorktreeChanges': {
        count(args, 2);
        const query = object(args[1]);
        if (!['ALL', 'COMMIT', 'COMMITTED', 'UNCOMMITTED'].includes(String(query.scope))) throw new Error('Invalid history scope');
        for (const key of ['commit', 'base', 'head']) {
          if (query[key] !== undefined && (typeof query[key] !== 'string' || !/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/.test(query[key]))) throw new Error('Invalid history revision');
        }
        return;
      }
    }
  }
  if (method === 'getAgentReview') { count(args, 1); if (!review || args[0] !== review.id) throw new Error('Different review'); return; }
  if (method === 'getAgentTask') {
    count(args, 1, 2);
    if (![context.task?.id, review?.task.id, review?.sourceTaskId].filter(Boolean).includes(String(args[0]))) throw new Error('Different task');
    if (args[1] !== undefined && args[1] !== (context.host.kind === 'task' ? context.host.workspaceId : review?.workspaceId)) throw new Error('Different workspace');
    return;
  }
  if (method === 'review.changed') {
    count(args, 1);
    if (object(args[0]).id !== review?.id) throw new Error('Different review');
    return;
  }
  if (method.startsWith('finding.')) {
    const finding = review?.findings.find(item => item.id === args[0] && findingInResults(review, item));
    if (!finding) throw new Error('Unknown finding');
    if (['finding.reopen', 'finding.add', 'finding.remove'].includes(method)) count(args, 1);
    else if (method === 'finding.editDraft') { count(args, 3); text(args[1]); integer(args[2]); }
    else {
      count(args, 2, method === 'finding.resolve' ? 3 : 2); text(args[1]);
      if (args[2] !== undefined && typeof args[2] !== 'boolean') throw new Error('Invalid remember choice');
    }
    if (['finding.add', 'finding.post', 'finding.editDraft', 'finding.remove'].includes(method) && (review.number === null || !comments?.canReview)) {
      throw new Error('This review cannot publish comments');
    }
    if (method === 'finding.fix' && !review.sourceTaskId) throw new Error('No development task is associated with this finding');
    return;
  }
  if (['askAgentReview', 'resolveAgentReviewFinding', 'reopenAgentReviewFinding'].includes(method)) {
    if (!review || args[0] !== review.id) throw new Error('Different review');
    const finding = review.findings.find(item => item.id === args[1] && findingInResults(review, item));
    if (!finding) throw new Error('Unknown finding');
    integer(args[2]);
    if (method === 'askAgentReview') { count(args, 5); if (args[2] !== finding.revision) throw new Error('Finding changed'); text(args[3]); id(args[4]); }
    else if (method === 'resolveAgentReviewFinding') { count(args, 4, 5); if (args[2] !== finding.version) throw new Error('Finding changed'); text(args[3]); if (args[4] !== undefined && typeof args[4] !== 'boolean') throw new Error('Invalid remember choice'); }
    else { count(args, 3); if (args[2] !== finding.version) throw new Error('Finding changed'); }
    return;
  }
  if (method === 'betterWords') {
    count(args, 1, 3); text(args[0]);
    if (args[1] !== undefined && args[1] !== comments?.repo) throw new Error('Different repository');
    if (args[2] !== undefined && args[2] !== comments?.workspaceId) throw new Error('Different workspace');
    return;
  }
  if (!comments) throw new Error('Review actions are unavailable here');
  if (method.startsWith('github.')) {
    if (!comments.canReview || args[0] !== comments.repo || args[1] !== comments.number) throw new Error('Different or unavailable pull request');
    if (method === 'github.post') {
      count(args, 4);
      if (typeof args[3] !== 'string' || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(args[3])) throw new Error('Missing comment key');
      const comment = object(args[2]);
      file(comment.path); text(comment.body); integer(comment.line);
      if (comment.line === 0 || comment.side !== 'RIGHT') throw new Error('Invalid code selection');
      if (comment.startLine != null) { integer(comment.startLine); if (comment.startLine === 0 || comment.startLine > comment.line || comment.startSide !== 'RIGHT') throw new Error('Invalid code range'); }
      if (typeof comment.commitId !== 'string' || !/^[a-f0-9]{40,64}$/.test(comment.commitId)) throw new Error('Missing review revision');
      if (review && comment.commitId !== review.observedHeadSha) throw new Error('The pull request head changed');
      return;
    }
    const target = method === 'github.edit' || method === 'github.react' ? args[3] : args[2];
    const thread = comments.threads.activities.find(activity => activity.eventType === 'review_thread' && activity.payload.comments.some(comment => comment.githubId === target));
    const comment = thread?.payload.comments.find(comment => comment.githubId === target);
    if (!comment) throw new Error('Unknown review comment');
    if (method === 'github.reply' || method === 'github.resolve') {
      count(args, 4);
      const root = thread.payload.comments.find(comment => comment.inReplyToId === null) ?? thread.payload.comments[0];
      if (root.githubId !== target) throw new Error('The reply must target the current thread');
      if (method === 'github.reply') text(args[3]);
      else if (typeof args[3] !== 'boolean') throw new Error('Invalid resolution');
    }
    else if (method === 'github.edit') {
      count(args, 5); text(args[4]);
      if (args[2] !== 'review_comment' || comment.actor !== comments.viewer?.login) throw new Error('Only your review comments can be edited');
    }
    else {
      count(args, 6);
      if (args[2] !== 'review_comment' || !['+1', '-1', 'laugh', 'hooray', 'confused', 'heart', 'rocket', 'eyes'].includes(String(args[4]))
        || (args[5] !== null && typeof args[5] !== 'boolean')) throw new Error('Invalid review reaction');
    }
    return;
  }

  switch (method) {
    case 'comments.refresh': count(args, 0); return;
    case 'comments.finding': count(args, 1); if (!review?.findings.some(item => item.id === args[0] && findingInResults(review, item))) throw new Error('Unknown finding'); return;
    case 'comments.reply': case 'comments.resolve': case 'comments.retry': {
      count(args, 2);
      const thread = comments.localReview.threads.find(item => item.id === args[0]);
      if (!thread) throw new Error('Unknown private review thread');
      if (method === 'comments.reply') text(args[1]);
      if (method === 'comments.resolve' && typeof args[1] !== 'boolean') throw new Error('Invalid resolution');
      if (method === 'comments.retry' && !thread.messages.some(item => item.id === args[1])) throw new Error('Unknown private review message');
      return;
    }
    case 'comments.ask': {
      count(args, 1);
      if (!comments.askAgent) throw new Error('The review agent is unavailable');
      const request = object(args[0]);
      const comment = object(request.comment);
      file(comment.path); text(comment.body); integer(comment.line);
      if (comment.line === 0 || comment.side !== 'RIGHT') throw new Error('Invalid code selection');
      if (comment.startLine != null) { integer(comment.startLine); if (comment.startLine === 0 || comment.startLine > comment.line) throw new Error('Invalid code range'); }
      text(request.code, 100_000);
      return;
    }
    case 'comments.pending': {
      count(args, 1);
      if (!Array.isArray(args[0]) || args[0].length > 500) throw new Error('Invalid local review draft');
      for (const value of args[0]) {
        const comment = object(value); file(comment.path); text(comment.body); integer(comment.line);
        if (comment.replyToCommentId != null) {
          integer(comment.replyToCommentId);
          const thread = comments.threads.activities.find(activity => activity.eventType === 'review_thread'
            && (activity.payload.comments.find(value => value.inReplyToId === null) ?? activity.payload.comments[0])?.githubId === comment.replyToCommentId);
          if (!thread || thread.payload.path !== comment.path) throw new Error('Unknown review thread');
        }
      }
      return;
    }
    case 'comments.detail': {
      count(args, 1);
      const detail = object(args[0]);
      if (detail.repo !== comments.repo || detail.number !== comments.number) throw new Error('Different pull request');
      return;
    }
  }
  throw new Error('This operation is unavailable in the editor');
}
