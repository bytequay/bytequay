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
import { afterEach, expect, it, vi } from 'vitest';
import CommitEditorDetail from './CommitEditorDetail';
import type { EditableCommit } from './commitRewrite';

afterEach(cleanup);

const BODY = 'Avoid the lookup of `t1$partitions` in the Iceberg catalog.';

const commit = {
  id: 'c1',
  sha: '7c3b32f332f',
  shortSha: '7c3b32f332f',
  subject: 'Avoid unnecessary lookup',
  body: BODY,
  authorName: 'Marius Grama',
  authorEmail: 'marius@example.com',
  authoredAt: '2026-08-03T10:00:00Z',
  additions: 45,
  deletions: 13,
  picks: ['7c3b32f332f'],
  squashedFrom: 0,
  reworded: false,
} as unknown as EditableCommit;

function renderDetail(editable: boolean, body = BODY) {
  const onOpenIssue = vi.fn();
  render(<CommitEditorDetail workspaceId="ws" selected={[commit]} files={[[]]}
    filesLoading={false} isLocal={false} editable={editable}
    repoContext={{ owner: 'trinodb', repo: 'trino' }} onOpenIssue={onOpenIssue}
    draftSubject={commit.subject} draftBody={body}
    onDraftSubject={vi.fn()} onDraftBody={vi.fn()} onSaveMessage={vi.fn()}
    onRevertMessage={vi.fn()} onSelectUpToHead={vi.fn()} onOpenSquash={vi.fn()} />);
  return onOpenIssue;
}

it('renders the commit body as markdown instead of raw text', () => {
  renderDetail(false);

  const body = screen.getByLabelText('Extended description');
  expect(body.querySelector('code')?.textContent).toBe('t1$partitions');
  // The backticks are markup, not content — seeing them means it rendered raw.
  expect(body.textContent).not.toContain('`');
  expect(screen.queryByRole('textbox', { name: /Extended description/ })).toBeNull();
});

it('swaps to a textarea when a rewritable body is clicked', () => {
  renderDetail(true);

  fireEvent.click(screen.getByLabelText('Extended description — click to edit'));

  const box = screen.getByRole('textbox', { name: 'Extended description' });
  // The raw source, not the rendered HTML, is what a reword edits.
  expect((box as HTMLTextAreaElement).value).toBe(BODY);
});

it('keeps a read-only body uneditable', () => {
  renderDetail(false);

  fireEvent.click(screen.getByLabelText('Extended description'));

  expect(screen.queryByRole('textbox', { name: /Extended description/ })).toBeNull();
});

it('opens the referenced issue from a #N in the body', () => {
  const onOpenIssue = renderDetail(false, 'Follows up on #26141 for the catalog.');

  const chip = screen.getByText('#26141');
  expect(chip.dataset.repoOwner).toBe('trinodb');
  fireEvent.click(chip);

  expect(onOpenIssue).toHaveBeenCalledWith(26141);
});

it('treats an issue chip as navigation, not a click into the text', () => {
  const onOpenIssue = renderDetail(true, 'Follows up on #26141 for the catalog.');

  fireEvent.click(screen.getByText('#26141'));

  expect(onOpenIssue).toHaveBeenCalledWith(26141);
  // Entering edit mode here would swap the chip out from under the click.
  expect(screen.queryByRole('textbox', { name: /Extended description/ })).toBeNull();
});
