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

/** `+adds −dels` plus the 5-block add:del ratio meter (U13b). Used in the
 *  PR header's tab strip. */
export function DeltaMeter({ additions, deletions }: { additions: number; deletions: number }) {
  const total = additions + deletions;
  const greenBlocks = total === 0 ? 0 : Math.max(1, Math.round((additions / total) * 5));
  const blocks = Array.from({ length: 5 }, (_, i) => i < greenBlocks ? 'g' : total === 0 ? '' : 'r');
  return (
    <span className="delta">
      <span className="add">+{additions}</span> <span className="del">−{deletions}</span>
      <span className="pr-delta-blocks">
        {blocks.map((cls, i) => <i key={i} className={cls} />)}
      </span>
    </span>
  );
}
