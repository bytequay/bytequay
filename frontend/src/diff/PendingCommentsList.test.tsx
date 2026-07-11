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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ReviewTabPendingList } from './PendingCommentsList';
import { diffInlineCommentFromReviewDto, type DiffInlineComment } from './DiffInlineComments';
import type { ReviewCommentDto } from '../types';

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

describe('ReviewTabPendingList', () => {
  it('renders human and agent pending comments with location labels', () => {
    const { container } = render(
      <ReviewTabPendingList
        comments={[
          pending(),
          pending({
            id: 'c0',
            author: 'chenjian2664',
            lineNumber: 56,
          }),
          pending({
            id: 'c2',
            author: 'AI Reviewer',
            body: 'This can silently swallow an error.',
            sourceLabel: 'AGENT',
            lineNumber: 89,
          }),
        ]}
      />,
    );

    expect(screen.getByText('Pending review')).toBeTruthy();
    expect(screen.getByText('3')).toBeTruthy();
    expect(screen.getByText('IcebergMetadata.java · R55')).toBeTruthy();
    expect(screen.getByText('IcebergMetadata.java · R89')).toBeTruthy();
    expect(screen.getByAltText('chenjian2664')).toBeTruthy();
    expect(screen.getByText('AGENT')).toBeTruthy();
    expect(container.querySelector('.review-pending__card--bot')).not.toBeNull();
    expect(container.querySelector('.review-pending__card--you')).not.toBeNull();
  });

  it('maps review DTO authors into GitHub-avatar-ready logins', () => {
    const mapped = diffInlineCommentFromReviewDto({
      id: 'r1',
      taskId: 'task-1',
      file: 'src/Foo.ts',
      line: 12,
      side: 'RIGHT',
      startLine: null,
      startSide: null,
      body: 'remote comment',
      createdAt: Date.now(),
      source: 'REMOTE_REVIEWER',
      author: '@chenjian2664',
      resolved: false,
    } satisfies ReviewCommentDto);

    expect(mapped.author).toBe('chenjian2664');
  });
});
