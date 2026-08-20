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
import { useEffect, useMemo, useRef, useState } from 'react';
import type { CherryPickPreview, CherryPickPreviewCommit } from '../types';
import {
  countCommits,
  groupCommits,
  groupSelection,
  isSkippable,
  matchesSearch,
  retainSelections,
  skippedShasForConfirmation,
  toggleCommit,
  toggleGroup,
} from './model';

const CONFLICT_LABELS: Record<string, string> = {
  CLEAN: 'No conflict',
  CONFLICT: 'Conflicts',
  NOT_ANALYZED: 'Not analyzed',
};

function shortSha(sha: string): string {
  return sha.slice(0, 12);
}

/** Tri-state needs the DOM property; React only renders `checked`. */
function TriStateCheckbox({
  state,
  disabled,
  label,
  onChange,
}: {
  state: 'none' | 'some' | 'all';
  disabled?: boolean;
  label: string;
  onChange: () => void;
}) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) {
      ref.current.indeterminate = state === 'some';
    }
  }, [state]);
  return (
    <input
      ref={ref}
      type="checkbox"
      checked={state === 'all'}
      disabled={disabled}
      aria-label={label}
      onChange={onChange}
    />
  );
}

export default function CherryPickRangeScreen() {
  const [repository, setRepository] = useState('');
  const [sourceRemote, setSourceRemote] = useState('origin');
  const [sourceBaseRef, setSourceBaseRef] = useState('');
  const [sourceTipRef, setSourceTipRef] = useState('');
  const [targetRemote, setTargetRemote] = useState('origin');
  const [targetBranch, setTargetBranch] = useState('master');

  const [preview, setPreview] = useState<CherryPickPreview | null>(null);
  const [skipped, setSkipped] = useState<Set<string>>(new Set());
  const [search, setSearch] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  const groups = useMemo(() => (preview ? groupCommits(preview.commits) : []), [preview]);
  const counts = useMemo(
    () => countCommits(preview?.commits ?? [], skipped),
    [preview, skipped],
  );

  async function runPreview() {
    setBusy(true);
    setError(null);
    try {
      const next = await window.bridge.previewCherryPickRange({
        repository,
        sourceRemote,
        sourceBaseRef,
        sourceTipRef,
        targetRemote,
        targetBranch,
      });
      // Selections survive a re-preview when the commits do; the rest cannot be
      // carried over silently.
      setSkipped(current => retainSelections(next, current));
      setPreview(next);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  }

  async function chooseRepository() {
    const chosen = await window.bridge.chooseDirectory('Choose the repository clone');
    if (chosen) {
      setRepository(chosen);
    }
  }

  function toggleExpanded(sha: string) {
    setExpanded(current => {
      const next = new Set(current);
      if (next.has(sha)) {
        next.delete(sha);
      } else {
        next.add(sha);
      }
      return next;
    });
  }

  const canPreview = repository.trim() !== ''
    && sourceBaseRef.trim() !== ''
    && sourceTipRef.trim() !== ''
    && targetBranch.trim() !== '';

  return (
    <div className="cherry-pick">
      <div className="cherry-pick-form">
        <div className="cherry-pick-field">
          <label htmlFor="cp-repository">Repository clone</label>
          <div className="cherry-pick-field-path">
            <input
              id="cp-repository"
              value={repository}
              placeholder="/Users/you/IdeaProjects/trino"
              onChange={event => setRepository(event.target.value)}
            />
            <button type="button" onClick={chooseRepository}>Choose…</button>
          </div>
        </div>
        <div className="cherry-pick-field">
          <label htmlFor="cp-source-remote">Source remote</label>
          <input
            id="cp-source-remote"
            value={sourceRemote}
            onChange={event => setSourceRemote(event.target.value)}
          />
        </div>
        <div className="cherry-pick-field">
          <label htmlFor="cp-base">Range from (exclusive)</label>
          <input
            id="cp-base"
            value={sourceBaseRef}
            placeholder="477"
            onChange={event => setSourceBaseRef(event.target.value)}
          />
        </div>
        <div className="cherry-pick-field">
          <label htmlFor="cp-tip">Range to (inclusive)</label>
          <input
            id="cp-tip"
            value={sourceTipRef}
            placeholder="478"
            onChange={event => setSourceTipRef(event.target.value)}
          />
        </div>
        <div className="cherry-pick-field">
          <label htmlFor="cp-target-remote">Target remote</label>
          <input
            id="cp-target-remote"
            value={targetRemote}
            onChange={event => setTargetRemote(event.target.value)}
          />
        </div>
        <div className="cherry-pick-field">
          <label htmlFor="cp-target-branch">Target branch</label>
          <input
            id="cp-target-branch"
            value={targetBranch}
            onChange={event => setTargetBranch(event.target.value)}
          />
        </div>
      </div>

      <div className="cherry-pick-actions">
        <button type="button" disabled={!canPreview || busy} onClick={runPreview}>
          {busy ? 'Preparing…' : 'Preview range'}
        </button>
        {busy && (
          <span className="cherry-pick-group-meta">
            Synchronizing {targetRemote}/{targetBranch}. This is the only step that uses the network.
          </span>
        )}
      </div>

      {error && <div className="cherry-pick-error" role="alert">{error}</div>}

      {preview && (
        <>
          <div className="cherry-pick-frozen">
            <span>{preview.repository}</span>
            <span>base {shortSha(preview.sourceBaseSha)}</span>
            <span>tip {shortSha(preview.sourceTipSha)}</span>
            <span>target {preview.localTargetBranch} @ {shortSha(preview.targetSha)}</span>
          </div>

          {preview.warnings.map(warning => (
            <div className="cherry-pick-warning" key={warning}>{warning}</div>
          ))}

          <div className="cherry-pick-counts">
            <span><strong>{counts.total}</strong> source commits</span>
            <span><strong>{counts.alreadyOnTarget}</strong> already on target</span>
            <span><strong>{counts.userSkipped}</strong> skipped by you</span>
            <span><strong>{counts.toCherryPick}</strong> will be cherry-picked</span>
          </div>

          <div className="cherry-pick-field">
            <label htmlFor="cp-search">Search</label>
            <input
              id="cp-search"
              value={search}
              placeholder="pull request number, title, or commit SHA"
              onChange={event => setSearch(event.target.value)}
            />
          </div>

          <div className="cherry-pick-groups">
            {groups.map(group => {
              const visible = group.commits.filter(commit => matchesSearch(commit, search));
              if (visible.length === 0) {
                return null;
              }
              const eligible = group.commits.filter(isSkippable);
              const state = groupSelection(group, skipped);
              const presentCount = group.commits.length - eligible.length;
              return (
                <div className="cherry-pick-group" key={group.key ?? group.commits[0].sourceSha}>
                  <div className="cherry-pick-group-head">
                    <TriStateCheckbox
                      state={state}
                      disabled={eligible.length === 0}
                      label={group.key === null
                        ? `Skip commit ${group.title}`
                        : `Skip pull request ${group.key}`}
                      onChange={() => setSkipped(current => toggleGroup(group, current))}
                    />
                    <span className="cherry-pick-group-title">
                      {group.key === null ? group.title : `#${group.key}  ${group.title}`}
                    </span>
                    <span className="cherry-pick-group-meta">
                      {group.mergedAt ? `Merged ${group.mergedAt.slice(0, 10)} · ` : ''}
                      {group.commits.length} commit{group.commits.length === 1 ? '' : 's'}
                      {presentCount > 0 ? ` · ${presentCount} already on target` : ''}
                    </span>
                  </div>
                  <div className="cherry-pick-commits">
                    {visible.map(commit => (
                      <CommitRow
                        key={commit.sourceSha}
                        commit={commit}
                        skipped={skipped.has(commit.sourceSha)}
                        expanded={expanded.has(commit.sourceSha)}
                        onToggleSkip={() => setSkipped(current => toggleCommit(commit, current))}
                        onToggleExpanded={() => toggleExpanded(commit.sourceSha)}
                      />
                    ))}
                  </div>
                </div>
              );
            })}
          </div>

          <div className="cherry-pick-actions">
            <button
              type="button"
              disabled={counts.toCherryPick === 0}
              onClick={() => setConfirming(true)}
            >
              Review {counts.toCherryPick} commit{counts.toCherryPick === 1 ? '' : 's'}…
            </button>
          </div>

          {confirming && (
            <div className="cherry-pick-warning">
              <p>
                <strong>{counts.toCherryPick}</strong> commits would be cherry-picked onto a new work
                branch from {preview.localTargetBranch} at {shortSha(preview.targetSha)},
                skipping <strong>{counts.userSkipped}</strong> you chose
                and <strong>{counts.alreadyOnTarget}</strong> already present.
              </p>
              <p className="cherry-pick-group-meta">
                Confirmation sends these exact commit IDs:{' '}
                {skippedShasForConfirmation(preview, skipped).map(shortSha).join(', ') || 'none'}
              </p>
              <p className="cherry-pick-group-meta">
                Execution lands in a later checkpoint; nothing is written yet.
              </p>
              <div className="cherry-pick-actions">
                <button type="button" onClick={() => setConfirming(false)}>Close</button>
              </div>
            </div>
          )}
        </>
      )}

      {preview && preview.commits.length === 0 && (
        <div className="cherry-pick-empty">This range contains no commits.</div>
      )}
    </div>
  );
}

function CommitRow({
  commit,
  skipped,
  expanded,
  onToggleSkip,
  onToggleExpanded,
}: {
  commit: CherryPickPreviewCommit;
  skipped: boolean;
  expanded: boolean;
  onToggleSkip: () => void;
  onToggleExpanded: () => void;
}) {
  const conflicted = commit.conflictStatus === 'CONFLICT';
  return (
    <>
      <div className="cherry-pick-commit">
        <input
          type="checkbox"
          checked={skipped}
          disabled={commit.alreadyOnTarget}
          aria-label={`Skip commit ${shortSha(commit.sourceSha)}`}
          onChange={onToggleSkip}
        />
        <span className="cherry-pick-position">{commit.position}</span>
        <span className="cherry-pick-sha">{shortSha(commit.sourceSha)}</span>
        <span className="cherry-pick-commit-title">{commit.title}</span>
        {commit.alreadyOnTarget && (
          <span className="cherry-pick-tag cherry-pick-tag-present">
            {commit.targetMatchKind === 'DIRECT_ANCESTOR' ? 'Already on target' : 'Cherry-picked'}
            {commit.targetMatchCommitSha ? ` · ${shortSha(commit.targetMatchCommitSha)}` : ''}
          </span>
        )}
        {commit.originallyEmpty && <span className="cherry-pick-tag">Empty upstream</span>}
        {commit.patchEquivalent && <span className="cherry-pick-tag">Looks applied</span>}
        {!commit.alreadyOnTarget && (
          <button
            type="button"
            className={`cherry-pick-tag${conflicted ? ' cherry-pick-tag-conflict' : ''}`}
            aria-expanded={expanded}
            onClick={onToggleExpanded}
          >
            {CONFLICT_LABELS[commit.conflictStatus] ?? commit.conflictStatus}
            {conflicted ? ` · ${commit.conflictFiles.length}` : ''}
          </button>
        )}
      </div>
      {expanded && (
        <div className="cherry-pick-conflict-detail">
          {commit.conflictStatus === 'NOT_ANALYZED' && (
            <span>
              Not analyzed: an earlier commit conflicts, and the resolution you choose decides what
              this one meets.
            </span>
          )}
          {commit.conflictStatus === 'CLEAN' && <span>Applies cleanly after the commits before it.</span>}
          {conflicted && (
            <ul>
              {commit.conflictFiles.map(file => (
                <li key={file.path}>
                  {file.path} · {file.type}
                  {file.module ? ` · ${file.module}` : ''}
                  {file.sections.length > 0
                    ? ` · lines ${file.sections.map(([from, to]) => `${from}-${to}`).join(', ')}`
                    : ''}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </>
  );
}
