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

/**
 * Combines per-commit file lists into one list, summing additions and
 * deletions per path. When the same file appears in multiple commits,
 * the LATEST commit's record wins — order the input chronologically
 * (oldest → newest) so {@code status} and any other non-numeric fields
 * reflect the most recent change.
 *
 * Used for both the PR diff viewer's multi-commit selection and the
 * local Commits tab's branch-level multi-select. The math is the same
 * regardless of where the per-commit file lists came from.
 */
export function unionCommitFiles<T extends { additions: number; deletions: number }>(
  perCommit: T[][],
  pathOf: (item: T) => string,
): T[] {
  const byPath = new Map<string, T>();
  for (const commit of perCommit) {
    for (const f of commit) {
      const key = pathOf(f);
      const prev = byPath.get(key);
      byPath.set(key, prev
        ? { ...f, additions: prev.additions + f.additions, deletions: prev.deletions + f.deletions }
        : { ...f });
    }
  }
  return [...byPath.values()];
}
