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
import { describe, expect, it } from 'vitest';
import { reviewFixture } from '../pulls/agent-review/fixtures/review';
import { validateIdeAction } from './actionPolicy';
import type { IdeContext } from './contracts';

const review = reviewFixture();
const context: IdeContext = { host: { kind: 'review', reviewId: review.id }, review, task: null, detail: null };

describe('IDE action boundary', () => {
  it('allows current-worktree reads but rejects unrelated worktrees and paths', () => {
    expect(() => validateIdeAction('readCodeFile', [context.host, 'src/main.go'], context, false)).not.toThrow();
    expect(() => validateIdeAction('readCodeFile', [{ kind: 'review', reviewId: 'elsewhere' }, 'src/main.go'], context, false)).toThrow('different task');
    for (const path of ['/etc/passwd', '../outside', 'src/../../outside', 'src/./file']) {
      expect(() => validateIdeAction('readCodeFile', [context.host, path], context, false)).toThrow('workspace path');
    }
  });

  it('rejects arbitrary app methods and direct low-level publishing', () => {
    for (const method of ['publishReviewDraftComment', 'startAgentTurn', 'savePat', 'constructor', '__proto__', 'runShell']) {
      expect(() => validateIdeAction(method, [], context, true)).toThrow('unavailable');
    }
  });

  it('requires native user activation and a current finding for action intents', () => {
    expect(() => validateIdeAction('finding.ask', ['finding-1', 'Explain this'], context, false)).toThrow('user click');
    expect(() => validateIdeAction('finding.ask', ['another-review-finding', 'Explain this'], context, true)).toThrow('Unknown finding');
    expect(() => validateIdeAction('finding.ask', ['finding-1', 'Explain this'], context, true)).not.toThrow();
    expect(() => validateIdeAction('finding.resolve', ['finding-1', 'Verified', 'yes'], context, true)).toThrow('remember choice');
    expect(() => validateIdeAction('finding.post', ['finding-1', 'Publish'], context, true)).toThrow('cannot publish');
  });

  it('retains backend version checks at the private finding boundary', () => {
    const finding = review.findings[0];
    expect(() => validateIdeAction('resolveAgentReviewFinding', [review.id, finding.id, finding.version, 'Verified'], context, true)).not.toThrow();
    expect(() => validateIdeAction('resolveAgentReviewFinding', [review.id, finding.id, finding.version + 1, 'Verified'], context, true)).toThrow('Finding changed');
    expect(() => validateIdeAction('resolveAgentReviewFinding', ['foreign-review', finding.id, finding.version, 'Verified'], context, true)).toThrow('Different review');
  });
  it('limits GitHub actions to direct gestures on the current PR and known comments', () => {
    const current = { ...context, comments: { repo: 'owner/repo', number: 1, canReview: true, viewer: { login: 'viewer' },
      threads: { activities: [{ eventType: 'review_thread', payload: { comments: [
        { githubId: 20, inReplyToId: null, actor: 'viewer' }, { githubId: 21, inReplyToId: 20, actor: 'other' },
      ] } }] } } } as unknown as IdeContext;
    expect(() => validateIdeAction('github.reply', ['owner/repo', 1, 20, 'Reply'], current, true)).not.toThrow();
    expect(() => validateIdeAction('github.reply', ['owner/repo', 1, 20, 'Reply'], current, false)).toThrow('user click');
    expect(() => validateIdeAction('github.reply', ['foreign/repo', 1, 20, 'Reply'], current, true)).toThrow('Different');
    expect(() => validateIdeAction('github.reply', ['owner/repo', 1, 999, 'Reply'], current, true)).toThrow('Unknown');
    expect(() => validateIdeAction('github.reply', ['owner/repo', 1, 21, 'Reply'], current, true)).toThrow('current thread');
    expect(() => validateIdeAction('github.edit', ['owner/repo', 1, 'review_comment', 21, 'Edit'], current, true)).toThrow('Only your');
    expect(() => validateIdeAction('github.react', ['owner/repo', 1, 'issue_comment', 20, '+1', true], current, true)).toThrow('Invalid');
  });

  it('opens web links only on a user gesture and bounds read-only profile and code previews', () => {
    expect(() => validateIdeAction('openInAppBrowser', ['https://github.com/owner/repo'], context, true)).not.toThrow();
    expect(() => validateIdeAction('openInAppBrowser', ['https://github.com/owner/repo'], context, false)).toThrow('user click');
    for (const url of ['file:///etc/passwd', 'javascript:alert(1)', 'https://user:secret@example.com']) {
      expect(() => validateIdeAction('openInAppBrowser', [url], context, true)).toThrow('Only web links');
    }
    expect(() => validateIdeAction('getGitHubUserProfile', ['owner'], context, false)).not.toThrow();
    expect(() => validateIdeAction('getGitHubUserProfile', ['../tokens'], context, false)).toThrow('Invalid profile');
    const current = { ...context, comments: { repo: 'owner/repo' } } as IdeContext;
    const query = { owner: 'owner', repo: 'repo', commit: 'a'.repeat(40), path: 'main.go', startLine: 1, endLine: 5 };
    expect(() => validateIdeAction('fetchGitHubCodeSnippet', [query], current, false)).not.toThrow();
    expect(() => validateIdeAction('fetchGitHubCodeSnippet', [{ ...query, repo: 'other' }], current, false)).toThrow('different repository');
    expect(() => validateIdeAction('fetchGitHubCodeSnippet', [{ ...query, endLine: 300 }], current, false)).toThrow('Invalid code preview range');
  });

});


it('prepares a bounded log draft only on a click in an open task', () => {
  expect(() => validateIdeAction('run.ask', ['run', 'failure'], context, true)).not.toThrow();
  expect(() => validateIdeAction('run.ask', ['run', 'failure'], context, false)).toThrow('user click');
  expect(() => validateIdeAction('run.ask', ['run', 'x'.repeat(30_001)], context, true)).toThrow('text');
  expect(() => validateIdeAction('run.ask', ['run', 'failure'], { ...context, review: { ...review, state: 'CLOSED' } }, true)).toThrow('Open a task');
});
