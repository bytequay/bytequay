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
import { useState } from 'react';

/** One tool call inside an activity group. A failed row pins to the top of
 *  its group with its error inline. */
export type ToolRow = { label: string; failed?: boolean; error?: string };

/** A batch of same-kind tool calls, e.g. `Edit · 5`. Routine reads collapse
 *  to a single summary row (pass one row whose label is the summary). */
export type ToolGroup = { kind: string; rows: ToolRow[] };

/**
 * Layer-2 conversation unit: the activity strip — a batch of auto-run tool
 * calls folded into one `⚙ N calls · breakdown` bar with a grouped,
 * expandable log. Tool calls are the lowest-signal events, so they never
 * each get a spine dot. Failures (red) and edits (blue) bubble up as badges
 * even while collapsed; expanding pins failed rows at the top of their
 * group.
 */
export function ActivityStrip({ groups, filesChanged, forceOpen = false }: {
  groups: ToolGroup[];
  /** "N files changed" blue badge; omit when nothing was edited. */
  filesChanged?: number;
  forceOpen?: boolean;
}) {
  const [selfOpen, setSelfOpen] = useState(false);
  const open = forceOpen || selfOpen;
  const total = groups.reduce((n, g) => n + g.rows.length, 0);
  const failed = groups.reduce((n, g) => n + g.rows.filter(r => r.failed === true).length, 0);
  const breakdown = groups.map(g => `${g.rows.length} ${g.kind}`).join(' · ');

  return (
    <div className={`sp-act${open ? ' open' : ''}`}>
      <button type="button" className="sp-act__bar" onClick={() => setSelfOpen(o => !o)} aria-expanded={open} disabled={forceOpen}>
        <span className="sp-act__gear" aria-hidden>⚙</span>
        <span className="sp-act__cnt">{total} {total === 1 ? 'tool call' : 'tool calls'}</span>
        <span className="sp-act__brk">· {breakdown}</span>
        {filesChanged !== undefined && filesChanged > 0 && (
          <span className="sp-badge sp-badge--edit">{filesChanged} files changed</span>
        )}
        {failed > 0 && <span className="sp-badge sp-badge--fail">{failed} failed</span>}
        <span className="sp-act__chev" aria-hidden>›</span>
      </button>
      {open && (
        <div className="sp-act__log">
          {groups.map((g, gi) => {
            // Failed rows pin to the top of their group.
            const rows = [...g.rows].sort((a, b) => Number(b.failed ?? false) - Number(a.failed ?? false));
            return (
              <div className="sp-act__grp" key={`${g.kind}-${gi}`}>
                <div className="sp-act__gh">{g.kind} · {g.rows.length}</div>
                {rows.map((r, ri) => (
                  <div className={`sp-trow${r.failed === true ? ' sp-trow--fail' : ''}`} key={ri}>
                    <span className={`sp-trow__st ${r.failed === true ? 'fail' : 'ok'}`} aria-hidden>{r.failed === true ? '✕' : '✓'}</span>
                    <span className="sp-trow__cmd">{r.label}</span>
                    {r.error !== undefined && <span className="sp-trow__err">{r.error}</span>}
                  </div>
                ))}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
