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
import { describe, expect, it, vi } from 'vitest';
import type { IdeContext } from './contracts';
import type { IdeRuntime } from './runtime';
import type { ReviewCodeFilesDto } from '../pulls/code-tests/contracts';
import { reviewFixture } from '../pulls/agent-review/fixtures/review';
import { openReviewComment, reviewCommentCards } from './reviewCommentModel';
import { runBrowserFixture, deadline } from '../fixtures/browserFixture';

const review = reviewFixture();
const comments: NonNullable<IdeContext['comments']> = {
  repo: review.repo, number: review.number ?? 1, canReview: true, viewer: null, askAgent: false, openFinding: false,
  pending: [{ path: 'A.java', side: 'RIGHT', line: 12, startLine: 10, startSide: 'RIGHT', body: 'Pending root', commitId: 'head' },
    { path: 'A.java', side: 'RIGHT', line: 12, startLine: null, startSide: null, body: 'Pending reply', commitId: 'head', replyToCommentId: 20 }],
  threads: { pr: { repo: review.repo, number: 1 }, unavailable: false, activities: [{ githubId: 20, eventType: 'review_thread',
    payload: { path: 'A.java', line: 12, startLine: 10, side: 'RIGHT', outdated: false, resolved: true,
      comments: [{ githubId: 20, actor: 'alice', body: 'GitHub feedback', timestamp: null, inReplyToId: null }] } }] } as NonNullable<IdeContext['comments']>['threads'],
  localReview: { threads: [{ id: 'private', path: 'B.java', side: 'RIGHT', line: 5, startLine: null, headSha: 'old-head', baseSha: 'base', resolved: false,
    messages: [{ author: 'USER', body: 'Private discussion', createdAt: '' }] }] } as NonNullable<IdeContext['comments']>['localReview'],
};

describe('review comment browser', () => {
  it('includes all card sources, resolved discussions, and pending replies in their existing thread', () => {
    const cards = reviewCommentCards({ review, comments });
    expect(new Set(cards.map(card => card.kind))).toEqual(new Set(['Agent', 'GitHub', 'Private', 'Pending']));
    expect(cards.filter(card => card.kind === 'Pending')).toHaveLength(1);
    expect(cards.find(card => card.id === 'thread:20')).toMatchObject({ resolved: true, target: { path: 'A.java', start: 10, end: 12 },
      messages: [{ body: 'GitHub feedback' }, { body: 'Pending reply' }] });
    expect(cards.find(card => card.id === 'local:private')?.target).toEqual({ path: 'B.java', start: null, end: 5, revision: 'old-head' });
  });

  it('keeps pending replies visible when their GitHub thread is unavailable', () => {
    const cards = reviewCommentCards({ review: null, comments: { ...comments, threads: { ...comments.threads, activities: [], unavailable: true } } });
    expect(cards.filter(card => card.kind === 'Pending')).toHaveLength(2);
    expect(cards.some(card => card.messages[0].body === 'Pending reply')).toBe(true);
  });

  it('retains whole-change and outdated findings while excluding withdrawn and unselected results', () => {
    const finding = review.findings[0];
    const value = { ...review, findings: [
      { ...finding, path: null, startLine: null, endLine: null },
      { ...finding, id: 'withdrawn', state: 'WITHDRAWN' as const },
      { ...finding, id: 'unselected' },
      { ...finding, id: 'old', origin: 'DISCUSSION' as const, outdated: true, side: 'LEFT' as const },
    ] };
    const cards = reviewCommentCards({ review: value });
    expect(cards.map(card => card.id)).toEqual([`finding:${finding.id}`, 'finding:old']);
    expect(cards[0].target).toBeNull();
    expect(cards[1].target?.revision).toBe(review.runs[0].mergeBaseSha);
    expect(cards[1].outdated).toBe(true);
  });

  it('does not send old-side or outdated GitHub lines into the current file', () => {
    for (const change of [{ side: 'LEFT' }, { outdated: true }]) {
      const thread = comments.threads.activities[0];
      const cards = reviewCommentCards({ review, comments: { ...comments,
        threads: { ...comments.threads, activities: [{ ...thread, payload: { ...thread.payload, ...change } }] } } });
      expect(cards.find(card => card.kind === 'GitHub')?.target).toEqual({ path: 'A.java', start: null, end: null, revision: null });
    }
  });

  it('opens and focuses the exact range, using a saved snapshot for historical code', async () => {
    const revealRange = vi.fn();
    const showTextDocument = vi.fn().mockResolvedValue({ revealRange });
    const withUri = vi.fn(value => ({ toString: () => JSON.stringify(value) }));
    const range = { start: 9, end: 11 };
    const Range = vi.fn(function () { return range; });
    const runtime = { workspace: { path: '/worktree', with: withUri },
      api: { workspace: { textDocuments: [] }, Uri: { parse: (value: string) => value }, Range, TextEditorRevealType: { InCenter: 1 }, window: { showTextDocument } } } as unknown as IdeRuntime;
    const files = { revision: 'head', files: [] } as unknown as ReviewCodeFilesDto;
    const target = { path: 'src/A.java', start: 10, end: 12, revision: 'head' };
    await openReviewComment(runtime, target, files);
    expect(withUri).toHaveBeenLastCalledWith({ path: '/worktree/src/A.java' });
    expect(Range).toHaveBeenLastCalledWith(9, 0, 11, 0);
    expect(showTextDocument).toHaveBeenLastCalledWith('{"path":"/worktree/src/A.java"}', { preview: false, selection: range });
    expect(revealRange).toHaveBeenLastCalledWith(range, 1);
    await openReviewComment(runtime, { ...target, revision: 'old' }, files);
    expect(withUri).toHaveBeenLastCalledWith({ scheme: 'bytequay-git', authority: '', path: '/src/A.java', query: '{"revision":"old"}' });
    await openReviewComment(runtime, target, { ...files, files: [{ path: target.path, uncommitted: true }] } as ReviewCodeFilesDto);
    expect(withUri).toHaveBeenLastCalledWith({ scheme: 'bytequay-git', authority: '', path: '/src/A.java', query: '{"revision":"head"}' });
    await expect(openReviewComment(runtime, { ...target, path: '../private' }, files)).rejects.toThrow('Invalid workspace path');
    showTextDocument.mockRejectedValueOnce(new Error('File unavailable'));
    await expect(openReviewComment(runtime, target, files)).rejects.toThrow('File unavailable');
  });

  it('filters live files, supports keyboard tabs, reads cards, and reports navigation failures in a browser', async () => {
    expect(await runBrowserFixture<boolean>({ prefix: 'bytequay-review-comments-', entry: 'src/ide/fixtures/commentBrowser.tsx',
      global: 'CommentBrowserTests', styles: true, width: 1200, height: 800,
      body: `const results = await window.webContents.executeJavaScript('CommentBrowserTests.run()');
        if (process.env.BYTEQUAY_COMMENT_CAPTURE) require('fs').writeFileSync(process.env.BYTEQUAY_COMMENT_CAPTURE, (await window.webContents.capturePage()).toPNG());`,
    })).toBe(true);
  }, deadline(30_000));
});
