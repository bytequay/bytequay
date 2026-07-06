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

/**
 * The PR header (U13b): title + number, the solid state pill, the
 * "X wants to merge N commits into base from head" branch row, the sync
 * chip, and the tab strip with count chips + the delta meter. Only
 * Conversation is a real (always-active) view today — Commits/Checks/Files
 * changed surface inline (timeline + merge box) rather than as separate
 * panes, so their tab chips are counts only, not navigation, until the
 * Files-changed milestone gives them somewhere to go.
 */
export function PRHeader({
  pr, syncedAt, syncing, onRefresh, commitCount, checkCount, conversationCount, additions, deletions, headerAction,
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
}) {
  const prNumLabel = pr.remotePrNumber !== null ? `#${pr.remotePrNumber}` : '#local';
  const author = pr.origin === 'external' ? pr.author ?? 'someone' : 'claude-code';
  return (
    <div className="pr-header">
      <div className="pr-title-row">
        <span className="pr-title">{pr.title}</span>
        <span className="pr-num">{prNumLabel}</span>
        {headerAction}
      </div>
      <div className="pr-meta-row">
        <StatePill status={pr.status} />
        <span className="pr-meta-text">
          <span className="who">{author}</span> wants to merge {commitCount} commit{commitCount === 1 ? '' : 's'} into{' '}
          <span className="pr-branch">{pr.baseBranch}</span> from <span className="pr-branch">{pr.branchName}</span>
        </span>
        <SyncChip pr={pr} syncedAt={syncedAt} syncing={syncing} onRefresh={onRefresh} />
      </div>
      <div className="pr-tabs">
        <span className="pt on">💬 Conversation <span className="cnt">{conversationCount}</span></span>
        <span className="pt">◆ Commits <span className="cnt">{commitCount}</span></span>
        <span className="pt">✓ Checks <span className="cnt">{checkCount}</span></span>
        <DeltaMeter additions={additions} deletions={deletions} />
      </div>
    </div>
  );
}
