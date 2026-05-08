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
import { unionCommitFiles } from './unionCommitFiles';

type F = { path: string; status: string; additions: number; deletions: number };
const pathOf = (f: F) => f.path;

describe('unionCommitFiles', () => {
  it('returns an empty list when no commits are given', () => {
    expect(unionCommitFiles<F>([], pathOf)).toEqual([]);
  });

  it('passes a single commit through unchanged', () => {
    const c = [{ path: 'a.ts', status: 'M', additions: 3, deletions: 1 }];
    expect(unionCommitFiles([c], pathOf)).toEqual(c);
  });

  it('sums additions/deletions when a file appears in multiple commits', () => {
    const c1 = [{ path: 'a.ts', status: 'M', additions: 3, deletions: 1 }];
    const c2 = [{ path: 'a.ts', status: 'M', additions: 5, deletions: 2 }];
    expect(unionCommitFiles([c1, c2], pathOf)).toEqual([
      { path: 'a.ts', status: 'M', additions: 8, deletions: 3 },
    ]);
  });

  it('keeps files that only appear in one commit', () => {
    const c1 = [{ path: 'a.ts', status: 'A', additions: 10, deletions: 0 }];
    const c2 = [{ path: 'b.ts', status: 'M', additions: 1, deletions: 1 }];
    const out = unionCommitFiles([c1, c2], pathOf);
    expect(out).toHaveLength(2);
    expect(out.find((f) => f.path === 'a.ts')).toEqual({ path: 'a.ts', status: 'A', additions: 10, deletions: 0 });
    expect(out.find((f) => f.path === 'b.ts')).toEqual({ path: 'b.ts', status: 'M', additions: 1, deletions: 1 });
  });

  it("uses the latest commit's status for files touched more than once", () => {
    // Order is chronological (oldest first). A file added in c1 and then
    // modified in c2 should report status 'M' — that's what the diff
    // panel will show against the rebased base.
    const c1 = [{ path: 'a.ts', status: 'A', additions: 5, deletions: 0 }];
    const c2 = [{ path: 'a.ts', status: 'M', additions: 2, deletions: 1 }];
    expect(unionCommitFiles([c1, c2], pathOf)).toEqual([
      { path: 'a.ts', status: 'M', additions: 7, deletions: 1 },
    ]);
  });
});
