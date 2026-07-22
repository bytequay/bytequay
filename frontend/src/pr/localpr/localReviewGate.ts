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
import type { DevPhaseDto } from '../../types/brainView';
import type { LocalPRBundle } from '../../types/localPr';

export type BrainReviewSummary =
  | { state: 'approved' }
  | { state: 'unresolved'; unresolved?: number }
  | { state: 'pending' };

/** Authoritative task state for the Local Review promotion button. */
export type LocalReviewGate = {
  eligible: boolean;
  reason: string;
  brainReview: BrainReviewSummary;
};

/** Final renderer-side affordance for the Local Review approval callout.
 * The backend repeats every one of these checks immediately before push. */
export type LocalReviewApproval = {
  enabled: boolean;
  reason: string;
};

function unresolvedCount(meta: string): number | undefined {
  const match = meta.match(/brain unresolved\s*[·:]?\s*(\d+)/i);
  return match === null ? undefined : Number.parseInt(match[1], 10);
}

/**
 * Fail closed unless all three server-owned facts agree: the task is parked
 * at AWAITING_PUSH, Validation is complete, and Brain explicitly approved.
 * Comment resolution is intentionally not used as a proxy for Brain's verdict.
 */
export function deriveLocalReviewGate(
  currentPhase: string | null | undefined,
  devPhases: DevPhaseDto[] | null | undefined,
): LocalReviewGate {
  const validation = devPhases?.find(phase => phase.key === 'validation');
  const brain = devPhases?.find(phase => phase.key === 'brainReview');
  const brainMeta = brain?.meta?.trim() ?? '';
  const brainReview: BrainReviewSummary = brain?.status === 'done' && /^brain approved$/i.test(brainMeta)
    ? { state: 'approved' }
    : /brain unresolved/i.test(brainMeta)
      ? { state: 'unresolved', unresolved: unresolvedCount(brainMeta) }
      : { state: 'pending' };

  if (currentPhase !== 'AWAITING_PUSH') {
    return {
      eligible: false,
      reason: currentPhase === 'ADDRESSING_LOCAL_COMMENTS'
        ? 'Development is addressing local review comments.'
        : currentPhase === 'VALIDATING'
          ? 'Validation is still running.'
          : currentPhase === 'INTERNAL_REVIEW'
            ? 'Brain review is still running.'
            : currentPhase === 'NEEDS_ATTENTION'
              ? 'This task needs attention before it can be shipped.'
              : 'Local Review is not ready to ship yet.',
      brainReview,
    };
  }
  if (validation?.status !== 'done') {
    return { eligible: false, reason: 'Waiting for a completed validation pass.', brainReview };
  }
  if (brainReview.state !== 'approved') {
    if (brainReview.state === 'unresolved') {
      return {
        eligible: true,
        reason: 'Brain review exhausted its budget with unresolved findings; human approval is required.',
        brainReview,
      };
    }
    return {
      eligible: false,
      reason: 'Waiting for an explicit Brain approval.',
      brainReview,
    };
  }
  return { eligible: true, reason: 'Validation and Brain review passed.', brainReview };
}

/** Combines the task-owned gate with the two PR-owned blockers visible in the
 * bundle. Budget-exhausted Brain findings are the deliberate human override
 * path, so only non-Brain open threads block that amber approval. */
export function deriveLocalReviewApproval(
  bundle: LocalPRBundle | null | undefined,
  gate: LocalReviewGate,
): LocalReviewApproval | null {
  if (bundle?.pr.origin !== 'task' || bundle.pr.status !== 'local-open') return null;

  const openComments = bundle.comments.filter(comment => comment.parentCommentId === null
    && comment.resolvedAt === null && comment.dismissedAt === null);
  const blockingComments = gate.eligible && gate.brainReview.state === 'unresolved'
    ? openComments.filter(comment => comment.author !== 'brain')
    : openComments;
  const latestLocalCheck = bundle.checks
    .filter(check => check.kind === 'local')
    .reduce<typeof bundle.checks[number] | undefined>(
      (latest, check) => latest === undefined || check.startedAt > latest.startedAt ? check : latest,
      undefined,
    );
  const blockers = [
    ...(!gate.eligible ? [gate.reason] : []),
    ...(blockingComments.length > 0
      ? [`Resolve or dismiss ${blockingComments.length} open local review comment${blockingComments.length === 1 ? '' : 's'} before shipping.`]
      : []),
    ...(latestLocalCheck?.status === 'failed' ? ['The latest local test run failed.'] : []),
  ];
  return blockers.length > 0
    ? { enabled: false, reason: blockers.join(' ') }
    : { enabled: true, reason: gate.reason };
}
