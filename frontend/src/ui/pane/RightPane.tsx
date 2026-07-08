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

/** One tab in the right-pane strip. `count` renders a small badge. */
export type PaneTab<K extends string = string> = {
  key: K;
  label: string;
  count?: number;
  /** Badge tint: red (default), accent, or muted. */
  countColor?: 'red' | 'acc' | 'muted';
};

/** The tab strip. Selecting a tab calls `onSelect`; `actions` renders the
 *  trailing header icons. */
function Tabs<K extends string>({ tabs, active, onSelect, actions }: {
  tabs: PaneTab<K>[];
  active: K;
  onSelect: (key: K) => void;
  actions?: ReactNode;
}) {
  return (
    <div className="pane-tabs">
      {tabs.map(t => (
        <button
          key={t.key}
          type="button"
          className={t.key === active ? 'pane-tab active' : 'pane-tab'}
          onClick={() => onSelect(t.key)}
        >
          {t.label}
          {t.count !== undefined && (
            <span className={t.countColor !== undefined && t.countColor !== 'red' ? `count ${t.countColor}` : 'count'}>
              {t.count}
            </span>
          )}
        </button>
      ))}
      {actions !== undefined && (
        <>
          <span className="grow" />
          <span className="header-actions">{actions}</span>
        </>
      )}
    </div>
  );
}

/** A sub-header strip under the tabs (sorting hint, sync status). */
function MetaRow({ left, right }: { left?: ReactNode; right?: ReactNode }) {
  return (
    <div className="pane-meta-row">
      {left !== undefined && <span className="left">{left}</span>}
      {right !== undefined && <span className="right">{right}</span>}
    </div>
  );
}

/** The scrollable content area holding the selected tab's content. `flush`
 *  drops the default padding/gap for a tab (like the embedded Changes diff
 *  viewer) that lays out its own edge-to-edge chrome and would otherwise
 *  show a visible gap around it. */
function Content({ children, flush }: { children: ReactNode; flush?: boolean }) {
  return <div className={flush === true ? 'pane-content pane-content--flush' : 'pane-content'}>{children}</div>;
}

/**
 * The collapsible right pane (~520px). Holds a tab strip + optional meta
 * row + scrollable content. Compose with the `RightPane.Tabs`,
 * `RightPane.MetaRow`, and `RightPane.Content` subcomponents.
 */
export function RightPane({ children }: { children: ReactNode }) {
  return <aside className="pane">{children}</aside>;
}

RightPane.Tabs = Tabs;
RightPane.MetaRow = MetaRow;
RightPane.Content = Content;
