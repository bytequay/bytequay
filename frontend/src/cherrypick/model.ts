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
import type {
  CherryPickExecution,
  CherryPickExecutionItem,
  CherryPickPreview,
  CherryPickPreviewCommit,
} from '../types';

/**
 * Commits grouped for display only. `position` stays the cherry-pick order;
 * grouping never reorders anything.
 */
export type CherryPickGroup = {
  /** Pull request number, or null for commits GitHub could not associate. */
  key: number | null;
  title: string;
  url: string | null;
  mergedAt: string | null;
  /** Earliest source position in the group; this is what orders groups. */
  firstPosition: number;
  commits: CherryPickPreviewCommit[];
};

export type GroupSelection = 'none' | 'some' | 'all';

/** A commit already on the target is never eligible: execution will not pick it. */
export function isSkippable(commit: CherryPickPreviewCommit): boolean {
  return !commit.alreadyOnTarget;
}

/**
 * Groups by pull request, ordered by each group's earliest commit position.
 *
 * `mergedAt` is display context only — ordering by it would put commits out of
 * the order execution applies them in.
 */
export function groupCommits(commits: CherryPickPreviewCommit[]): CherryPickGroup[] {
  const groups = new Map<string, CherryPickGroup>();
  for (const commit of commits) {
    const key = commit.pullRequestNumber ?? null;
    const id = key === null ? `commit:${commit.sourceSha}` : `pr:${key}`;
    const existing = groups.get(id);
    if (existing) {
      existing.commits.push(commit);
      existing.firstPosition = Math.min(existing.firstPosition, commit.position);
      continue;
    }
    groups.set(id, {
      key,
      title: key === null ? commit.title : commit.pullRequestTitle ?? `#${key}`,
      url: commit.pullRequestUrl ?? null,
      mergedAt: commit.pullRequestMergedAt ?? null,
      firstPosition: commit.position,
      commits: [commit],
    });
  }
  return [...groups.values()]
    .map(group => ({ ...group, commits: [...group.commits].sort((a, b) => a.position - b.position) }))
    .sort((a, b) => a.firstPosition - b.firstPosition);
}

/** Tri-state over a group's eligible commits only. */
export function groupSelection(group: CherryPickGroup, skipped: ReadonlySet<string>): GroupSelection {
  const eligible = group.commits.filter(isSkippable);
  if (eligible.length === 0) {
    return 'none';
  }
  const selected = eligible.filter(commit => skipped.has(commit.sourceSha)).length;
  if (selected === 0) {
    return 'none';
  }
  return selected === eligible.length ? 'all' : 'some';
}

/** Checking a partly-selected group selects the rest, matching the checkbox the user sees. */
export function toggleGroup(
  group: CherryPickGroup,
  skipped: ReadonlySet<string>,
): Set<string> {
  const next = new Set(skipped);
  const eligible = group.commits.filter(isSkippable);
  const selectAll = groupSelection(group, skipped) !== 'all';
  for (const commit of eligible) {
    if (selectAll) {
      next.add(commit.sourceSha);
    } else {
      next.delete(commit.sourceSha);
    }
  }
  return next;
}

export function toggleCommit(
  commit: CherryPickPreviewCommit,
  skipped: ReadonlySet<string>,
): Set<string> {
  const next = new Set(skipped);
  if (!isSkippable(commit)) {
    return next;
  }
  if (next.has(commit.sourceSha)) {
    next.delete(commit.sourceSha);
  } else {
    next.add(commit.sourceSha);
  }
  return next;
}

/**
 * Filters for display. Selections are held separately by SHA, so filtering can
 * never drop one.
 */
export function matchesSearch(commit: CherryPickPreviewCommit, query: string): boolean {
  const needle = query.trim().toLowerCase();
  if (needle === '') {
    return true;
  }
  return (
    commit.sourceSha.toLowerCase().startsWith(needle)
    || commit.title.toLowerCase().includes(needle)
    || String(commit.pullRequestNumber ?? '').includes(needle)
    || (commit.pullRequestTitle ?? '').toLowerCase().includes(needle)
  );
}

export type CherryPickCounts = {
  total: number;
  alreadyOnTarget: number;
  userSkipped: number;
  toCherryPick: number;
};

export function countCommits(
  commits: CherryPickPreviewCommit[],
  skipped: ReadonlySet<string>,
): CherryPickCounts {
  const alreadyOnTarget = commits.filter(commit => commit.alreadyOnTarget).length;
  const userSkipped = commits.filter(
    commit => isSkippable(commit) && skipped.has(commit.sourceSha),
  ).length;
  return {
    total: commits.length,
    alreadyOnTarget,
    userSkipped,
    toCherryPick: commits.length - alreadyOnTarget - userSkipped,
  };
}

/**
 * Builds the durable execution input: explicit source SHAs, in preview order.
 *
 * Group checkbox state is never sent. A SHA the preview does not contain, or
 * one already on the target, is dropped rather than trusted.
 */
export function skippedShasForConfirmation(
  preview: CherryPickPreview,
  skipped: ReadonlySet<string>,
): string[] {
  return preview.commits
    .filter(commit => isSkippable(commit) && skipped.has(commit.sourceSha))
    .map(commit => commit.sourceSha);
}

/**
 * Keeps the selections a refreshed preview can still honour.
 *
 * A stale preview means the range moved; a SHA that survived the move is still
 * the user's choice, and one that did not cannot be carried over silently.
 */
export function retainSelections(
  preview: CherryPickPreview,
  skipped: ReadonlySet<string>,
): Set<string> {
  const eligible = new Set(preview.commits.filter(isSkippable).map(commit => commit.sourceSha));
  return new Set([...skipped].filter(sha => eligible.has(sha)));
}

export function completedExecutionItems(items: CherryPickExecutionItem[]): number {
  return items.filter(item => item.result !== 'PENDING').length;
}

export function executionHeadline(execution: CherryPickExecution): string {
  if (execution.status === 'FINISHED') {
    return execution.finishReason === 'CANCELLED' ? 'Cherry-pick cancelled' : 'Cherry-pick complete';
  }
  if (execution.status === 'PAUSED') {
    return 'Cherry-pick needs attention';
  }
  const position = execution.currentPosition ? `commit ${execution.currentPosition}` : 'the range';
  return execution.currentStep === 'RESOLVING'
    ? `Resolving conflicts in ${position}`
    : `Applying ${position}`;
}

export function executionResultLabel(item: CherryPickExecutionItem, currentPosition?: number | null): string {
  switch (item.result) {
    case 'ALREADY_ON_TARGET': return 'Already on target';
    case 'USER_SKIPPED': return 'Skipped by you';
    case 'APPLIED': return 'Applied';
    case 'EMPTY_SKIPPED': return 'No changes needed';
    default: return item.position === currentPosition ? 'Working' : 'Waiting';
  }
}
