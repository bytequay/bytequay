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
import { useCallback, useState } from 'react';

export type ThreadsView = 'list' | 'group' | 'immersive';

const STORAGE_KEY = 'bytequay.threads.view';

/** Three-up view-mode toggle the Threads section surfaces alongside
 *  the filter chips. List is the only mode wired today; Group and
 *  Immersive surface as scaffold so the user can see the toggle's
 *  shape — the bodies land in later phases (group board, immersive
 *  conversation/terminal). The chosen mode persists across mounts
 *  through localStorage so a back-button doesn't reset the choice. */

type Props = {
  value: ThreadsView;
  onChange: (next: ThreadsView) => void;
};

const ITEMS: Array<{ id: ThreadsView; label: string; icon: string }> = [
  { id: 'list',      label: 'List',      icon: '☰' },
  { id: 'group',     label: 'Group',     icon: '▦' },
  { id: 'immersive', label: 'Immersive', icon: '◰' },
];

function ThreadsViewToggle({ value, onChange }: Props) {
  return (
    <div className="threads-view-toggle" role="tablist" aria-label="Threads view">
      {ITEMS.map(item => (
        <button
          key={item.id}
          type="button"
          role="tab"
          aria-selected={value === item.id}
          className={`threads-view-toggle__btn${
              value === item.id ? ' threads-view-toggle__btn--active' : ''}`}
          onClick={() => onChange(item.id)}
        >
          <span className="threads-view-toggle__icon" aria-hidden>{item.icon}</span>
          <span>{item.label}</span>
        </button>
      ))}
    </div>
  );
}

/** Persist the view choice through a mount cycle. Falls back to list
 *  on first render or when the stored value isn't recognised — that's
 *  the no-friction default for a user who hasn't picked yet. */
export function useThreadsView(): [ThreadsView, (next: ThreadsView) => void] {
  const [value, setValue] = useState<ThreadsView>(() => {
    try {
      const raw = window.localStorage.getItem(STORAGE_KEY);
      if (raw === 'list' || raw === 'group' || raw === 'immersive') {
        return raw;
      }
    }
    catch { /* private browsing — fall through */ }
    return 'list';
  });
  const update = useCallback((next: ThreadsView) => {
    setValue(next);
    try { window.localStorage.setItem(STORAGE_KEY, next); }
    catch { /* skip silently */ }
  }, []);
  return [value, update];
}

export default ThreadsViewToggle;
