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
import { PRView } from './PRView';
import { derivePRCapabilities } from '../prCapabilities';
import type {
  LocalPR,
  LocalPRBundle,
  LocalPRCheck,
  LocalPRComment,
  LocalPRStatus,
  LocalPRTimelineEvent,
} from '../../types/localPr';

afterEach(cleanup);

function pr(status: LocalPRStatus, over: Partial<LocalPR> = {}): LocalPR {
  return {
    id: 'pr1', taskId: 't1', branchName: 'feat/x', baseBranch: 'main',
    title: 'Add cost-meter card', description: 'Adds a `CostMeterCard`.',
    status, createdAt: Date.now(), pushedAt: null, remotePrNumber: null,
    remotePrUrl: null, mergedAt: null, closedAt: null,
    origin: 'task', repo: null, author: null, syncedAt: null,
    syncedAdditions: null, syncedDeletions: null,
    syncedMergeable: null, syncedMergeableState: null, syncedMergeQueueEnabled: false, syncedMergeQueueState: null, branchDeletedAt: null, ...over,
  };
}

function event(over: Partial<LocalPRTimelineEvent> & Pick<LocalPRTimelineEvent, 'eventType'>): LocalPRTimelineEvent {
  return {
    id: `e${Math.round(over.createdAt ?? 1)}`, localPrId: 'pr1', actor: 'claude-code',
    isLocalOnly: false, strippedOnPushAt: null, createdAt: Date.now(), payload: null, ...over,
  };
}

function check(over: Partial<LocalPRCheck> & Pick<LocalPRCheck, 'kind' | 'status'>): LocalPRCheck {
  return {
    id: `c-${over.kind}-${over.status}`, localPrId: 'pr1', name: 'mvn verify',
    durationMs: 28000, startedAt: Date.now(), finishedAt: Date.now(), runId: null, ...over,
  };
}

function comment(over: Partial<LocalPRComment> = {}): LocalPRComment {
  return {
    id: 'cm1', localPrId: 'pr1', origin: 'local', scope: 'pr', filePath: null,
    lineNumber: null, side: 'RIGHT', startLine: null, startSide: null,
    author: 'you', body: 'hi', createdAt: Date.now(),
    resolvedAt: null, dismissedAt: null, strippedOnPushAt: null, parentCommentId: null,
    publishedAt: null, ...over,
  };
}

function bundle(over: Partial<LocalPRBundle> & { pr: LocalPR }): LocalPRBundle {
  return { commits: [], timeline: [], checks: [], comments: [], ...over };
}

const noop = () => { /* noop */ };

/** Renders `<PRView>` for the given bundle on the `task` surface — the
 *  capabilities every existing caller (StageDetailRoute/TaskBrainRoute)
 *  actually derives. */
function renderView(b: LocalPRBundle, props: Partial<Parameters<typeof PRView>[0]> = {}) {
  return render(
    <PRView
      bundle={b}
      capabilities={derivePRCapabilities(b.pr, 'task')}
      commentValue="" onCommentChange={noop}
      syncedAt={null} syncing={false} onRefresh={noop}
      {...props}
    />,
  );
}

