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
import { describe, expect, it } from 'vitest';
import {
  countCommits,
  groupCommits,
  groupSelection,
  matchesSearch,
  retainSelections,
  skippedShasForConfirmation,
  toggleCommit,
  toggleGroup,
} from './model';
import type { CherryPickPreview, CherryPickPreviewCommit } from '../types';

function commit(
  position: number,
  overrides: Partial<CherryPickPreviewCommit> = {},
): CherryPickPreviewCommit {
  return {
    position,
    sourceSha: String(position).padStart(40, '0'),
    sourceParentSha: String(position + 500).padStart(40, '0'),
    title: `Commit ${position}`,
    author: 'someone',
    committedAt: '2026-08-01T00:00:00Z',
    changedPaths: [],
    originallyEmpty: false,
    alreadyOnTarget: false,
    patchEquivalent: false,
    conflictStatus: 'CLEAN',
    conflictBasis: 'AFTER_CLEAN_PREFIX',
    conflictFiles: [],
    ...overrides,
  };
}

function preview(commits: CherryPickPreviewCommit[]): CherryPickPreview {
  return {
    previewId: 'preview-1',
    repository: 'trinodb/trino',
    sourceRemote: 'origin',
    targetRemote: 'cork',
    targetBranch: 'master',
    localTargetBranch: 'cork-master',
    sourceBaseSha: 'b'.repeat(40),
    sourceTipSha: 'c'.repeat(40),
    targetSha: 'a'.repeat(40),
    commits,
    warnings: [],
    expiresAt: '2026-08-20T01:00:00Z',
  };
}

describe('grouping', () => {
  it('orders groups by earliest commit position, not merged time', () => {
    const groups = groupCommits([
      commit(1, { pullRequestNumber: 20, pullRequestTitle: 'Later PR', pullRequestMergedAt: '2026-08-09' }),
      commit(2, { pullRequestNumber: 10, pullRequestTitle: 'Earlier PR', pullRequestMergedAt: '2026-08-01' }),
      commit(3, { pullRequestNumber: 20, pullRequestTitle: 'Later PR', pullRequestMergedAt: '2026-08-09' }),
    ]);

    expect(groups.map(group => group.key)).toEqual([20, 10]);
    expect(groups[0].commits.map(c => c.position)).toEqual([1, 3]);
  });

  it('keeps commits without a pull request individually selectable', () => {
    const groups = groupCommits([
      commit(1, { pullRequestNumber: 7 }),
      commit(2),
      commit(3),
    ]);

    expect(groups).toHaveLength(3);
    expect(groups.filter(group => group.key === null)).toHaveLength(2);
  });

  it('never reorders commits inside a group', () => {
    const groups = groupCommits([
      commit(5, { pullRequestNumber: 1 }),
      commit(2, { pullRequestNumber: 1 }),
      commit(9, { pullRequestNumber: 1 }),
    ]);

    expect(groups[0].commits.map(c => c.position)).toEqual([2, 5, 9]);
  });
});

describe('tri-state selection', () => {
  const group = groupCommits([
    commit(1, { pullRequestNumber: 1 }),
    commit(2, { pullRequestNumber: 1 }),
    commit(3, { pullRequestNumber: 1 }),
  ])[0];

  it('moves none to all and back', () => {
    expect(groupSelection(group, new Set())).toBe('none');
    const all = toggleGroup(group, new Set());
    expect(groupSelection(group, all)).toBe('all');
    expect(groupSelection(group, toggleGroup(group, all))).toBe('none');
  });

  it('reports a partial selection and completes it on the next toggle', () => {
    const partial = new Set([group.commits[0].sourceSha]);
    expect(groupSelection(group, partial)).toBe('some');
    expect(groupSelection(group, toggleGroup(group, partial))).toBe('all');
  });

  it('ignores commits already on the target', () => {
    const mixed = groupCommits([
      commit(1, { pullRequestNumber: 2, alreadyOnTarget: true, targetMatchKind: 'DIRECT_ANCESTOR' }),
      commit(2, { pullRequestNumber: 2 }),
    ])[0];

    const selected = toggleGroup(mixed, new Set());
    expect([...selected]).toEqual([mixed.commits[1].sourceSha]);
    expect(groupSelection(mixed, selected)).toBe('all');
    expect(toggleCommit(mixed.commits[0], new Set()).size).toBe(0);
  });

  it('treats a group that is entirely already on the target as unselectable', () => {
    const present = groupCommits([commit(1, { pullRequestNumber: 3, alreadyOnTarget: true })])[0];
    expect(groupSelection(present, new Set())).toBe('none');
    expect(toggleGroup(present, new Set()).size).toBe(0);
  });
});

describe('search', () => {
  const commits = [
    commit(1, { title: 'Fix Hive coercion', pullRequestNumber: 23401, pullRequestTitle: 'Hive work' }),
    commit(2, { title: 'Improve JDBC metadata', pullRequestNumber: 23418 }),
  ];

  it('matches sha prefix, commit title, pull request number and title', () => {
    expect(matchesSearch(commits[0], commits[0].sourceSha.slice(0, 6))).toBe(true);
    expect(matchesSearch(commits[0], 'hive')).toBe(true);
    expect(matchesSearch(commits[0], '23401')).toBe(true);
    expect(matchesSearch(commits[0], 'Hive work')).toBe(true);
    expect(matchesSearch(commits[1], 'hive')).toBe(false);
    expect(matchesSearch(commits[1], '   ')).toBe(true);
  });

  it('cannot drop a selection, because selections are held by sha', () => {
    const skipped = new Set([commits[1].sourceSha]);
    const visible = commits.filter(c => matchesSearch(c, 'hive'));

    expect(visible).toHaveLength(1);
    expect(skippedShasForConfirmation(preview(commits), skipped)).toEqual([commits[1].sourceSha]);
  });
});

describe('confirmation payload', () => {
  it('sends explicit shas in preview order', () => {
    const commits = [commit(1), commit(2), commit(3)];
    const skipped = new Set([commits[2].sourceSha, commits[0].sourceSha]);

    expect(skippedShasForConfirmation(preview(commits), skipped))
      .toEqual([commits[0].sourceSha, commits[2].sourceSha]);
  });

  it('drops shas the preview does not contain and any already on the target', () => {
    const commits = [commit(1), commit(2, { alreadyOnTarget: true })];
    const skipped = new Set([commits[0].sourceSha, commits[1].sourceSha, 'f'.repeat(40)]);

    expect(skippedShasForConfirmation(preview(commits), skipped)).toEqual([commits[0].sourceSha]);
  });

  it('deduplicates by construction', () => {
    const commits = [commit(1)];
    const skipped = new Set([commits[0].sourceSha, commits[0].sourceSha]);

    expect(skippedShasForConfirmation(preview(commits), skipped)).toHaveLength(1);
  });
});

describe('counts and stale previews', () => {
  it('splits the range into present, skipped and to-pick', () => {
    const commits = [commit(1, { alreadyOnTarget: true }), commit(2), commit(3)];
    const counts = countCommits(commits, new Set([commits[1].sourceSha]));

    expect(counts).toEqual({ total: 3, alreadyOnTarget: 1, userSkipped: 1, toCherryPick: 1 });
  });

  it('keeps selections a refreshed preview can still honour', () => {
    const before = [commit(1), commit(2), commit(3)];
    const skipped = new Set([before[0].sourceSha, before[2].sourceSha]);
    const refreshed = preview([commit(1), commit(2), commit(4, { alreadyOnTarget: true })]);

    expect([...retainSelections(refreshed, skipped)]).toEqual([before[0].sourceSha]);
  });
});
