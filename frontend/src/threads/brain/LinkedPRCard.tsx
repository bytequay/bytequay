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
import type { LinkedPrDto } from '../../types/brainView';

type Props = {
  pr: LinkedPrDto;
  onMerge: () => void;
};

function ciDisplay(pr: LinkedPrDto): { text: string; cls: string } {
  switch (pr.ciStatus) {
    case 'green': return { text: `✓ ${pr.ciSummary || 'green'}`, cls: 'ok' };
    case 'failing': return { text: `⚠ ${pr.ciSummary || 'failing'}`, cls: 'warn' };
    case 'pending': return { text: `◷ ${pr.ciSummary || 'pending'}`, cls: '' };
    default: return { text: pr.ciSummary || 'unknown', cls: '' };
  }
}

function conflictsDisplay(pr: LinkedPrDto): { text: string; cls: string } {
  switch (pr.conflictsState) {
    case 'none': return { text: 'none', cls: 'ok' };
    case 'has_conflicts': return { text: 'conflicts', cls: 'warn' };
    default: return { text: 'unknown', cls: '' };
  }
}

/**
 * Linked PR card — status / CI / reviewers / conflicts rows with an
 * integrated merge button. The button reads `merge-btn ready` (green,
 * clickable) only when the PR is mergeable; otherwise it's a disabled
 * gray button carrying `aria-disabled`.
 */
export function LinkedPRCard({ pr, onMerge }: Props) {
  const ci = ciDisplay(pr);
  const conflicts = conflictsDisplay(pr);
  const statusCls = pr.status === 'draft' ? 'draft' : pr.status === 'merged' ? 'ok' : '';
  return (
    <div className="pr-card">
      <div className="num">#{pr.number} · {pr.branch}</div>
      <div className="row"><span className="l">Status</span><span className={`v ${statusCls}`}>{pr.status}</span></div>
      <div className="row"><span className="l">CI</span><span className={`v ${ci.cls}`}>{ci.text}</span></div>
      <div className="row">
        <span className="l">Reviewers</span>
        <span className="v">{pr.reviewersApproved}/{pr.reviewersTotal} approved</span>
      </div>
      <div className="row"><span className="l">Conflicts</span><span className={`v ${conflicts.cls}`}>{conflicts.text}</span></div>
      <div className="merge-section">
        <button
          type="button"
          className={`merge-btn${pr.mergeable ? ' ready' : ''}`}
          onClick={pr.mergeable ? onMerge : undefined}
          disabled={!pr.mergeable}
          aria-disabled={!pr.mergeable}
        >
          ⏏ Merge — finalize PR
        </button>
        {!pr.mergeable && (
          <div className="merge-note">Resolve CI and required approvals to merge.</div>
        )}
      </div>
    </div>
  );
}
