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
import { describe, expect, it } from 'vitest';
import type { DevPhaseDto } from '../../types/brainView';
import type { LocalPRBundle, LocalPRCheck, LocalPRComment } from '../../types/localPr';
import { deriveLocalReviewApproval, deriveLocalReviewGate } from './localReviewGate';

function phases(brainMeta: string | null, validation: DevPhaseDto['status'] = 'done'): DevPhaseDto[] {
  return [
    { key: 'implementing', status: 'done', meta: null, badgeRunId: null },
    { key: 'validation', status: validation, meta: null, badgeRunId: null },
    { key: 'brainReview', status: brainMeta === null ? 'running' : 'done', meta: brainMeta, badgeRunId: null },
  ];
}

describe('deriveLocalReviewGate', () => {
  it('opens the green path only from AWAITING_PUSH with validation and explicit Brain approval', () => {
    expect(deriveLocalReviewGate('AWAITING_PUSH', phases('brain approved'))).toEqual({
      eligible: true,
      reason: 'Validation and Brain review passed.',
      brainReview: { state: 'approved' },
    });
  });

  it('keeps Brain budget exhaustion as an explicit amber human decision path', () => {
    expect(deriveLocalReviewGate('AWAITING_PUSH', phases('brain unresolved · 3'))).toEqual({
      eligible: true,
      reason: 'Brain review exhausted its budget with unresolved findings; human approval is required.',
      brainReview: { state: 'unresolved', unresolved: 3 },
    });
  });

  it.each([
    ['ADDRESSING_LOCAL_COMMENTS', phases('brain approved')],
    ['VALIDATING', phases('brain approved', 'running')],
    ['AWAITING_PUSH', phases(null)],
    ['AWAITING_PUSH', []],
  ])('fails closed while phase/validation/Brain authority is incomplete (%s)', (phase, devPhases) => {
    expect(deriveLocalReviewGate(phase, devPhases).eligible).toBe(false);
  });
});

const approvedGate = deriveLocalReviewGate('AWAITING_PUSH', phases('brain approved'));

function localBundle({
  checks = [], comments = [], status = 'local-open', origin = 'task',
}: {
  checks?: LocalPRCheck[];
  comments?: LocalPRComment[];
  status?: LocalPRBundle['pr']['status'];
  origin?: LocalPRBundle['pr']['origin'];
} = {}): LocalPRBundle {
  return {
    pr: {
      id: 'pr-1', taskId: 'task-1', branchName: 'dev/task-1', baseBranch: 'main',
      title: 'Task change', description: 'Description', status, createdAt: 0,
      pushedAt: null, remotePrNumber: null, remotePrUrl: null, mergedAt: null,
      closedAt: null, origin, repo: 'owner/repo', author: 'agent', syncedAt: null,
      syncedAdditions: null, syncedDeletions: null, syncedMergeable: null,
      syncedMergeableState: null, syncedMergeQueueEnabled: false,
      syncedMergeQueueState: null, branchDeletedAt: null,
    },
    commits: [], timeline: [], checks, comments,
  };
}

function check(status: LocalPRCheck['status'], startedAt: number): LocalPRCheck {
  return {
    id: `check-${startedAt}`, localPrId: 'pr-1', kind: 'local', name: 'Tests',
    status, durationMs: null, startedAt, finishedAt: null, runId: null,
  };
}

function comment(author = 'you'): LocalPRComment {
  return {
    id: `comment-${author}`, localPrId: 'pr-1', origin: 'local', scope: 'pr',
    filePath: null, lineNumber: null, side: 'RIGHT', startLine: null, startSide: null,
    author, body: 'Please fix this.', createdAt: 0, resolvedAt: null, dismissedAt: null,
    strippedOnPushAt: null, parentCommentId: null, publishedAt: null,
  };
}

describe('deriveLocalReviewApproval', () => {
  it('opens only the current task-origin local review', () => {
    expect(deriveLocalReviewApproval(localBundle(), approvedGate)).toEqual({
      enabled: true, reason: 'Validation and Brain review passed.',
    });
    expect(deriveLocalReviewApproval(localBundle({ status: 'local-drafted' }), approvedGate)).toBeNull();
    expect(deriveLocalReviewApproval(localBundle({ origin: 'external' }), approvedGate)).toBeNull();
  });

  it('fails closed on the task gate, human comments, and latest failed check', () => {
    const pendingGate = deriveLocalReviewGate('INTERNAL_REVIEW', phases(null));
    expect(deriveLocalReviewApproval(localBundle(), pendingGate)?.reason)
      .toContain('Brain review is still running.');
    expect(deriveLocalReviewApproval(localBundle({ comments: [comment()] }), approvedGate)).toEqual({
      enabled: false,
      reason: 'Resolve or dismiss 1 open local review comment before shipping.',
    });
    expect(deriveLocalReviewApproval(localBundle({
      checks: [check('failed', 1), check('passed', 2), check('failed', 3)],
    }), approvedGate)?.reason).toContain('The latest local test run failed.');
    expect(deriveLocalReviewApproval(localBundle({
      checks: [check('failed', 1), check('passed', 2)],
    }), approvedGate)?.enabled).toBe(true);
  });

  it('allows explicit human escalation when only budget-exhausted Brain findings remain', () => {
    const escalated = deriveLocalReviewGate('AWAITING_PUSH', phases('brain unresolved · 1'));
    expect(deriveLocalReviewApproval(localBundle({ comments: [comment('brain')] }), escalated)).toEqual({
      enabled: true,
      reason: 'Brain review exhausted its budget with unresolved findings; human approval is required.',
    });
  });
});
