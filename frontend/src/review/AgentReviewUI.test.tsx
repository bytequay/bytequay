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
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DiffInlineComments, diffInlineCommentFromLocalPr } from '../diff/DiffInlineComments';
import type { LocalPRBundle, LocalPRComment } from '../types/localPr';
import { createAgentReviewFixture, createVerificationStateFixture } from './agentReviewTestData';
import { AgentReviewHeaderAction } from './AgentReviewHeaderAction';
import { SubmitReviewPopover } from './SubmitReviewPopover';
import { AgentFindingContent, findingMarkdown, findingSummary, presentFinding } from './AgentEvidence';

function bundle(): LocalPRBundle {
  return {
    pr: {
      id: 'pr-1', taskId: null, branchName: 'feature', baseBranch: 'main', title: 'Preserve missing behavior',
      description: 'Fixture PR', status: 'remote-open', createdAt: 1, pushedAt: 1, remotePrNumber: 42,
      remotePrUrl: 'https://example.test/pr/42', mergedAt: null, closedAt: null, origin: 'external',
      repo: 'acme/widget', author: 'maria', syncedAt: 1, syncedAdditions: 2, syncedDeletions: 1,
      syncedMergeable: true, syncedMergeableState: 'clean', syncedMergeQueueEnabled: false,
      syncedMergeQueueState: null, branchDeletedAt: null,
    },
    commits: [{ id: 'c-1', localPrId: 'pr-1', sha: 'abcdef012345', message: 'change', additions: 2, deletions: 1, authoredAt: 1, pushedAt: 1 }],
    timeline: [], checks: [], comments: [],
  };
}

function fixture() {
  return createAgentReviewFixture(bundle(), [{
    filename: 'src/ChangedFile.ts', status: 'modified', additions: 2, deletions: 1,
    patch: '@@ -3,2 +3,3 @@\n-old\n+new\n context',
  }]);
}

