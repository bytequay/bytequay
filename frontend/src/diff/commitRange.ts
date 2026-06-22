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
 * Commit selection in the diff view is a *contiguous range* only — a clean
 * `base..tip` slice that always merges into one cumulative diff (an arbitrary
 * subset can't, without cherry-picking). Given the commits in display order,
 * this returns the inclusive run between {@code anchor} and {@code target}.
 *
 * If either sha isn't in the list (stale anchor after a force-push, etc.) it
 * falls back to a single-commit selection of {@code target} — matching the
 * "click outside the current run starts a new single selection" rule.
 */
export function contiguousRange(orderedShas: string[], anchor: string, target: string): Set<string> {
  const a = orderedShas.indexOf(anchor);
  const b = orderedShas.indexOf(target);
  if (a === -1 || b === -1) return new Set([target]);
  const lo = Math.min(a, b);
  const hi = Math.max(a, b);
  return new Set(orderedShas.slice(lo, hi + 1));
}
