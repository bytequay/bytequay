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

/** A chip shown above the composer when the right pane is closed —
 *  carries the same entry point a pane tab or full-page nav would. */
export type InlineChip = {
  icon?: ReactNode;
  label: string;
  count?: number;
  countColor?: 'red' | 'acc';
  onClick?: () => void;
};

/**
 * The chip row rendered above the composer when the right pane is
 * collapsed, keeping the pane tabs + full-page entries (Changes, CI
 * Status, Follow-ups) discoverable.
 */
export function InlineChips({ chips }: { chips: InlineChip[] }) {
  if (chips.length === 0) return null;
  return (
    <div className="inline-chips">
      {chips.map((c, i) => (
        <button key={`${c.label}-${i}`} type="button" className="inline-chip" onClick={c.onClick}>
          {c.icon !== undefined && <span className="ic" aria-hidden>{c.icon}</span>}
          {c.label}
          {c.count !== undefined && (
            <span className={c.countColor !== undefined ? `count ${c.countColor}` : 'count'}>{c.count}</span>
          )}
        </button>
      ))}
    </div>
  );
}
