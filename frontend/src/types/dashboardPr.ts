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
 * same enum spellings) so `prBuckets.ts`'s categorization logic works
 * unchanged against it, modulo one deliberate difference: `id` is the
 * unified PR's string id (not two different GitHub numeric-id namespaces —
 * see unified-pr-view.md's dashboard migration), and every timestamp is
 * epoch-millis (matching the rest of the unified PR wire format) rather
 * than an ISO string.
 */
export type DashboardPR = {
  id: string;
  repo: string;
  number: number;
  title: string;
  author: string | null;
  htmlUrl: string;
  createdAt: number | null;
  updatedAt: number | null;
  origin: 'AUTHORED' | 'REVIEW_REQUESTED' | null;
  labels: string[];
  labelColors: Record<string, string> | null;
  draft: boolean;
  viewedAt: number | null;
  reviewedAt: number | null;
  handledAction: HandledAction | null;
  requestedReviewers: string[];
  ciStatus: CiStatus | null;
  additions: number;
  deletions: number;
  commentCount: number;
  attentionReason: AttentionReason | null;
  state: 'open' | 'closed' | 'merged' | string | null;
  closedAt: number | null;
  mergedAt: number | null;
  mergeable: boolean | null;
  mergeableState: string | null;
  headPushedAt: number | null;
  reviewerVerdicts: Record<string, string> | null;
  snoozedUntil: number | null;
  snoozeWakeReason: string | null;
};
