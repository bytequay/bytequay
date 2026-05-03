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
import React, { useState } from 'react';
import { THEMES, applyTheme, loadTheme, type ThemeId } from '../../themes';
import SettingCard from '../shared/SettingCard';
import SettingRow from '../shared/SettingRow';

const THEME_DOT_COLORS: Record<ThemeId, React.CSSProperties> = {
  'warm': { background: '#c5a85a' },
  'github-light': { background: '#0969da' },
  'atom-one-dark': { background: '#61afef' },
};

function AppearancePage() {
  const [theme, setTheme] = useState<ThemeId>(loadTheme());

  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">Appearance</h2>
          <div className="settings-shell-page__subtitle">Choose a theme. Applies immediately.</div>
        </div>
      </div>

      <SettingCard title="Theme">
        <SettingRow
          title="Active theme"
          description="Light themes for daytime focus, dark themes for late-night review."
          control={
            <div className="theme-picker">
              {THEMES.map(t => (
                <button
                  key={t.id}
                  type="button"
                  className={`theme-swatch${theme === t.id ? ' theme-swatch--active' : ''}`}
                  onClick={() => { setTheme(t.id); applyTheme(t.id); }}
                >
                  <span className="theme-swatch__dot" style={THEME_DOT_COLORS[t.id]} />
                  {t.label}
                </button>
              ))}
            </div>
          }
        />
      </SettingCard>
    </>
  );
}

export default AppearancePage;
