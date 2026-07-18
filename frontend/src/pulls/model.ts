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
import { relativeTime } from '../notificationDisplay';
import { labelChipColors } from './atoms';

/**
 * View model for the redesigned PR screens. The shapes mirror the DC
 * prototypes' renderVals() rows (docs/mockups/design/pr-redesign/) so the
 * markup ports 1:1, but they are fed from real dashboard data — the
 * prototypes' mock data() is intentionally not ported.
 */

export type PullTab = 'all' | 'active' | 'req' | 'done';

export const PULL_TABS: { key: PullTab; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'active', label: 'Active' },
  { key: 'req', label: 'Review requests' },
  { key: 'done', label: 'Done' },
];

export type PullChip = { t: string; bg: string; fg: string };

export type PullRow = {
  /** Stable identity — the unified pr-table id. */
  id: string;
  repo: string;
  num: number;
  title: string;
  author: string;
  /** "1h ago" style, from updatedAt. */
  time: string;
  kind: 'pr' | 'merged';
  chips: PullChip[];
  status: 'passed' | 'failed' | 'running' | null;
  add: number;
  del: number;
  comments: number;
  hasAgent: boolean;
  dto: DashboardPR;
};

function isDone(pr: DashboardPR): boolean {
  return pr.state === 'merged' || pr.state === 'closed' || pr.handledAction !== null;
}

function status(pr: DashboardPR): PullRow['status'] {
  switch (pr.ciStatus) {
    case 'PASSING': return 'passed';
    case 'FAILING': return 'failed';
    case 'PENDING': return 'running';
    default: return null;
  }
}

export function toRow(pr: DashboardPR): PullRow {
  return {
    id: pr.id,
    repo: pr.repo,
    num: pr.number,
    title: pr.title,
    // The dashboard feed prefixes logins with '@'; strip it so the avatar
    // URL resolves and the meta line matches the design (no '@' there).
    author: (pr.author ?? '').replace(/^@/, ''),
    time: pr.updatedAt !== null ? relativeTime(pr.updatedAt) : '',
    kind: pr.state === 'merged' ? 'merged' : 'pr',
    chips: pr.labels.map(l => {
      const [bg, fg] = labelChipColors(l, pr.labelColors?.[l]);
      return { t: l, bg, fg };
    }),
    status: status(pr),
    add: pr.additions,
    del: pr.deletions,
    comments: pr.commentCount,
    hasAgent: pr.reviewState !== undefined && pr.reviewState !== 'none',
    dto: pr,
  };
}

const ACTIVE_WINDOW_MS = 7 * 24 * 3600 * 1000;

/** Newest-activity-first ordering shared by every tab. */
function byUpdatedDesc(a: DashboardPR, b: DashboardPR): number {
  return Date.parse(b.updatedAt ?? '') - Date.parse(a.updatedAt ?? '');
}

export function rowsForTab(prs: DashboardPR[], tab: PullTab): PullRow[] {
  const open = prs.filter(pr => !isDone(pr));
  let subset: DashboardPR[];
  switch (tab) {
    case 'all':
      subset = open;
      break;
    case 'active':
      // decision pending: "Active" = open PRs with activity in the last 7
      // days for now; revisit once real usage shows what belongs here.
      subset = open.filter(pr => pr.updatedAt !== null
        && Date.now() - Date.parse(pr.updatedAt) < ACTIVE_WINDOW_MS);
      break;
    case 'req':
      subset = open.filter(pr => pr.origin === 'REVIEW_REQUESTED');
      break;
    case 'done':
      subset = prs.filter(isDone);
      break;
  }
  return subset.slice().sort(byUpdatedDesc).map(toRow);
}
