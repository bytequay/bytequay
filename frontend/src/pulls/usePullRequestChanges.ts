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

import { useCallback, useEffect, useRef, useState } from 'react';
import type { DiffCommitDto, DiffFileDto, PullRequestDetailDto } from '../types';
import type { CommitRange } from './PullChanges';
import { remoteErrorMessage } from './PullOverview';

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

export interface PullRequestChangesSource {
  files: DiffFileDto[];
  commits: DiffCommitDto[];
  loading: boolean;
  error: string | null;
  commitRange: CommitRange;
  changeCommitRange: (range: CommitRange) => void;
  /** Re-reads the snapshot after the detail refresh has updated its stored diff. */
  refresh: () => void;
}

/** Both PR surfaces own the same selection, snapshot reads, and GitHub comparisons. */
export function usePullRequestChanges(
  target: PullRequestChangesTarget | null,
  { active = true, revision = null }: { active?: boolean; revision?: string | null } = {},
): PullRequestChangesSource {
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [history, setHistory] = useState<{ scope: string; commits: DiffCommitDto[] } | null>(null);
  const [result, setResult] = useState<{ key: string; files: DiffFileDto[]; error: string | null } | null>(null);
  const repo = target?.repo ?? null;
  const number = target?.number ?? null;
  const scope = JSON.stringify([repo?.toLowerCase(), number, revision, refreshVersion]);
  const selected = selection?.scope === scope ? selection : null;
  const key = JSON.stringify([scope, selected]);
  // The render invalidates old responses before the following effect has had a chance to clean up.
  const currentKey = useRef(key);
  currentKey.current = key;
  const commits = history?.scope === scope ? history.commits : NO_COMMITS;

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
        window.bridge.fetchPrDiffFiles(repo, number),
        window.bridge.fetchPrDiffCommits(repo, number),
      ]).then(([files, loadedCommits]) => {
        if (live && currentKey.current === key) setHistory({ scope, commits: loadedCommits });
        return files;
      })
      : window.bridge.refreshPrDiffFiles(repo, number, selected.baseSha, selected.headSha);
    request
      .then(files => apply(files))
      .catch((failure: unknown) => apply([], remoteErrorMessage(failure)));
    return () => { live = false; };
  }, [active, key, number, repo, result, scope, selected]);

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
  const current = result?.key === key ? result : null;
  return {
    files: current?.files ?? [],
    commits,
    loading: active && target !== null && current === null,
    error: current?.error ?? null,
    commitRange: selected?.range ?? null,
    changeCommitRange,
    refresh,
  };
}
