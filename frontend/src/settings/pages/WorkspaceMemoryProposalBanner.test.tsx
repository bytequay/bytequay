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
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Bridge, WorkspaceDto, WorkspaceMemoryProposalDto } from '../../types';
import WorkspaceMemoryProposalBanner from './WorkspaceMemoryProposalBanner';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

afterEach(() => {
  cleanup();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

describe('WorkspaceMemoryProposalBanner', () => {
  it('renders nothing when there is no pending proposal', async () => {
    const getProposal = vi.fn(
        async (): Promise<WorkspaceMemoryProposalDto | null> => null);
    installBridge({ getWorkspaceMemoryProposal: getProposal });

    const { container } = render(
      <WorkspaceMemoryProposalBanner workspaceId="ws-default" onApplied={() => {}} />);

    await waitFor(() => expect(getProposal).toHaveBeenCalledWith('ws-default'));
    expect(screen.queryByTestId('workspace-memory-proposal-banner')).toBeNull();
    expect(container.firstChild).toBeNull();
  });

  it('renders the pending proposal with its delta and toggles the diff', async () => {
    const proposal = sampleProposal({
      currentMd: 'Old memory.',
      proposedMd: 'Old memory.\n\nNew section with three lines.\nLine two.\nLine three.',
    });
    installBridge({ getWorkspaceMemoryProposal: vi.fn(async () => proposal) });

    render(<WorkspaceMemoryProposalBanner workspaceId="ws-default" onApplied={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('workspace-memory-proposal-banner')).toBeTruthy();
    });
    // Delta is the visible character delta — assert it's signed +.
    const expectedDelta = (proposal.proposedMd.length - proposal.currentMd.length);
    expect(screen.getByText(content => content.includes(`+${expectedDelta}`))).toBeTruthy();

    // Show diff toggles a side-by-side pair of panes.
    await act(async () => {
      fireEvent.click(screen.getByText('Show diff'));
    });
    expect(screen.getByText('Current memory')).toBeTruthy();
    expect(screen.getByText('Proposed memory')).toBeTruthy();
  });

  it('calls applyWorkspaceMemoryProposal and notifies the parent on Apply', async () => {
    const getProposal = vi.fn(async () => sampleProposal({}));
    const applyProposal = vi.fn(async () => ({
      id: 'ws-default', name: 'ByteQuay', memoryMd: 'Approved.',
      isScratch: false,
      createdAt: '2026-05-22T12:00:00Z', updatedAt: '2026-05-22T12:00:00Z',
    } satisfies WorkspaceDto));
    installBridge({
      getWorkspaceMemoryProposal: getProposal,
      applyWorkspaceMemoryProposal: applyProposal,
    });
    const onApplied = vi.fn();

    render(<WorkspaceMemoryProposalBanner workspaceId="ws-default" onApplied={onApplied} />);

    await waitFor(() => screen.getByTestId('workspace-memory-proposal-banner'));
    await act(async () => {
      fireEvent.click(screen.getByText('Apply proposal'));
    });

    expect(applyProposal).toHaveBeenCalledWith('ws-default');
    await waitFor(() => expect(onApplied).toHaveBeenCalled());
    // Banner clears itself after applying — the proposal is gone, the
    // user shouldn't have a stale "Apply" affordance to click again.
    await waitFor(() =>
      expect(screen.queryByTestId('workspace-memory-proposal-banner')).toBeNull());
  });

  it('calls discardWorkspaceMemoryProposal on Discard and does not call onApplied', async () => {
    const getProposal = vi.fn(async () => sampleProposal({}));
    const discardProposal = vi.fn(async () => {});
    installBridge({
      getWorkspaceMemoryProposal: getProposal,
      discardWorkspaceMemoryProposal: discardProposal,
    });
    const onApplied = vi.fn();

    render(<WorkspaceMemoryProposalBanner workspaceId="ws-default" onApplied={onApplied} />);

    await waitFor(() => screen.getByTestId('workspace-memory-proposal-banner'));
    await act(async () => {
      fireEvent.click(screen.getByText('Discard'));
    });

    expect(discardProposal).toHaveBeenCalledWith('ws-default');
    expect(onApplied).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(screen.queryByTestId('workspace-memory-proposal-banner')).toBeNull());
  });

  it('renders back-link tokens in the proposed pane as clickable chips that fire onOpenThread', async () => {
    const proposal = sampleProposal({
      currentMd: 'Previously.',
      proposedMd: '## Decisions\n- Use embedded browser. [thread:abc-12345-thread-id]\n',
    });
    installBridge({ getWorkspaceMemoryProposal: vi.fn(async () => proposal) });
    const onOpenThread = vi.fn();

    render(
      <WorkspaceMemoryProposalBanner
        workspaceId="ws-default"
        onApplied={() => {}}
        onOpenThread={onOpenThread}
      />);

    await waitFor(() => screen.getByTestId('workspace-memory-proposal-banner'));
    await act(async () => { fireEvent.click(screen.getByText('Show diff')); });

    // The raw [thread:...] token must not survive into the rendered
    // pane — it should have been swapped for a chip.
    expect(screen.queryByText(/\[thread:/)).toBeNull();
    const chip = screen.getByTitle('Open thread abc-12345-thread-id');
    expect(chip).toBeTruthy();

    await act(async () => { fireEvent.click(chip); });
    expect(onOpenThread).toHaveBeenCalledWith('abc-12345-thread-id');
  });

  it('renders back-link tokens as inert text when no onOpenThread handler is provided', async () => {
    const proposal = sampleProposal({
      currentMd: '',
      proposedMd: '- Promoted fact. [thread:xyz-789]\n',
    });
    installBridge({ getWorkspaceMemoryProposal: vi.fn(async () => proposal) });

    render(
      <WorkspaceMemoryProposalBanner workspaceId="ws-default" onApplied={() => {}} />);

    await waitFor(() => screen.getByTestId('workspace-memory-proposal-banner'));
    await act(async () => { fireEvent.click(screen.getByText('Show diff')); });

    // Token still gets replaced with the chip label so the markdown
    // doesn't show the raw form, but it's a <span> not a <button>.
    expect(screen.queryByText(/\[thread:/)).toBeNull();
    expect(screen.queryByRole('button', { name: /thread:xyz/ })).toBeNull();
    expect(screen.getByText(/thread:xyz-789/)).toBeTruthy();
  });

  it('surfaces the 409 drift error from apply inline', async () => {
    installBridge({
      getWorkspaceMemoryProposal: vi.fn(async () => sampleProposal({})),
      applyWorkspaceMemoryProposal: vi.fn(async () => {
        throw new Error('backend POST memory/proposal/apply returned 409: '
            + 'workspace memory has changed since this proposal was generated');
      }),
    });

    render(<WorkspaceMemoryProposalBanner workspaceId="ws-default" onApplied={() => {}} />);

    await waitFor(() => screen.getByTestId('workspace-memory-proposal-banner'));
    await act(async () => {
      fireEvent.click(screen.getByText('Apply proposal'));
    });

    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toContain('memory has changed');
    });
    // Failure leaves the banner mounted so the user can re-distill.
    expect(screen.getByTestId('workspace-memory-proposal-banner')).toBeTruthy();
  });
});

