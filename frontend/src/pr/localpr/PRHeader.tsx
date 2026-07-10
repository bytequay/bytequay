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
import type { ReactNode } from 'react';
import type { LocalPR } from '../../types/localPr';
import { StatePill } from './StatePill';
import { SyncChip } from './SyncChip';
import { DeltaMeter } from './DeltaMeter';

export type PRHeaderTab = 'conversation' | 'commits' | 'checks';

/**
 * The PR header (U13b): title + number, the solid state pill, the
 * "X wants to merge N commits into base from head" branch row, the sync
 * chip, and the tab strip with count chips + the delta meter. Conversation/
 * Commits/Checks are real, clickable tabs, tracked by {@link PRHeaderTab};
 * "Changes" sits alongside them but isn't one — it navigates straight to the
 * full-page changed-files + diff review ({@code onReviewChanges}) instead of
 * swapping in-place content, so it carries no `activeTab` state of its own.
 */
export function PRHeader({
  pr, syncedAt, syncing, onRefresh, commitCount, checkCount, conversationCount, additions, deletions, headerAction,
  onReviewChanges, activeTab, onTabChange,
}: {
  pr: LocalPR;
  syncedAt: number | null;
  syncing: boolean;
  onRefresh: () => void;
  commitCount: number;
  checkCount: number;
  conversationCount: number;
  additions: number;
  deletions: number;
  /** e.g. a "Review with agent →" affordance on the standalone details page. */
  headerAction?: ReactNode;
  /** Opens the full-page changed-files + diff review — surfaces as the
   *  green "Review" button beside the title. Omitted when there's
   *  nothing to review yet. */
  onReviewChanges?: () => void;
  activeTab: PRHeaderTab;
  onTabChange: (tab: PRHeaderTab) => void;
}) {
  const prNumLabel = pr.remotePrNumber !== null ? `#${pr.remotePrNumber}` : '#local';
  const author = pr.origin === 'external' ? pr.author ?? 'someone' : 'claude-code';
  return (
    <div className="pr-header">
      <div className="pr-title-row">
        <span className="pr-title">{pr.title}</span>
        <span className="pr-num">{prNumLabel}</span>
        {headerAction !== undefined && (
          <span className="pr-header-action">{headerAction}</span>
        )}
      </div>
      <div className="pr-meta-row">
        <StatePill status={pr.status} />
        <span className="pr-meta-text">
          <span className="who">{author}</span> wants to merge {commitCount} commit{commitCount === 1 ? '' : 's'} into{' '}
          <span className="pr-branch">{pr.baseBranch}</span> from <span className="pr-branch">{pr.branchName}</span>
        </span>
        <SyncChip pr={pr} syncedAt={syncedAt} syncing={syncing} onRefresh={onRefresh} />
      </div>
      <div className="pr-tabs" role="tablist">
        <button type="button" role="tab" aria-selected={activeTab === 'conversation'} className={`pt${activeTab === 'conversation' ? ' on' : ''}`} onClick={() => onTabChange('conversation')}>
          💬 Conversation <span className="cnt">{conversationCount}</span>
        </button>
        <button type="button" role="tab" aria-selected={activeTab === 'commits'} className={`pt${activeTab === 'commits' ? ' on' : ''}`} onClick={() => onTabChange('commits')}>
          ◆ Commits <span className="cnt">{commitCount}</span>
        </button>
        <button type="button" role="tab" aria-selected={activeTab === 'checks'} className={`pt${activeTab === 'checks' ? ' on' : ''}`} onClick={() => onTabChange('checks')}>
          ✓ Checks <span className="cnt">{checkCount}</span>
        </button>
        {onReviewChanges !== undefined && (
          <button type="button" className="pt" onClick={onReviewChanges}>
            ⇄ Changes
          </button>
        )}
        <DeltaMeter additions={additions} deletions={deletions} />
      </div>
    </div>
  );
}