describe('PRView', () => {
  it('renders the local amber state pill and the push gate', () => {
    renderView(bundle({ pr: pr('local-open') }), { onPush: noop, onAskAgent: noop });
    const pill = document.querySelector('.pr-state-pill');
    expect(pill?.className).toContain('local');
    expect(screen.getByText(/Approve & push to GitHub/)).toBeTruthy();
    expect(screen.queryByText(/Merge pull request/)).toBeNull();
    expect(screen.getByText(/won't be posted to GitHub/)).toBeTruthy();
    expect(screen.getByText('#local')).toBeTruthy();
  });

  it('renders the green open state pill and the merge gate', () => {
    renderView(bundle({ pr: pr('remote-open', { remotePrNumber: 145 }) }), { username: 'chenjian2664', onMerge: noop });
    const pill = document.querySelector('.pr-state-pill');
    expect(pill?.className).toContain('open');
    expect(screen.getByText(/Squash and merge/)).toBeTruthy();
    expect(screen.getByText('#145')).toBeTruthy();
    expect(screen.getByText(/Posts to GitHub as @chenjian2664/)).toBeTruthy();
  });

  it('disables Merge while an open comment remains and enables Merge anyway', () => {
    const onMerge = vi.fn();
    renderView(
      bundle({ pr: pr('remote-open', { remotePrNumber: 145 }), comments: [comment()] }),
      { onMerge },
    );
    const merge = screen.getByText(/Squash and merge/).closest('button') as HTMLButtonElement;
    expect(merge.disabled).toBe(true);
    fireEvent.click(merge);
    expect(onMerge).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /Merge anyway/ })).toBeTruthy();
    expect(screen.getByText(/open comment.*resolve before merge/)).toBeTruthy();
  });

  it('disables push while an open comment remains (promotion gate)', () => {
    const onPush = vi.fn();
    renderView(bundle({ pr: pr('local-open'), comments: [comment()] }), { onPush, onAskAgent: noop });
    const push = screen.getByText(/Approve & push to GitHub/).closest('button') as HTMLButtonElement;
    expect(push.disabled).toBe(true);
    fireEvent.click(push);
    expect(onPush).not.toHaveBeenCalled();
  });

  it('disables push while the latest local test run is failing', () => {
    const onPush = vi.fn();
    renderView(bundle({
      pr: pr('local-open'),
      checks: [
        check({ kind: 'local', status: 'passed', startedAt: 1 }),
        check({ kind: 'local', status: 'failed', startedAt: 2 }),
      ],
    }), { onPush, onAskAgent: noop });
    const push = screen.getByText(/Approve & push to GitHub/).closest('button') as HTMLButtonElement;
    expect(push.disabled).toBe(true);
  });

  it('enables push once the latest local test run passes even after an earlier failure', () => {
    const onPush = vi.fn();
    renderView(bundle({
      pr: pr('local-open'),
      checks: [
        check({ kind: 'local', status: 'failed', startedAt: 1 }),
        check({ kind: 'local', status: 'passed', startedAt: 2 }),
      ],
    }), { onPush, onAskAgent: noop });
    const push = screen.getByText(/Approve & push to GitHub/).closest('button') as HTMLButtonElement;
    expect(push.disabled).toBe(false);
    fireEvent.click(push);
    expect(onPush).toHaveBeenCalledOnce();
  });

  it('marks local-only timeline events with the local lock tag', () => {
    const events: LocalPRTimelineEvent[] = [
      event({ eventType: 'commit', isLocalOnly: true, createdAt: 1,
        payload: { sha: '4b2a1f0', message: 'Extend composer' } }),
      event({ eventType: 'ci', isLocalOnly: false, createdAt: 2,
        payload: { name: 'GitHub Actions', status: 'passed' } }),
    ];
    renderView(bundle({ pr: pr('local-open'), timeline: events }));
    const rows = document.querySelectorAll('.pr-tl-icon-row');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('.lock-tag')).not.toBeNull();
    expect(rows[1].querySelector('.lock-tag')).toBeNull();
    expect(screen.getByText('4b2a1f0')).toBeTruthy();
  });

  it('shows a failed CI icon in red', () => {
    renderView(bundle({
      pr: pr('remote-open', { remotePrNumber: 1 }),
      timeline: [event({ eventType: 'ci', payload: { name: 'ci', status: 'failed' } })],
    }));
    expect(document.querySelector('.pr-tl-icon-row .tic.fail')).not.toBeNull();
  });

  it('groups local and remote checks under the merge box', () => {
    const checks: LocalPRCheck[] = [
      check({ kind: 'local', status: 'passed', name: 'mvn verify' }),
      check({ kind: 'remote', status: 'passed', name: 'backend / tests' }),
    ];
    renderView(bundle({ pr: pr('local-open'), checks }));
    // The checks list is collapsed by default (matching github.com) — expand it.
    fireEvent.click(document.querySelector('.pr-merge-box .mb-sec.clickable') as Element);
    expect(screen.getByText('mvn verify')).toBeTruthy();
    expect(screen.getByText('backend / tests')).toBeTruthy();
    expect(screen.getByText('LOCAL')).toBeTruthy();
    expect(screen.getByText('REMOTE')).toBeTruthy();
  });

  it('hides the merge box, run-tests button and comment composer on the Commits/Checks tabs', () => {
    const onRunTests = vi.fn();
    renderView(bundle({ pr: pr('local-open'), checks: [check({ kind: 'local', status: 'passed' })] }), { onRunTests });
    expect(document.querySelector('.pr-merge-box')).not.toBeNull();
    expect(screen.getByRole('button', { name: /Run tests/ })).toBeTruthy();
    expect(document.querySelector('.pr-comment-composer, textarea')).not.toBeNull();

    fireEvent.click(screen.getByRole('tab', { name: /Commits/ }));

    expect(document.querySelector('.pr-merge-box')).toBeNull();
    expect(screen.queryByRole('button', { name: /Run tests/ })).toBeNull();
  });

  it('openSubTabRequest force-switches to the Checks tab (the CI validation node)', () => {
    renderView(
      bundle({ pr: pr('local-open'), checks: [check({ kind: 'local', status: 'passed' })] }),
      { openSubTabRequest: { subTab: 'checks', token: 1 } },
    );
    expect(screen.getByRole('tab', { name: /Checks/ }).getAttribute('aria-selected')).toBe('true');
  });

  it('fires onRunTests and shows a busy label', () => {
    const onRunTests = vi.fn();
    const { rerender } = renderView(bundle({ pr: pr('local-open') }), { onRunTests });
    const button = screen.getByRole('button', { name: 'Run tests' });
    fireEvent.click(button);
    expect(onRunTests).toHaveBeenCalledOnce();

    rerender(
      <PRView
        bundle={bundle({ pr: pr('local-open') })}
        capabilities={derivePRCapabilities(pr('local-open'), 'task')}
        commentValue="" onCommentChange={noop} syncedAt={null} syncing={false} onRefresh={noop}
        onRunTests={onRunTests} runTestsBusy
      />,
    );
    expect(screen.getByRole('button', { name: 'Running tests…' })).toBeTruthy();
  });

  it('drives the comment composer via props and fires submit on ⌘↵', () => {
    const onSubmit = vi.fn();
    const onChange = vi.fn();
    renderView(bundle({ pr: pr('local-open') }), { commentValue: 'ship it', onCommentChange: onChange, onAddComment: onSubmit });
    const textarea = document.querySelector('textarea.cc-input') as HTMLTextAreaElement;
    expect(textarea.value).toBe('ship it');
    fireEvent.keyDown(textarea, { key: 'Enter', metaKey: true });
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('renders no merge-box gate while the agent is still drafting', () => {
    renderView(bundle({ pr: pr('local-drafted') }));
    expect(screen.queryByText(/Approve & push/)).toBeNull();
    expect(screen.queryByText(/Merge pull request/)).toBeNull();
  });

  it('shows "Brain-reviewed" once the brain\'s dev-end comments are all resolved', () => {
    renderView(
      bundle({ pr: pr('local-open'), comments: [comment({ id: 'b1', author: 'brain', resolvedAt: Date.now() })] }),
      { onPush: noop, onAskAgent: noop },
    );
    expect(screen.getByText('✓ Brain-reviewed')).toBeTruthy();
  });

  it('shows "brain unresolved · N" when the brain escalated with open comments', () => {
    renderView(
      bundle({
        pr: pr('local-open'),
        comments: [
          comment({ id: 'b1', author: 'brain', resolvedAt: null }),
          comment({ id: 'b2', author: 'brain', resolvedAt: null }),
        ],
      }),
      { onPush: noop, onAskAgent: noop },
    );
    expect(screen.getByText('◆ brain unresolved · 2')).toBeTruthy();
  });

  it('shows no brain-review tag when the brain never reviewed this PR', () => {
    renderView(bundle({ pr: pr('local-open') }), { onPush: noop, onAskAgent: noop });
    expect(document.querySelector('.brain-review-tag')).toBeNull();
  });

  it('renders a brain review timeline event as a person-event with its verdict', () => {
    renderView(bundle({
      pr: pr('local-open'),
      timeline: [event({
        eventType: 'review', actor: 'brain', isLocalOnly: true,
        payload: { scope: 'dev', verdict: 'changes_requested', iteration: 1 },
      })],
    }), { onPush: noop, onAskAgent: noop });
    expect(screen.getByText(/reviewed/)).toBeTruthy();
  });

  it('force-refreshes the GitHub conversation feed alongside the local bundle on Sync', async () => {
    const fetchPullRequestDetail = vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] });
    const refreshPullRequestDetail = vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] });
    window.bridge = { fetchPullRequestDetail, refreshPullRequestDetail } as unknown as typeof window.bridge;
    const onRefresh = vi.fn();

    renderView(bundle({
      pr: pr('remote-open', { remotePrNumber: 145, repo: 'acme/widget', origin: 'external' }),
    }), { onRefresh });
    await vi.waitFor(() => expect(fetchPullRequestDetail).toHaveBeenCalledOnce());

    fireEvent.click(document.querySelector('.pr-sync-chip') as Element);

    expect(onRefresh).toHaveBeenCalledOnce();
    expect(refreshPullRequestDetail).toHaveBeenCalledOnce();
    expect(refreshPullRequestDetail).toHaveBeenCalledWith('acme/widget', 145);
  });
});
