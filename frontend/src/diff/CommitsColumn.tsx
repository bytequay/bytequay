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
import { commitSubject, formatShortSha } from './commitDisplay';

export type CommitsColumnCommit = {
  sha: string;
  /** First line of the commit message (caller passes the raw message; we
   *  run it through commitSubject for display). */
  subject: string;
};

type Props = {
  /** Commits in display order (oldest-first, as the PR commits API returns). */
  commits: CommitsColumnCommit[];
  /** Empty set === cumulative (all commits). */
  selected: ReadonlySet<string>;
  /** Plain click selects a single commit; shift-click ({@code extend}) grows a
   *  contiguous range from the existing anchor. The parent owns the range math. */
  onSelectCommit: (sha: string, extend: boolean) => void;
  /** Return to the cumulative (all-commits) diff. */
  onSelectAll: () => void;
  /** Footer "Review selected" action. */
  onReview: () => void;
  /** Running totals across the current selection, for the footer summary. */
  summary: { additions: number; deletions: number };
  loading?: boolean;
  collapsed: boolean;
  onToggleCollapsed: () => void;
};

/**
 * The first lane of the three-column diff view: a persistent, browsable list
 * of the PR's commits (replacing the old toolbar pill + popover). Selection is
 * a contiguous range that drives the files column and the cumulative diff.
 * Purely presentational — the parent holds selection state and the range math.
 */
export function CommitsColumn({
  commits, selected, onSelectCommit, onSelectAll, onReview, summary,
  loading, collapsed, onToggleCollapsed,
}: Props) {
  if (collapsed) {
    return (
      <aside className="diff-viewer__commits diff-viewer__commits--collapsed">
        <button
          type="button"
          className="diff-viewer__chev"
          onClick={onToggleCollapsed}
          title="Expand commits"
          aria-label="Expand commits"
        >
          ›
        </button>
        <div className="diff-viewer__col-rail-label" aria-hidden="true">
          Commits · {commits.length}
        </div>
      </aside>
    );
  }

  const selectionActive = selected.size > 0;
  const countLabel = selectionActive ? `${selected.size} of ${commits.length}` : String(commits.length);

  return (
    <aside className="diff-viewer__commits">
      <div className="diff-viewer__col-head">
        <span className="diff-viewer__col-title">Commits · {countLabel}</span>
        {loading && <span className="diff-viewer__col-status" aria-hidden="true">…</span>}
        <button
          type="button"
          className="diff-viewer__chev"
          onClick={onToggleCollapsed}
          title="Collapse commits"
          aria-label="Collapse commits"
        >
          ‹
        </button>
      </div>

      <div className="diff-viewer__commits-list">
        <button
          type="button"
          className={'diff-viewer__commit-row diff-viewer__commit-all'
            + (selectionActive ? '' : ' diff-viewer__commit-row--sel')}
          onClick={onSelectAll}
          title="Show the cumulative diff across all commits"
        >
          <span className="diff-viewer__commit-chk diff-viewer__commit-chk--all" aria-hidden="true">≡</span>
          <span className="diff-viewer__commit-text">
            <span className="diff-viewer__commit-subject diff-viewer__commit-subject--all">
              All {commits.length} commit{commits.length === 1 ? '' : 's'}
            </span>
            <span className="diff-viewer__commit-meta">cumulative diff</span>
          </span>
        </button>
        {commits.map(c => {
          const isSel = selected.has(c.sha);
          const cls = 'diff-viewer__commit-row'
            + (isSel ? ' diff-viewer__commit-row--sel' : '')
            + (selectionActive && !isSel ? ' diff-viewer__commit-row--dim' : '');
          return (
            <button
              key={c.sha}
              type="button"
              className={cls}
              onClick={(e) => onSelectCommit(c.sha, e.shiftKey)}
              title="Click to select; shift-click to extend the range"
            >
              <span className={'diff-viewer__commit-chk' + (isSel ? ' diff-viewer__commit-chk--on' : '')} aria-hidden="true">
                {isSel ? '✓' : ''}
              </span>
              <span className="diff-viewer__commit-text">
                <span className="diff-viewer__commit-sha">{formatShortSha(c.sha)}</span>
                <span className="diff-viewer__commit-subject">{commitSubject(c.subject)}</span>
              </span>
            </button>
          );
        })}
      </div>

      {selectionActive && (
        <div className="diff-viewer__commits-foot">
          <div className="diff-viewer__commits-sum">
            Reviewing {selected.size} commit{selected.size === 1 ? '' : 's'} ·{' '}
            <span className="diff-viewer__stat-add">+{summary.additions}</span>{' '}
            <span className="diff-viewer__stat-del">−{summary.deletions}</span>
          </div>
          <button type="button" className="diff-viewer__commits-go" onClick={onReview}>
            Review selected
          </button>
        </div>
      )}
    </aside>
  );
}
