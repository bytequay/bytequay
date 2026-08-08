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
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { PendingCommentsList } from './PendingCommentsList';
import {
  diffInlineCommentFromLocalPr, isPublishableReviewDraft, type DiffInlineComment,
} from './DiffInlineComments';
import type { LocalPRComment } from '../types/localPr';

afterEach(cleanup);

function pending(over: Partial<DiffInlineComment> = {}): DiffInlineComment {
  return {
    id: 'c1',
    filePath: 'plugin/trino-delta-lake/src/main/java/IcebergMetadata.java',
    lineNumber: 55,
    side: 'RIGHT',
    startLine: null,
    startSide: null,
    author: 'you',
    body: 'Should we guard this?',
    origin: 'local',
    parentCommentId: null,
    resolved: false,
    dismissed: false,
    pending: true,
    createdAtMs: Date.now(),
    ...over,
  };
}

describe('PendingCommentsList', () => {
  it('counts user and quick-review local roots as publishable review drafts', () => {
    const comment = (author: string, over: Partial<LocalPRComment> = {}): LocalPRComment => ({
      id: author, localPrId: 'pr-1', origin: 'local', scope: 'pr', filePath: null,
      lineNumber: null, side: 'RIGHT', startLine: null, startSide: null, author,
      body: 'review note', createdAt: 1, resolvedAt: null, dismissedAt: null,
      strippedOnPushAt: null, parentCommentId: null, publishedAt: null, ...over,
    });

    expect(isPublishableReviewDraft(comment('you'))).toBe(true);
    expect(isPublishableReviewDraft(comment('ai-reviewer'))).toBe(true);
    expect(isPublishableReviewDraft(comment('brain'))).toBe(false);
    expect(isPublishableReviewDraft(comment('claude-code'))).toBe(false);
    expect(isPublishableReviewDraft(comment('you', { parentCommentId: 'root' }))).toBe(false);
    expect(isPublishableReviewDraft(comment('you', { strippedOnPushAt: 123 }))).toBe(false);
  });

  it('renders Markdown in the submit-review pending cards', () => {
    const { container } = render(
      <PendingCommentsList comments={[pending({ body: '**Critical:** guard `close()`.' })]} />,
    );

    expect(container.querySelector('.pending-comments__text strong')?.textContent).toBe('Critical:');
    expect(container.querySelector('.pending-comments__text code')?.textContent).toBe('close()');
  });

  it('presents finding authors as Brain and implementation replies as Dev', () => {
    const comment = (author: string, findingId: string | null): LocalPRComment => ({
      id: author, localPrId: 'pr-1', origin: 'local', scope: 'file-line', filePath: 'src/Foo.ts',
      lineNumber: 12, side: 'RIGHT', startLine: null, startSide: null, author, body: 'reply',
      createdAt: 1, resolvedAt: null, dismissedAt: null, strippedOnPushAt: null,
      parentCommentId: null, publishedAt: null, findingId,
    });

    expect(diffInlineCommentFromLocalPr(comment('openai', 'finding-1'))).toMatchObject({
      author: 'brain', sourceLabel: 'BRAIN',
    });
    expect(diffInlineCommentFromLocalPr(comment('claude-code', null))).toMatchObject({
      author: 'dev', sourceLabel: 'DEV',
    });
    const ordinaryUserComment: LocalPRComment = { ...comment('you', null), findingId: undefined };
    expect(diffInlineCommentFromLocalPr(ordinaryUserComment)).toMatchObject({
      author: 'you', sourceLabel: undefined,
    });
  });
});
