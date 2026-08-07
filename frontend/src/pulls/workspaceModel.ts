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
import type { PullRequestDto } from '../types';
import type { DashboardPR } from '../types/dashboardPr';
import { toRow } from './model';
import type { PullRow } from './model';
import { isToday } from '../format';

/**
 * View model for the workspace Pull-requests surface (Board|List + detail
 * pane). Filter predicates, counts, and bucketFor are copied from
 * workspace/PullRequestBoardList.tsx (scheduled for deletion) rather than
 * imported from it.
 */

export type WorkspaceFilter = 'review' | 'mine' | 'all';
export type Bucket = 'attention' | 'progress' | 'cleared';

export function matchesFilter(pr: PullRequestDto, filter: WorkspaceFilter): boolean {
  if (filter === 'review') return pr.origin === 'REVIEW_REQUESTED';
  if (filter === 'mine') return pr.origin === 'AUTHORED';
  return true;
}

/** Counts shown in the filter-pill labels — always open-state only. */
export function filterCounts(rows: PullRequestDto[]): { review: number; mine: number; open: number } {
  const open = rows.filter(pr => pr.state === 'open');
  return {
    review: open.filter(pr => pr.origin === 'REVIEW_REQUESTED').length,
    mine: open.filter(pr => pr.origin === 'AUTHORED').length,
    open: open.length,
  };
}

export function bucketFor(pr: PullRequestDto): Bucket {
  if (isToday(pr.mergedAt) || isToday(pr.reviewedAt)) return 'cleared';
  if (pr.attentionReason !== null
      || (pr.origin === 'REVIEW_REQUESTED' && pr.handledAction === null)) {
    return 'attention';
  }
  return 'progress';
}

/**
 * The rows the surface shows: open PRs (the design has no "Include closed"
 * control) plus non-open PRs that bucket to "cleared today" so the board's
 * third column can show what was merged/reviewed today.
 */
export function visibleRows(rows: PullRequestDto[], filter: WorkspaceFilter): PullRequestDto[] {
  return rows
    .filter(pr => (pr.state === null || pr.state === 'open' || bucketFor(pr) === 'cleared')
      && matchesFilter(pr, filter))
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt));
}

export function boardBuckets(rows: PullRequestDto[]): Record<Bucket, PullRequestDto[]> {
  const result: Record<Bucket, PullRequestDto[]> = { attention: [], progress: [], cleared: [] };
  for (const pr of rows) result[bucketFor(pr)].push(pr);
  return result;
}

/**
 * PullRequestDto overlaps DashboardPR field-for-field except the numeric id;
 * stringifying it lets the list reuse model.ts's toRow() wholesale. The
 * resulting id is NOT the unified pr-table id — the detail pane resolves
 * that separately via getPrForRepoPull.
 */
export function toDashboardPr(pr: PullRequestDto): DashboardPR {
  return { ...pr, id: String(pr.id) };
}

export function pullRowFromDto(pr: PullRequestDto): PullRow {
  return {
    ...toRow(toDashboardPr(pr)),
    // PullRequestDto has no reviewState; an attached review round is the
    // workspace's agent-review marker.
    hasAgent: pr.reviewRound !== null && pr.reviewRound !== undefined,
  };
}
