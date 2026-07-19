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
import type { DashboardPR } from '../types/dashboardPr';
import type { LocalPR } from '../types/localPr';
import { toRow, type PullRow } from './model';

/**
 * Adapts the unified local-PR record to the one locked PR-detail view model.
 * Standalone deep links and task/stage panes intentionally share this path.
 */
export function pullRowFromLocal(pr: LocalPR, repo: string, number: number): PullRow {
  const iso = (ms: number | null) => (ms === null ? null : new Date(ms).toISOString());
  const dto: DashboardPR = {
    id: pr.id,
    repo,
    number,
    title: pr.title,
    author: pr.author,
    htmlUrl: pr.remotePrUrl ?? '',
    createdAt: iso(pr.createdAt),
    updatedAt: iso(pr.syncedAt),
    origin: null,
    labels: [],
    labelColors: null,
    draft: pr.status === 'remote-drafted' || pr.status === 'local-drafted',
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: [],
    ciStatus: null,
    additions: pr.syncedAdditions ?? 0,
    deletions: pr.syncedDeletions ?? 0,
    commentCount: 0,
    attentionReason: null,
    state: pr.status === 'merged' ? 'merged' : pr.status === 'closed' ? 'closed' : 'open',
    closedAt: iso(pr.closedAt),
    mergedAt: iso(pr.mergedAt),
    mergeable: pr.syncedMergeable,
    mergeableState: pr.syncedMergeableState,
    headPushedAt: null,
    reviewerVerdicts: null,
    snoozedUntil: null,
    snoozeWakeReason: null,
  };
  return toRow(dto);
}
