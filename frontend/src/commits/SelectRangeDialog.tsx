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
import { ArrowRight, Flag, GitCommitHorizontal, Hash, RotateCcw, X } from 'lucide-react';
import { useState } from 'react';
import type { GitCommitDto } from '../types';
import { useModalDialog } from '../useModalDialog';
import CommitCard from './CommitCard';
import { matchesCommitQuery, pastedRangePair, shortSha } from './model';

type Slot = 'from' | 'to';

type SelectRangeDialogProps = {
  commits: GitCommitDto[];
  /** Repo and branch the picker is searching, shown under the title. */
  scope: string;
  /** `owner/repo` the listed commits' pull requests live in. */
  pullRequestRepo?: string | null;
  onOpenPullRequest: (number: number) => void;
  onCancel: () => void;
  onApply: (anchors: [string, string]) => void;
};

export default function SelectRangeDialog({
  commits,
  scope,
  pullRequestRepo = null,
  onOpenPullRequest,
  onCancel,
  onApply,
}: SelectRangeDialogProps) {
  const [from, setFrom] = useState<string | null>(null);
  const [to, setTo] = useState<string | null>(null);
  const [slot, setSlot] = useState<Slot>('from');
  const [query, setQuery] = useState('');

  const dialog = useModalDialog();

  const fromCommit = commits.find(commit => commit.sha === from) ?? null;
  const toCommit = commits.find(commit => commit.sha === to) ?? null;
  const results = commits.filter(commit => matchesCommitQuery(commit, query));
  const ready = fromCommit !== null && toCommit !== null;
  const count = ready
    ? Math.abs(commits.indexOf(fromCommit) - commits.indexOf(toCommit)) + 1
    : 0;

  const onQueryChange = (value: string) => {
    const pair = pastedRangePair(commits, value);
    if (pair === null) {
      setQuery(value);
      return;
    }
    setFrom(pair.anchors[0]);
    setTo(pair.anchors[1]);
    setQuery('');
  };

  const pick = (sha: string) => {
    if (slot === 'from') {
      setFrom(sha);
      setSlot('to');
      return;
    }
    setTo(sha);
    setSlot('from');
  };

  const endpoint = (which: Slot, commit: GitCommitDto | null, empty: string) => (
    <button
      type="button"
      className={`cmt-rd-end${slot === which ? ' is-focused' : ''}${commit === null ? '' : ' is-set'}`}
      aria-pressed={slot === which}
      onClick={() => setSlot(which)}
    >
      {which === 'from'
        ? <GitCommitHorizontal size={17} strokeWidth={2} aria-hidden="true" />
        : <Flag size={17} strokeWidth={2} aria-hidden="true" />}
      <span className="cmt-rd-end-text">
        <span className="cmt-rd-end-sha cmt-mono">{commit === null ? empty : shortSha(commit.sha)}</span>
        <span className="cmt-rd-end-title">
          {commit === null ? (which === 'from' ? 'start of the range' : 'end of the range') : commit.title}
        </span>
      </span>
    </button>
  );

  return (
    <dialog ref={dialog} className="cmt-rd" onCancel={event => { event.preventDefault(); onCancel(); }} role="dialog" aria-modal="true" aria-label="Select a commit range">
        <div className="cmt-rd-head">
          <Hash size={15} strokeWidth={2} aria-hidden="true" />
          <div className="cmt-rd-head-text">
            <div className="cmt-rd-title">Select a commit range</div>
            <div className="cmt-rd-scope">{scope}</div>
          </div>
          <button
            type="button"
            className="cmt-rd-icon"
            title="Reset both ends"
            aria-label="Reset both ends"
            onClick={() => { setFrom(null); setTo(null); setQuery(''); setSlot('from'); }}
          >
            <RotateCcw size={15} strokeWidth={2} aria-hidden="true" />
          </button>
          <button
            type="button"
            className="cmt-rd-icon is-close"
            title="Close"
            aria-label="Close"
            onClick={onCancel}
          >
            <X size={15} strokeWidth={2} aria-hidden="true" />
          </button>
        </div>

        <div className="cmt-rd-body">
          <div className="cmt-rd-ends">
            {endpoint('from', fromCommit, 'pick the oldest commit')}
            <span className="cmt-rd-arrow" aria-hidden="true">
              <ArrowRight size={18} strokeWidth={2} />
            </span>
            {endpoint('to', toCommit, 'pick the newest commit')}
          </div>

          <input
            className="cmt-rd-search"
            value={query}
            placeholder="Search a commit, PR title or #number — or paste 8fd3d32…741339a"
            aria-label="Search commits, or paste a SHA range"
            onChange={event => onQueryChange(event.target.value)}
          />

          <div className="cmt-rd-results">
            {results.map(commit => (
              <CommitCard
                key={commit.sha}
                commit={commit}
                selected={commit.sha === from || commit.sha === to}
                dense
                pullRequestRepo={pullRequestRepo}
                onSelect={() => pick(commit.sha)}
                onOpenPullRequest={onOpenPullRequest}
              />
            ))}
            {results.length === 0 && <div className="cmt-rd-empty">No commit matches that search.</div>}
          </div>

          <div className="cmt-rd-foot">
            <span className="cmt-rd-hint">
              {ready
                ? `${count} commit${count === 1 ? '' : 's'} in this range`
                : 'Pick both ends — or paste 8fd3d32…741339a'}
            </span>
            <div className="cmt-rd-gap" />
            <button type="button" className="cmt-rd-btn" onClick={onCancel}>Cancel</button>
            <button
              type="button"
              className="cmt-rd-btn is-primary"
              disabled={!ready}
              onClick={() => { if (fromCommit && toCommit) onApply([fromCommit.sha, toCommit.sha]); }}
            >
              Select range
            </button>
          </div>
        </div>
    </dialog>
  );
}
