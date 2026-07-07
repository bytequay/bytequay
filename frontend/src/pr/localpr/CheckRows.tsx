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
import type { LocalPRCheck } from '../../types/localPr';

const CHECK_GLYPH: Record<string, { cls: string; glyph: string }> = {
  passed: { cls: 'ok', glyph: '✓' },
  neutral: { cls: 'ok', glyph: '✓' },
  failed: { cls: 'fail', glyph: '✗' },
  running: { cls: 'skip', glyph: '●' },
  pending: { cls: 'skip', glyph: '·' },
};

/** One row per check run (glyph, name, duration/status) — shared by
 *  `MergeBox`'s expanded check list and the standalone Checks tab. */
export function CheckRows({ checks }: { checks: LocalPRCheck[] }) {
  return (
    <>
      {checks.map(c => {
        const g = CHECK_GLYPH[c.status] ?? CHECK_GLYPH.pending;
        return (
          <div className="check-row" key={c.id}>
            <span className={`st ${g.cls}`}>{g.glyph}</span>
            <span className="nm">{c.name}</span>
            <span className="res">
              {c.durationMs !== null ? `${Math.round(c.durationMs / 1000)}s` : c.status}
            </span>
          </div>
        );
      })}
    </>
  );
}
