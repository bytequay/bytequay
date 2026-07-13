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
import type { AttentionReason, CiStatus, HandledAction } from '../types';

/**
 * A dashboard row backed by the unified `pr` table (`GET /api/prs`) —
 * field-for-field compatible with the legacy `PullRequestDto` (same names,
 * same enum spellings, same ISO-string timestamps) so `prBuckets.ts`'s
 * categorization logic — and its large existing fixture-based test suite —
 * works unchanged against it. `id` is the one field that genuinely can't
 * stay compatible: the legacy numeric id was never stable across GitHub's
 * two id namespaces (search-issue vs REST pull-request), which unifying
 * onto one string id fixes as a side effect (see unified-pr-view.md's
 * dashboard migration).
 */
export type DashboardPR = {
  id: string;
  repo: string;
  number: number;
  title: string;
  author: string | null;
  htmlUrl: string;
  createdAt: string | null;
  updatedAt: string | null;
  origin: 'AUTHORED' | 'REVIEW_REQUESTED' | null;
  labels: string[];
  labelColors: Record<string, string> | null;
  draft: boolean;
  viewedAt: string | null;
  reviewedAt: string | null;
  handledAction: HandledAction | null;
  requestedReviewers: string[];
  ciStatus: CiStatus | null;
  additions: number;
  deletions: number;
  commentCount: number;
  attentionReason: AttentionReason | null;
  state: 'open' | 'closed' | 'merged' | string | null;
  closedAt: string | null;
  mergedAt: string | null;
  mergeable: boolean | null;
  mergeableState: string | null;
  headPushedAt: string | null;
  reviewerVerdicts: Record<string, string> | null;
  snoozedUntil: string | null;
  snoozeWakeReason: string | null;
  reviewState?: 'none' | 'running' | 'done' | 'stale';
};
