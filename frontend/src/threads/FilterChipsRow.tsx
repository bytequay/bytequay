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
import { useMemo } from 'react';
import type { ThreadDto } from '../types';
import type { StatusFilter } from './ThreadsLeftRail';

type Props = {
  threads: ThreadDto[];
  /** Set of thread ids that carry at least one unread notification
   *  (the auto* membership). The {@code Mine} chip excludes these. */
  autoIds: ReadonlySet<string>;
  value: StatusFilter;
  onChange: (next: StatusFilter) => void;
};

type ChipId = 'ALL' | 'MINE' | 'REVIEW' | 'AUTO' | 'AWAITING_ME';

const CHIPS: Array<{ id: ChipId; label: string }> = [
  { id: 'ALL',         label: 'All' },
  { id: 'MINE',        label: 'Mine' },
  { id: 'REVIEW',      label: 'Review' },
  { id: 'AUTO',        label: 'auto*' },
  { id: 'AWAITING_ME', label: 'Awaiting me' },
];

/** Horizontal pill row that sits under the page title. Replaces the
 *  rail's per-status section as the primary filter for the Threads
 *  surface — matches {@code threads-auto-filter.png}. Each chip
 *  carries a count derived from the thread set; the active chip is
 *  filled with the workspace accent. */
function FilterChipsRow({ threads, autoIds, value, onChange }: Props) {
  const counts = useMemo(() => computeCounts(threads, autoIds), [threads, autoIds]);

  return (
    <div className="threads-chips" role="tablist" aria-label="Filter threads">
      {CHIPS.map(chip => {
        const active = value === chip.id;
        return (
          <button
            key={chip.id}
            type="button"
            role="tab"
            aria-selected={active}
            className={`threads-chips__chip${
                active ? ' threads-chips__chip--active' : ''
            }${chip.id === 'AUTO' ? ' threads-chips__chip--auto' : ''}`}
            onClick={() => onChange(chip.id)}
          >
            <span>{chip.label}</span>
            {counts[chip.id] > 0 && (
              <span className="threads-chips__count">{counts[chip.id]}</span>
            )}
          </button>
        );
      })}
    </div>
  );
}

function computeCounts(
    threads: ThreadDto[],
    autoIds: ReadonlySet<string>): Record<ChipId, number> {
  let all = 0;
  let mine = 0;
  let review = 0;
  let auto = 0;
  let awaiting = 0;
  for (const t of threads) {
    all++;
    const isAuto = autoIds.has(t.id);
    if (isAuto) {
      auto++;
    }
    if (t.flow === 'review') {
      review++;
    }
    else if (!isAuto) {
      mine++;
    }
    const taskStatus = t.activeTask?.status;
    if (t.status === 'AWAITING'
        || taskStatus === 'AWAITING_REVIEW'
        || taskStatus === 'NEEDS_ATTENTION') {
      awaiting++;
    }
  }
  return { ALL: all, MINE: mine, REVIEW: review, AUTO: auto, AWAITING_ME: awaiting };
}

export default FilterChipsRow;