describe('agent review UI', () => {
  it('renders a pending finding through the shared comment card and expands SUPPORTS/REFUTES citations', () => {
    const data = fixture();
    render(<DiffInlineComments comments={[diffInlineCommentFromLocalPr(data.pr_comments[0], data)]} allowLocalComments={false} />);
    expect(screen.getByText('BRAIN')).toBeTruthy();
    expect(screen.getByText('brain')).toBeTruthy();
    expect(screen.getByText('MAJOR')).toBeTruthy();
    expect(screen.getByText('Pending')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /evidence/ }));
    expect(screen.getByText('SUPPORTS · 1')).toBeTruthy();
    expect(screen.getByText('REFUTES · 1')).toBeTruthy();
    expect(screen.getByText(/ChangedFile\.ts:3@abcdef0/)).toBeTruthy();
  });

  it('adds conservative Markdown to legacy finding prose and keeps folded summaries plain', () => {
    const prose = 'DynamicTrinoCatalog uses ConnectorIdentity.\n\nCould you clarify the intended behavior here? Keep `Session` isolated.';
    expect(findingMarkdown(prose)).toContain('`DynamicTrinoCatalog` uses `ConnectorIdentity`');
    expect(findingMarkdown(prose)).toContain('**Question:** Keep `Session` isolated.');
    expect(findingMarkdown('GitHub links stay prose.')).toBe('GitHub links stay prose.');
    expect(findingSummary('**Risk:** `DynamicTrinoCatalog` reuses identity.')).toBe('Risk: DynamicTrinoCatalog reuses identity.');
  });

  it('edits, excludes, removes, and submits fixture comments from one popover', () => {
    const data = fixture();
    const onToggle = vi.fn();
    const onEdit = vi.fn();
    const onRemove = vi.fn();
    const onSubmit = vi.fn();
    render(<SubmitReviewPopover comments={data.pr_comments} excluded={new Set()} onToggle={onToggle} onEdit={onEdit} onRemove={onRemove} onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole('button', { name: 'Submit review • 2 ▾' }));
    const textareas = screen.getAllByRole('textbox');
    fireEvent.change(textareas[0], { target: { value: 'Edited finding' } });
    expect(onEdit).toHaveBeenCalledWith('fixture-comment-1', 'Edited finding');
    fireEvent.click(screen.getAllByRole('checkbox')[0]);
    expect(onToggle).toHaveBeenCalledWith('finding-1');
    fireEvent.click(screen.getAllByRole('button', { name: 'Remove pending comment' })[0]);
    expect(onRemove).toHaveBeenCalledWith('fixture-comment-1');
    fireEvent.click(screen.getByRole('button', { name: 'Submit review (2)' }));
    expect(onSubmit).toHaveBeenCalledWith('REQUEST_CHANGES');
  });

  it('shows manual drafts in the agent-review submission and includes them in its count', () => {
    const data = fixture();
    const manual: LocalPRComment = {
      ...data.pr_comments[0],
      id: 'manual-comment',
      findingId: null,
      author: 'you',
      body: 'Manual reviewer draft',
    };
    const onRemove = vi.fn();
    const view = render(<SubmitReviewPopover
      comments={[...data.pr_comments, manual]}
      excluded={new Set()}
      onToggle={vi.fn()}
      onEdit={vi.fn()}
      onRemove={onRemove}
      onSubmit={vi.fn()}
    />);
    const popover = within(view.container);
    fireEvent.click(popover.getByRole('button', { name: /Submit review/ }));
    expect(popover.getByDisplayValue('Manual reviewer draft')).toBeTruthy();
    expect(popover.getByRole('button', { name: 'Submit review (3)' })).toBeTruthy();
    const manualRow = popover.getByDisplayValue('Manual reviewer draft').closest('label');
    if (manualRow === null) throw new Error('manual draft row missing');
    fireEvent.click(within(manualRow).getByRole('button', { name: 'Remove pending comment' }));
    expect(onRemove).toHaveBeenCalledWith('manual-comment');
  });

  it('renders all header entry states with review actions in page content', () => {
    const data = fixture();
    const props = {
      comments: data.pr_comments, excluded: new Set<string>(), onStart: vi.fn(), onOpenRound: vi.fn(),
      onToggle: vi.fn(), onEdit: vi.fn(), onRemove: vi.fn(), onSubmit: vi.fn(),
    };
    const { rerender } = render(<AgentReviewHeaderAction state="never" {...props} />);
    expect(screen.getByRole('button', { name: /^Full review$/ })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Customize agent review' }));
    fireEvent.click(screen.getByRole('radio', { name: /CLI runner/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Start review' }));
    expect(props.onStart).toHaveBeenLastCalledWith({ runner: 'cli' });
    rerender(<AgentReviewHeaderAction state="running" {...props} />);
    expect(screen.getByRole('button', { name: /Full review • running/ })).toBeTruthy();
    rerender(<AgentReviewHeaderAction state="stale" {...props} />);
    fireEvent.click(screen.getByRole('button', { name: /Full review · update available/ }));
    expect(props.onOpenRound).toHaveBeenCalledOnce();
  });

  it('keeps Full review but hides Submit review on review-only PR surfaces', () => {
    const data = fixture();
    const view = render(
      <AgentReviewHeaderAction
        state="done"
        comments={data.pr_comments}
        excluded={new Set()}
        onStart={vi.fn()}
        onOpenRound={vi.fn()}
        onToggle={vi.fn()}
        onEdit={vi.fn()}
        onRemove={vi.fn()}
      />,
    );
    const header = within(view.container);
    expect(header.getByRole('button', { name: /Full review · Round/ })).toBeTruthy();
    expect(header.queryByRole('button', { name: /Submit review/ })).toBeNull();
  });

  it.each([
    ['verified', /verified/],
    ['partially', /partially verified/],
    ['unknown', /unknown — asks author/],
    ['rejected', /rejected — dropped/],
  ] as const)('renders %s verifier chrome from fixture verification rows', (status, label) => {
    const data = createVerificationStateFixture(fixture(), status);
    const view = presentFinding(data, 'finding-1');
    expect(view).toBeDefined();
    if (view === undefined) return;
    const { container } = render(<AgentFindingContent view={view} body="Finding body" pending />);
    expect(within(container).getByText(label)).toBeTruthy();
  });

  it('derives the confidence ceiling from supporting evidence only', () => {
    const data = fixture();
    data.findings[0] = { ...data.findings[0], verification_status: 'partially' };
    data.evidence = data.evidence.map(row => row.finding_id !== 'finding-1' ? row : {
      ...row,
      strength_class: row.relation === 'SUPPORTS' ? 'E1' : 'E4',
    });
    const view = presentFinding(data, 'finding-1');
    expect(view).toBeDefined();
    if (view === undefined) return;
    const { container } = render(<AgentFindingContent view={view} body="Finding body" />);
    expect(container.querySelector('.agent-finding-chip.confidence')?.textContent).toContain('≤0.45');
  });
});