function sampleProposal(overrides: Partial<WorkspaceMemoryProposalDto>): WorkspaceMemoryProposalDto {
  return {
    workspaceId: 'ws-default',
    currentMd: 'Current memory.',
    proposedMd: '## Architecture\nProposed memory.\n',
    summariserModel: 'claude-haiku-4-5',
    promptTokens: 1_000,
    completionTokens: 400,
    costUsdMilli: 3,
    createdAt: '2026-05-22T12:00:00Z',
    ...overrides,
  };
}

function installBridge(overrides: Partial<Bridge>) {
  const getNoop = vi.fn(async (): Promise<WorkspaceMemoryProposalDto | null> => null);
  const applyNoop = vi.fn(async (): Promise<WorkspaceDto> => ({
    id: 'ws-default', name: 'ByteQuay', memoryMd: '',
    isScratch: false,
    createdAt: '2026-05-22T12:00:00Z', updatedAt: '2026-05-22T12:00:00Z',
  }));
  const discardNoop = vi.fn(async (): Promise<void> => {});
  (window as unknown as { bridge: Partial<Bridge> }).bridge = {
    getWorkspaceMemoryProposal: getNoop,
    applyWorkspaceMemoryProposal: applyNoop,
    discardWorkspaceMemoryProposal: discardNoop,
    ...overrides,
  } as Partial<Bridge>;
}
