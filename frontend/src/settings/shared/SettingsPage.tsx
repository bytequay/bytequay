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

export type SettingsTab<T extends string> = {
  id: T;
  label: string;
  /** Small uppercase chip after the label — used for the ds4 tab. */
  badge?: string;
};

type Props<T extends string> = {
  title: string;
  subtitle?: ReactNode;
  /** Body/header column width. Pages carry different measures: text-heavy
   *  ones stay narrow, table-ish ones (Credentials, AI) run wider. */
  width?: number;
  /** Control pinned to the right of the heading, e.g. "Add credential". */
  action?: ReactNode;
  tabs?: SettingsTab<T>[];
  activeTab?: T;
  onSelectTab?: (tab: T) => void;
  children: ReactNode;
};

/**
 * Page frame for every Settings section: a sticky header carrying the
 * title, subtitle, an optional action and an optional tab bar, over a
 * centred scrolling body. The scroll container is the page itself so the
 * header pins while content moves under it.
 */
function SettingsPage<T extends string>({
  title, subtitle, width = 820, action, tabs, activeTab, onSelectTab, children,
}: Props<T>) {
  const measure = { maxWidth: width };
  return (
    <div className="sv2-page">
      <div className={'sv2-page__head' + (tabs === undefined ? '' : ' sv2-page__head--tabbed')}>
        <div className="sv2-page__head-inner" style={measure}>
          <div className="sv2-page__heading">
            <h2 className="sv2-page__title">{title}</h2>
            {subtitle !== undefined && <div className="sv2-page__subtitle">{subtitle}</div>}
            {tabs !== undefined && (
              <div className="sv2-page__tabs" role="tablist">
                {tabs.map(tab => (
                  <button
                    key={tab.id}
                    type="button"
                    role="tab"
                    aria-selected={activeTab === tab.id}
                    className={'sv2-page__tab' + (activeTab === tab.id ? ' sv2-page__tab--active' : '')}
                    onClick={() => onSelectTab?.(tab.id)}
                  >
                    {tab.label}
                    {tab.badge !== undefined && <span className="sv2-rail__badge">{tab.badge}</span>}
                  </button>
                ))}
              </div>
            )}
          </div>
          {action}
        </div>
      </div>
      <div className="sv2-page__body" style={measure}>{children}</div>
    </div>
  );
}

export default SettingsPage;
