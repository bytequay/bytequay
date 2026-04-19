import type { PullRequestDto } from './types';

/** What the repo-detail effect should do for the deep-link, given the
 *  fresh pulls list and the requested PR number. */
export type DeepLinkDecision =
  | { kind: 'noop' }
  /** PR is in the fresh list — select it directly, no extra fetch. */
  | { kind: 'select'; pr: PullRequestDto }
  /** PR isn't in the (capped) list — caller should fetch it via
   *  {@link window.bridge.getRepoPull} and prepend the result to pulls. */
  | { kind: 'fallback'; number: number };

/**
 * Pure decision function for the deep-link auto-select on RepoDetailPage.
 * Extracted so the noop / select / fallback branches can be unit-tested
 * without rendering the component — covers the regression class that hit
 * the user (the page silently dropping deep-links when the PR was past
 * the 50-row cap on {@code listPullRequests}).
 */
export function decideDeepLinkSelection(
  pulls: PullRequestDto[],
  initialPrNumber: number | null | undefined,
): DeepLinkDecision {
  if (initialPrNumber == null) return { kind: 'noop' };
  const match = pulls.find(p => p.number === initialPrNumber);
  if (match) return { kind: 'select', pr: match };
  return { kind: 'fallback', number: initialPrNumber };
}
