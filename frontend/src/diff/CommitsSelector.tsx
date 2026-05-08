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
import { useState, type ReactNode } from 'react';
import { commitSubject, formatShortSha } from './commitDisplay';
import { formatRelativeTime } from '../pr/utils';

/** Minimal commit descriptor the selector renders. Both
 *  PullRequestCommitDto and LocalCommitDto can be adapted to this
 *  shape with a one-line map at the call site. */
export type CommitsSelectorCommit = {
  sha: string;
  /** First line of the commit message — caller is responsible for
   *  truncating long subjects (commitSubject() does this) so the
   *  selector stays purely presentational. */
  subject: string;
  authoredAt?: string | null;
};

export type CommitsSelectorProps = {
  /** Chronological order is up to the caller. The popover renders in
   *  the order given; both PR and local-repo flows pass newest first. */
  commits: CommitsSelectorCommit[];
  selected: ReadonlySet<string>;
  onToggle: (sha: string) => void;
  onClear: () => void;
  /** Shown next to the pill when a fetch is in flight. */
  loading?: boolean;
  /** Right-side chrome (file count, "branched from main", etc.). The
   *  selector renders the wrapper bar so absolute-positioned popover
   *  placement is consistent across screens. */
  rightChrome?: ReactNode;
  /** Wording on the "all commits" pill + first popover row. PR flow
   *  uses "All N commits (cumulative)"; the branch flow may want
   *  "All N commits since branch point". */
  cumulativeLabel?: string;
  /** sha of the merge-base between branch and base. When provided,
   *  the popover renders a "branched from <baseLabel>" divider AFTER
   *  the matching row. */
  mergeBaseSha?: string;
  baseLabel?: string;
};

export function CommitsSelector(props: CommitsSelectorProps) {
  const {
    commits,
    selected,
    onToggle,
    onClear,
    loading,
    rightChrome,
    cumulativeLabel,
    mergeBaseSha,
    baseLabel,
  } = props;
  const [open, setOpen] = useState(false);

  if (commits.length === 0) return null;

  const allLabel = cumulativeLabel ?? `All ${commits.length} commit${commits.length === 1 ? '' : 's'} (cumulative)`;
  const titleAll = 'All commits (cumulative diff). Click to filter by commit.';
  const titleOne = "Showing only this commit's changes — click to change selection.";
  const titleN = `Showing the union of ${selected.size} selected commits.`;

  return (
    <div className="diff-viewer__sub">
      <div className="diff-viewer__sub-left">
        <span className="diff-viewer__sub-label">Showing:</span>
        <button
          type="button"
          className="commits-pill"
          onClick={() => setOpen(o => !o)}
          title={selected.size === 0 ? titleAll : selected.size === 1 ? titleOne : titleN}
        >
          <span className="commits-pill__icon" aria-hidden="true">⊞</span>
          {selected.size === 0 ? (
            <b>{allLabel}</b>
          ) : selected.size === 1 ? (
            <><b>{formatShortSha([...selected][0])}</b> · single commit</>
          ) : (
            <><b>{selected.size} of {commits.length} commits</b> selected</>
          )}
          <span className="commits-pill__caret" aria-hidden="true">▾</span>
        </button>
        {loading && (
          <span className="diff-viewer__sub-status">Loading commit diff…</span>
        )}
      </div>
      {rightChrome && (
        <div className="diff-viewer__sub-right">{rightChrome}</div>
      )}
      {open && (
        <div className="commits-popover" onClick={(e) => e.stopPropagation()}>
          <button
            type="button"
            className={'commits-popover__row commits-popover__row--all' + (selected.size === 0 ? ' commits-popover__row--active' : '')}
            onClick={() => { onClear(); setOpen(false); }}
          >
            <code className="commits-popover__sha">All</code>
            <span className="commits-popover__subject">{allLabel}</span>
          </button>
          {commits.map((c) => {
            const checked = selected.has(c.sha);
            const isMergeBase = mergeBaseSha != null && c.sha === mergeBaseSha;
            return (
              <div key={c.sha}>
                <label
                  className={'commits-popover__row commits-popover__row--checkable' + (checked ? ' commits-popover__row--active' : '')}
                  title={c.subject}
                >
                  <input
                    type="checkbox"
                    className="commits-popover__check"
                    checked={checked}
                    onChange={() => onToggle(c.sha)}
                  />
                  <code className="commits-popover__sha">{formatShortSha(c.sha)}</code>
                  <span className="commits-popover__subject">{commitSubject(c.subject)}</span>
                  {c.authoredAt && (
                    <span className="commits-popover__time">{formatRelativeTime(c.authoredAt)}</span>
                  )}
                </label>
                {isMergeBase && (
                  <div className="commits-popover__divider" aria-hidden="true">
                    {baseLabel
                      ? <>— branched from <code>{baseLabel}</code> —</>
                      : '— branch point —'}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
