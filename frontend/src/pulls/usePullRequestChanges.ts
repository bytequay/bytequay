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

import { usePullRequestBridge } from './PullRequestWorkspace';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { DiffCommitDto, DiffFileDto, PullRequestDetailDto } from '../types';
import type { CommitRange } from './PullChanges';
import { remoteErrorMessage } from '../remoteError';

const NO_COMMITS: DiffCommitDto[] = [];
const DIFF_EVENTS = new Set(['committed', 'head_ref_force_pushed', 'base_ref_force_pushed', 'base_ref_changed']);

/** Discussion activity changes updatedAt too; only code changes invalidate line selections. */
export function pullRequestDiffRevision(
  detail: Pick<PullRequestDetailDto, 'baseRef' | 'headRef' | 'additions' | 'deletions' | 'changedFiles' | 'recentActivity'> | null,
  fallbackHeadPushedAt?: string | null,
): string | null {
  if (detail === null) return fallbackHeadPushedAt ?? null;
  const events = detail.recentActivity
    .filter(activity => DIFF_EVENTS.has(activity.eventType))
    .map(activity => JSON.stringify([activity.eventType, activity.githubId, activity.timestamp, activity.payload.commitSha]))
    .sort();
  return JSON.stringify([detail.baseRef, detail.headRef, detail.additions, detail.deletions, detail.changedFiles,
    events.length > 0 ? events : fallbackHeadPushedAt ?? null]);
}

interface PullRequestChangesTarget {
  repo: string;
  number: number;
}

interface Selection {
  scope: string;
  range: Exclude<CommitRange, null>;
  baseSha?: string;
  headSha?: string;
}

interface CommitSelection {
  scope: string;
  commitSha: string;
}

export interface PullRequestChangesSource {
  files: DiffFileDto[];
  /** The complete PR snapshot, retained while a commit range is selected. */
  allFiles: DiffFileDto[];
  commits: DiffCommitDto[];
  loading: boolean;
  error: string | null;
  commitRange: CommitRange;
  changeCommitRange: (range: CommitRange) => void;
  /** Selects a published commit, including before the PR history has loaded. */
  selectCommit: (sha: string) => void;
  /** Re-reads the snapshot after the detail refresh has updated its stored diff. */
  refresh: () => void;
  /** A completed detail refresh may have rebuilt a missing default diff without changing its revision. */
  retryFailedRead: () => void;
}

/** Both PR surfaces own the same selection, snapshot reads, and GitHub comparisons. */
export function usePullRequestChanges(
  target: PullRequestChangesTarget | null,
  { active = true, revision = null }: { active?: boolean; revision?: string | null } = {},
): PullRequestChangesSource {
  const bridge = usePullRequestBridge();
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [selection, setSelection] = useState<Selection | CommitSelection | null>(null);
  const [history, setHistory] = useState<{ scope: string; commits: DiffCommitDto[]; files: DiffFileDto[] } | null>(null);
  const [result, setResult] = useState<{ key: string; files: DiffFileDto[]; error: string | null } | null>(null);
  const repo = target?.repo ?? null;
  const number = target?.number ?? null;
  const scope = JSON.stringify([repo?.toLowerCase(), number, revision, refreshVersion]);
  const requested = selection?.scope === scope ? selection : null;
  const commits = history?.scope === scope ? history.commits : NO_COMMITS;
  const selected = useMemo<Selection | null>(() => {
    if (requested === null) return null;
    if ('range' in requested) return requested;
    const index = commits.findIndex(commit => commit.sha === requested.commitSha);
    return index < 0 ? null : {
      scope, range: { start: index, end: index }, baseSha: commits[index].parentSha ?? undefined, headSha: commits[index].sha,
    };
  }, [commits, requested, scope]);
  const missingCommit = requested !== null && 'commitSha' in requested && history?.scope === scope && selected === null;
  const key = JSON.stringify([scope, requested, selected]);
  // The render invalidates old responses before the following effect has had a chance to clean up.
  const currentKey = useRef(key);
  currentKey.current = key;

  useEffect(() => {
    // Showing the tab again redraws what is already held; only a new pull request, head or commit
    // range is worth another read.
    if (!active || repo === null || number === null || result?.key === key) return;
    let live = true;
    const apply = (files: DiffFileDto[], error: string | null = null) => {
      if (live && currentKey.current === key) setResult({ key, files, error });
    };
    if (selected !== null && (!selected.baseSha || !selected.headSha)) {
      apply([], 'This commit range does not have enough Git history to compare.');
      return;
    }
    const request = selected === null
      ? Promise.all([
        bridge.fetchPrDiffFiles(repo, number),
        bridge.fetchPrDiffCommits(repo, number),
      ]).then(([files, loadedCommits]) => {
        if (live && currentKey.current === key) setHistory({ scope, commits: loadedCommits, files });
        return files;
      })
      : bridge.refreshPrDiffFiles(repo, number, selected.baseSha, selected.headSha);
    request
      .then(files => apply(files))
      .catch((failure: unknown) => apply([], remoteErrorMessage(failure)));
    return () => { live = false; };
  }, [active, bridge, key, number, repo, result, scope, selected]);

  const changeCommitRange = useCallback((range: CommitRange) => {
    if (range === null) {
      setSelection(null);
      return;
    }
    const valid = Number.isInteger(range.start) && Number.isInteger(range.end)
      && range.start >= 0 && range.start <= range.end && range.end < commits.length;
    setSelection({
      scope,
      range,
      baseSha: valid ? commits[range.start].parentSha ?? undefined : undefined,
      headSha: valid ? commits[range.end].sha : undefined,
    });
  }, [commits, scope]);
  const refresh = useCallback(() => setRefreshVersion(version => version + 1), []);
  const retryFailedRead = useCallback(() => {
    if (selected !== null) return;
    setResult(current => current?.key === key && current.error !== null ? null : current);
  }, [key, selected]);
  const selectCommit = useCallback((commitSha: string) => setSelection({ scope, commitSha }), [scope]);
  const current = result?.key === key ? result : null;
  return {
    files: current?.files ?? [],
    allFiles: history?.scope === scope ? history.files : [],
    commits,
    loading: active && target !== null && current === null,
    error: current?.error ?? (missingCommit ? 'This commit is not available in the published pull request. It may be unpublished or removed by a force push.' : null),
    commitRange: selected?.range ?? null,
    changeCommitRange,
    selectCommit,
    refresh,
    retryFailedRead,
  };
}
