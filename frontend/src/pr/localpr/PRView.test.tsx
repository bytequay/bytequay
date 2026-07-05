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
    remotePrUrl: null, mergedAt: null, closedAt: null, ...over,
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
    lineNumber: null, author: 'you', body: 'hi', createdAt: Date.now(),
    resolvedAt: null, dismissedAt: null, strippedOnPushAt: null, parentCommentId: null, ...over,
  };
}

function bundle(over: Partial<LocalPRBundle> & { pr: LocalPR }): LocalPRBundle {
  return { commits: [], timeline: [], checks: [], comments: [], ...over };
}

const noop = () => { /* noop */ };

describe('PRView', () => {
  it('renders the local-open amber badge with a lock and the push action bar', () => {
    render(
      <PRView
        mode="local"
        bundle={bundle({ pr: pr('local-open') })}
        commentValue="" onCommentChange={noop} onPush={noop} onAskAgent={noop}
      />,
    );
    const badge = document.querySelector('.pr-status-badge');
    expect(badge?.className).toContain('local-open');
    expect(badge?.querySelector('.lock')).not.toBeNull();
    // Action bar offers Approve & push, not Merge.
    expect(screen.getByText(/Approve & push to GitHub/)).toBeTruthy();
    expect(screen.queryByText(/Merge pull request/)).toBeNull();
    // Local composer hint warns nothing posts to GitHub.
    expect(screen.getByText(/won't be posted to GitHub/)).toBeTruthy();
    // The PR number label is #local until pushed.
    expect(screen.getByText('#local')).toBeTruthy();
  });

  it('renders the remote-open green badge (no lock) and the merge action bar', () => {
    render(
      <PRView
        mode="remote"
        bundle={bundle({ pr: pr('remote-open', { remotePrNumber: 145 }) })}
        commentValue="" onCommentChange={noop} username="chenjian2664" onMerge={noop}
      />,
    );
    const badge = document.querySelector('.pr-status-badge');
    expect(badge?.className).toContain('remote-open');
    expect(badge?.querySelector('.lock')).toBeNull();
    expect(screen.getByText(/Merge pull request/)).toBeTruthy();
    expect(screen.getByText('#145')).toBeTruthy();
    // Remote composer posts to GitHub as the user.
    expect(screen.getByText(/posts to GitHub as @chenjian2664/)).toBeTruthy();
  });

  it('disables Merge while an open comment remains and enables Merge anyway', () => {
    const onMerge = vi.fn();
    render(
      <PRView
        mode="remote"
        bundle={bundle({ pr: pr('remote-open', { remotePrNumber: 145 }), comments: [comment()] })}
        commentValue="" onCommentChange={noop} onMerge={onMerge} onMergeAnyway={noop}
      />,
    );
    const merge = screen.getByText(/Merge pull request/).closest('button') as HTMLButtonElement;
    expect(merge.disabled).toBe(true);
    fireEvent.click(merge);
    expect(onMerge).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /Merge anyway/ })).toBeTruthy();
    // The head surfaces the gating reason ("N open comment · resolve before merge").
    expect(screen.getByText(/open comment · resolve before merge/)).toBeTruthy();
  });

  it('disables push while an open comment remains (promotion gate)', () => {
    const onPush = vi.fn();
    render(
      <PRView
        mode="local"
        bundle={bundle({ pr: pr('local-open'), comments: [comment()] })}
        commentValue="" onCommentChange={noop} onPush={onPush} onAskAgent={noop}
      />,
    );
    const push = screen.getByText(/Approve & push to GitHub/).closest('button') as HTMLButtonElement;
    expect(push.disabled).toBe(true);
    fireEvent.click(push);
    expect(onPush).not.toHaveBeenCalled();
    expect(screen.getByText(/Resolve.*or dismiss them/)).toBeTruthy();
  });

  it('disables push while the latest local test run is failing', () => {
    const onPush = vi.fn();
    render(
      <PRView
        mode="local"
        bundle={bundle({
          pr: pr('local-open'),
          checks: [
            check({ kind: 'local', status: 'passed', startedAt: 1 }),
            check({ kind: 'local', status: 'failed', startedAt: 2 }),
          ],
        })}
        commentValue="" onCommentChange={noop} onPush={onPush} onAskAgent={noop}
      />,
    );
    const push = screen.getByText(/Approve & push to GitHub/).closest('button') as HTMLButtonElement;
    expect(push.disabled).toBe(true);
    expect(screen.getByText(/Local tests are currently/)).toBeTruthy();
  });

  it('enables push once the latest local test run passes even after an earlier failure', () => {
    const onPush = vi.fn();
    render(
      <PRView
        mode="local"
        bundle={bundle({
          pr: pr('local-open'),
          checks: [
            check({ kind: 'local', status: 'failed', startedAt: 1 }),
            check({ kind: 'local', status: 'passed', startedAt: 2 }),
          ],
        })}
        commentValue="" onCommentChange={noop} onPush={onPush} onAskAgent={noop}
      />,
    );
    const push = screen.getByText(/Approve & push to GitHub/).closest('button') as HTMLButtonElement;
    expect(push.disabled).toBe(false);
    fireEvent.click(push);
    expect(onPush).toHaveBeenCalledOnce();
  });

  it('marks local-only timeline events (lock) and dims pre-push history in remote mode', () => {
    const events: LocalPRTimelineEvent[] = [
      event({ eventType: 'commit', isLocalOnly: true, createdAt: 1,
        payload: { sha: '4b2a1f0', message: 'Extend composer', additions: 18, deletions: 14 } }),
      event({ eventType: 'ci', isLocalOnly: false, createdAt: 2,
        payload: { name: 'GitHub Actions', status: 'passed', durationMs: 204000 } }),
    ];
    const { rerender } = render(
      <PRView mode="local" bundle={bundle({ pr: pr('local-open'), timeline: events })}
        commentValue="" onCommentChange={noop} />,
    );
    const rows = document.querySelectorAll('.pr-timeline-event');
    expect(rows.length).toBe(2);
    expect(rows[0].className).toContain('local-only');
    // Local mode does not dim.
    expect((rows[0] as HTMLElement).style.opacity).toBe('');
    // Nested commit sha (short) renders.
    expect(screen.getByText('4b2a1f0')).toBeTruthy();

    rerender(
      <PRView mode="remote" bundle={bundle({ pr: pr('remote-open', { remotePrNumber: 1 }), timeline: events })}
        commentValue="" onCommentChange={noop} />,
    );
    // Promoted: the local-only history folds away — only the GitHub event
    // shows inline, the local segment hides under "Local development".
    expect(document.querySelectorAll('.pr-timeline-event').length).toBe(1);
    expect(screen.queryByText('4b2a1f0')).toBeNull();
    const fold = screen.getByRole('button', { name: /Local development/ });
    expect(fold.textContent).toContain('1 event');
    fireEvent.click(fold);
    const remoteRows = document.querySelectorAll('.pr-timeline-event');
    expect(remoteRows.length).toBe(2);
    // Expanded, the local-only row is dimmed; the remote row is full opacity.
    expect((remoteRows[0] as HTMLElement).style.opacity).toBe('0.55');
    expect((remoteRows[1] as HTMLElement).style.opacity).toBe('');
    expect(screen.getByText('4b2a1f0')).toBeTruthy();
  });

  it('shows a failed CI icon in red', () => {
    render(
      <PRView mode="remote"
        bundle={bundle({ pr: pr('remote-open', { remotePrNumber: 1 }),
          timeline: [event({ eventType: 'ci', payload: { name: 'ci', status: 'failed' } })] })}
        commentValue="" onCommentChange={noop} />,
    );
    expect(document.querySelector('.tl-icon.ci.fail')).not.toBeNull();
  });

  it('emphasises LOCAL checks in local mode and dims REMOTE, and vice-versa', () => {
    const checks: LocalPRCheck[] = [
      check({ kind: 'local', status: 'passed', name: 'mvn verify' }),
      check({ kind: 'remote', status: 'passed', name: 'backend / tests' }),
    ];
    const { rerender } = render(
      <PRView mode="local" bundle={bundle({ pr: pr('local-open'), checks })}
        commentValue="" onCommentChange={noop} />,
    );
    let cards = document.querySelectorAll('.pr-checks-card');
    // Card 0 = LOCAL (not dim), card 1 = REMOTE (dim) in local mode.
    expect((cards[0] as HTMLElement).style.opacity).toBe('');
    expect((cards[1] as HTMLElement).style.opacity).toBe('0.5');

    rerender(
      <PRView mode="remote" bundle={bundle({ pr: pr('remote-open', { remotePrNumber: 1 }), checks })}
        commentValue="" onCommentChange={noop} />,
    );
    cards = document.querySelectorAll('.pr-checks-card');
    expect((cards[0] as HTMLElement).style.opacity).toBe('0.5');
    expect((cards[1] as HTMLElement).style.opacity).toBe('');
  });

  it('fires onRunTests from the local checks card only, and shows a busy label', () => {
    const onRunTests = vi.fn();
    const { rerender } = render(
      <PRView mode="local" bundle={bundle({ pr: pr('local-open') })}
        commentValue="" onCommentChange={noop} onRunTests={onRunTests} />,
    );
    const buttons = screen.getAllByRole('button', { name: 'Run tests' });
    expect(buttons).toHaveLength(1); // only the local card gets the button.
    fireEvent.click(buttons[0]);
    expect(onRunTests).toHaveBeenCalledOnce();

    rerender(
      <PRView mode="local" bundle={bundle({ pr: pr('local-open') })}
        commentValue="" onCommentChange={noop} onRunTests={onRunTests} runTestsBusy />,
    );
    expect(screen.getByRole('button', { name: 'Running…' })).toBeTruthy();
  });

  it('drives the comment composer via props and fires submit on ⌘↵', () => {
    const onSubmit = vi.fn();
    const onChange = vi.fn();
    render(
      <PRView mode="local" bundle={bundle({ pr: pr('local-open') })}
        commentValue="ship it" onCommentChange={onChange} onAddComment={onSubmit} />,
    );
    const textarea = document.querySelector('textarea.cc-input') as HTMLTextAreaElement;
    expect(textarea.value).toBe('ship it');
    fireEvent.keyDown(textarea, { key: 'Enter', metaKey: true });
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('renders the drafting description treatment in local-drafted', () => {
    render(
      <PRView mode="local" bundle={bundle({ pr: pr('local-drafted') })}
        commentValue="" onCommentChange={noop} />,
    );
    expect(document.querySelector('.pr-description.drafting')).not.toBeNull();
    // The pulsing dot badge, no lock, in local-drafted.
    const badge = document.querySelector('.pr-status-badge.local-drafted');
    expect(badge?.querySelector('.d')).not.toBeNull();
    // No action bar while the agent is still drafting.
    expect(document.querySelector('.pr-action-bar')).toBeNull();
  });

  it('shows "Brain-reviewed" once the brain\'s dev-end comments are all resolved', () => {
    render(
      <PRView
        mode="local"
        bundle={bundle({
          pr: pr('local-open'),
          comments: [comment({ id: 'b1', author: 'brain', resolvedAt: Date.now() })],
        })}
        commentValue="" onCommentChange={noop} onPush={noop} onAskAgent={noop}
      />,
    );
    expect(screen.getByText('✓ Brain-reviewed')).toBeTruthy();
  });

  it('shows "brain unresolved · N" when the brain escalated with open comments', () => {
    render(
      <PRView
        mode="local"
        bundle={bundle({
          pr: pr('local-open'),
          comments: [
            comment({ id: 'b1', author: 'brain', resolvedAt: null }),
            comment({ id: 'b2', author: 'brain', resolvedAt: null }),
          ],
        })}
        commentValue="" onCommentChange={noop} onPush={noop} onAskAgent={noop}
      />,
    );
    expect(screen.getByText('◆ brain unresolved · 2')).toBeTruthy();
  });

  it('shows no brain-review tag when the brain never reviewed this PR', () => {
    render(
      <PRView
        mode="local"
        bundle={bundle({ pr: pr('local-open') })}
        commentValue="" onCommentChange={noop} onPush={noop} onAskAgent={noop}
      />,
    );
    expect(document.querySelector('.brain-review-tag')).toBeNull();
  });

  it('renders a brain review timeline event with its BRAIN badge and verdict', () => {
    render(
      <PRView
        mode="local"
        bundle={bundle({
          pr: pr('local-open'),
          timeline: [event({
            eventType: 'review', actor: 'brain', isLocalOnly: true,
            payload: { scope: 'dev', verdict: 'changes_requested', iteration: 1 },
          })],
        })}
        commentValue="" onCommentChange={noop} onPush={noop} onAskAgent={noop}
      />,
    );
    expect(screen.getByText('BRAIN')).toBeTruthy();
    expect(screen.getByText(/reviewed the diff/)).toBeTruthy();
    expect(screen.getByText('CHANGES REQUESTED')).toBeTruthy();
  });
});
