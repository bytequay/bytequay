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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DiffFileDto } from '../types';
import { TaskChangedFilesCard } from './TaskChangedFilesCard';

afterEach(cleanup);

const files: DiffFileDto[] = Array.from({ length: 5 }, (_, index): DiffFileDto => ({
  filename: `frontend/src/file-${index + 1}.tsx`,
  status: 'modified',
  additions: index + 1,
  deletions: index,
  patch: null,
}));

describe('TaskChangedFilesCard', () => {
  it('shows real totals and opens the existing review action', () => {
    const onReview = vi.fn();
    render(<TaskChangedFilesCard files={files} commitCount={2} onReview={onReview} />);
    expect(screen.getByText('Changed 5 files')).toBeTruthy();
    expect(screen.getByText('2 commits')).toBeTruthy();
    expect(screen.getByText('+15')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    expect(onReview).toHaveBeenCalledOnce();
  });

  it('shows three rows first and expands the rest', () => {
    const { container } = render(<TaskChangedFilesCard files={files} />);
    expect(container.querySelectorAll('.workspace-task-files-card__file')).toHaveLength(3);
    fireEvent.click(screen.getByRole('button', { name: 'Show 2 more files' }));
    expect(container.querySelectorAll('.workspace-task-files-card__file')).toHaveLength(5);
    expect(screen.getByRole('button', { name: 'Show fewer files' })).toBeTruthy();
  });

  it('folds and reopens the file rows', () => {
    const { container } = render(<TaskChangedFilesCard files={files} />);
    fireEvent.click(screen.getByRole('button', { name: 'Collapse changed files' }));
    expect(container.querySelectorAll('.workspace-task-files-card__file')).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'Expand changed files' }));
    expect(container.querySelectorAll('.workspace-task-files-card__file')).toHaveLength(3);
  });

  it('translates the trunk variant and opens its undo action', () => {
    const onUndo = vi.fn();
    render(<TaskChangedFilesCard files={files} verb="Edited" onUndo={onUndo} />);
    expect(screen.getByText('Edited 5 files')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(onUndo).toHaveBeenCalledOnce();
  });
});
