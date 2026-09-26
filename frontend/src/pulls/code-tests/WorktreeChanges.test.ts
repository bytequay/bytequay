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
import { expect, it } from 'vitest';
import { worktreeReviewMapping, type WorktreePublishedReview } from './WorktreeChanges';
import type { WorktreeChangesDto } from './contracts';

const base = 'a'.repeat(40);
const head = 'b'.repeat(40);
const changes: WorktreeChangesDto = {
  branch: 'work', scope: 'COMMITTED', baseSha: base, headSha: head, baseLabel: 'main', selectedCommit: null,
  sourceFingerprint: null, truncated: false, staged: [], unstaged: [], untracked: [],
  commits: [{ sha: head, parentSha: base, message: 'Published commit', author: 'dev', authoredAt: null }],
  files: [{ filename: 'source.ts', status: 'modified', additions: 1, deletions: 1, patch: '@@ -1 +1 @@\n-old\n+new\n' }],
};
const published: Pick<WorktreePublishedReview, 'files' | 'commits' | 'loading' | 'error'> = {
  files: [{ ...changes.files[0], patch: '@@ -1 +1 @@\n-old\n+new' }], commits: changes.commits, loading: false, error: null,
};

it('maps an immutable published commit even when the checkout has moved and contains local edits', () => {
  expect(worktreeReviewMapping({ ...changes, scope: 'COMMIT', selectedCommit: head, headSha: 'c'.repeat(40),
    staged: ['draft.ts'], unstaged: ['other.ts'], untracked: ['new.ts'] }, published)).toEqual({ range: { start: 0, end: 0 }, reason: null });
});

it.each(['COMMITTED', 'ALL'] as const)('maps %s only when the displayed comparison matches the complete published PR', scope => {
  expect(worktreeReviewMapping({ ...changes, scope }, published)).toEqual({ range: null, reason: null });
});

it.each<Partial<WorktreeChangesDto>>([
  { scope: 'UNCOMMITTED' },
  { scope: 'ALL', unstaged: ['source.ts'] },
  { scope: 'ALL', staged: ['source.ts'] },
  { scope: 'ALL', untracked: ['new.ts'] },
  { scope: 'COMMIT', selectedCommit: 'c'.repeat(40) },
  { scope: 'COMMIT', selectedCommit: head, commits: [{ ...changes.commits[0], parentSha: 'c'.repeat(40) }] },
  { headSha: 'c'.repeat(40) },
  { baseSha: 'c'.repeat(40) },
  { files: [{ ...changes.files[0], patch: '@@ -1 +1 @@\n-old\n+local' }] },
  { files: [{ ...changes.files[0], filename: 'other.ts' }] },
  { files: [] },
  { truncated: true },
])('keeps unpublished or mismatched snapshots from creating GitHub anchors: %j', override => {
  expect(worktreeReviewMapping({ ...changes, ...override }, published).reason).not.toBeNull();
});

it.each<Partial<typeof published>>([{ loading: true }, { error: 'Unavailable' }, { commits: [] }, { files: [] }])(
  'requires a complete published comparison before enabling comments: %j', override => {
    expect(worktreeReviewMapping(changes, { ...published, ...override }).reason).not.toBeNull();
  });
