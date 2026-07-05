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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MarkReadyPanel, parseReviewers } from './MarkReadyPanel';
import { MarkReadyPrompt } from './MarkReadyPrompt';
import { proposalAction } from './usePendingShipProposal';

afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });

describe('parseReviewers', () => {
  it('splits on commas/spaces, strips @, and dedupes', () => {
    expect(parseReviewers('octocat, @hubot  octocat')).toEqual(['octocat', 'hubot']);
  });
  it('returns empty for blank input', () => {
    expect(parseReviewers('   ')).toEqual([]);
  });
});

describe('proposalAction', () => {
  it('reads the parked action, or null when absent', () => {
    expect(proposalAction({ payloadJson: JSON.stringify({ action: 'mark_ready' }) } as never)).toBe('mark_ready');
    expect(proposalAction(null)).toBeNull();
    expect(proposalAction({ payloadJson: 'not json' } as never)).toBeNull();
  });
});

describe('MarkReadyPrompt', () => {
  it('routes to the review surface', () => {
    const onReview = vi.fn();
    render(<MarkReadyPrompt onReview={onReview} />);
    fireEvent.click(screen.getByText(/Mark ready for review/));
    expect(onReview).toHaveBeenCalledOnce();
  });
});

describe('MarkReadyPanel', () => {
  const pr = { owner: 'me', repo: 'proj', number: 7 };

  it('marks ready with no reviewers when the field is empty', async () => {
    const approveNotification = vi.fn().mockResolvedValue({ ok: true, resolution: 'approved', message: '', action: 'mark_ready' });
    (window as unknown as { bridge: unknown }).bridge = { approveNotification, openExternal: vi.fn() };
    const onMarked = vi.fn();
    render(<MarkReadyPanel notificationId="n1" pr={pr} onMarked={onMarked} />);

    expect(screen.getByText('Mark ready for review')).toBeTruthy();
    fireEvent.click(screen.getByText('Mark ready for review'));
    await waitFor(() => expect(approveNotification).toHaveBeenCalledWith('n1', '', 'mark_ready'));
    await waitFor(() => expect(onMarked).toHaveBeenCalledWith(0));
  });

  it('renders the fetched PR description as markdown', async () => {
    const fetchPullRequestDetail = vi.fn().mockResolvedValue({ number: 7, body: '## Summary\n\n- did a thing' });
    (window as unknown as { bridge: unknown }).bridge = {
      approveNotification: vi.fn(), openExternal: vi.fn(), fetchPullRequestDetail,
    };
    render(<MarkReadyPanel notificationId="n1" pr={pr} onMarked={vi.fn()} />);

    expect(await screen.findByText('Summary')).toBeTruthy();
    expect(screen.getByText('did a thing')).toBeTruthy();
    expect(fetchPullRequestDetail).toHaveBeenCalledWith('me/proj', 7);
  });

  it('sends typed reviewers through approve and reflects the count', async () => {
    const approveNotification = vi.fn().mockResolvedValue({ ok: true, resolution: 'approved', message: '', action: 'mark_ready' });
    (window as unknown as { bridge: unknown }).bridge = { approveNotification, openExternal: vi.fn() };
    const onMarked = vi.fn();
    render(<MarkReadyPanel notificationId="n1" pr={pr} onMarked={onMarked} />);

    fireEvent.change(screen.getByLabelText('Reviewers'), { target: { value: 'octocat, hubot' } });
    fireEvent.click(screen.getByText('Mark ready & request 2 reviewers'));
    await waitFor(() => expect(approveNotification).toHaveBeenCalledWith('n1', 'octocat, hubot', 'mark_ready'));
    await waitFor(() => expect(onMarked).toHaveBeenCalledWith(2));
  });

  it('offers an @ reviewer picker from suggested reviewers and inserts the pick', async () => {
    const getSuggestedReviewers = vi.fn().mockResolvedValue([
      { login: 'octocat', avatarUrl: null, name: null, isAuthor: false, isCommenter: false },
      { login: 'hubot', avatarUrl: null, name: null, isAuthor: false, isCommenter: false },
    ]);
    (window as unknown as { bridge: unknown }).bridge = {
      approveNotification: vi.fn(), openExternal: vi.fn(), getSuggestedReviewers,
    };
    render(<MarkReadyPanel notificationId="n1" pr={pr} onMarked={vi.fn()} />);
    await waitFor(() => expect(getSuggestedReviewers).toHaveBeenCalledWith('me/proj', 7));

    const field = screen.getByLabelText('Reviewers') as HTMLTextAreaElement;
    fireEvent.change(field, { target: { value: '@oc', selectionStart: 3 } });
    // 'oc' matches octocat, not hubot.
    expect(screen.queryByText('@hubot')).toBeNull();
    fireEvent.mouseDown(await screen.findByText('@octocat'));
    expect((screen.getByLabelText('Reviewers') as HTMLTextAreaElement).value).toBe('@octocat ');
  });

  it('surfaces a non-approved resolution instead of navigating away', async () => {
    const approveNotification = vi.fn().mockResolvedValue({ ok: false, resolution: 'failed', message: 'boom', action: 'mark_ready' });
    (window as unknown as { bridge: unknown }).bridge = { approveNotification, openExternal: vi.fn() };
    const onMarked = vi.fn();
    render(<MarkReadyPanel notificationId="n1" pr={pr} onMarked={onMarked} />);

    fireEvent.click(screen.getByText('Mark ready for review'));
    await waitFor(() => expect(screen.getByText('boom')).toBeTruthy());
    expect(onMarked).not.toHaveBeenCalled();
  });

  it('opens the live PR on GitHub', () => {
    const openExternal = vi.fn();
    (window as unknown as { bridge: unknown }).bridge = { approveNotification: vi.fn(), openExternal };
    render(<MarkReadyPanel notificationId="n1" pr={pr} onMarked={vi.fn()} />);
    fireEvent.click(screen.getByText('me/proj#7 ↗'));
    expect(openExternal).toHaveBeenCalledWith('https://github.com/me/proj/pull/7');
  });
});
