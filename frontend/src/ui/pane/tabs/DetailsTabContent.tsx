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
import type { ReactNode } from 'react';

/** A single key/value detail row. `cost` tints the value green. */
export type DetailRow = { label: string; value: ReactNode; cost?: boolean };
/** A titled group of detail rows (e.g. TASK METRICS, CONTEXT WINDOW). */
export type DetailSection = { title?: string; rows: DetailRow[] };

/**
 * The Details tab — the new home for the metrics that used to live in
 * M8's right-rail cards (task metrics, context window, checkpoints),
 * rendered compactly as grouped key/value rows.
 */
export function DetailsTabContent({ sections }: { sections: DetailSection[] }) {
  return (
    <>
      {sections.map((sec, si) => (
        <div className="plan-sec" key={si}>
          {sec.title !== undefined && <span className="details-sec-h">{sec.title}</span>}
          <div>
            {sec.rows.map((row, ri) => (
              <div className="details-row" key={ri}>
                <span className="k">{row.label}</span>
                <span className={row.cost === true ? 'v cost' : 'v'}>{row.value}</span>
              </div>
            ))}
          </div>
        </div>
      ))}
    </>
  );
}
