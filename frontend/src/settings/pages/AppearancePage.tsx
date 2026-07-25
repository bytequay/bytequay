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
import { useEffect } from 'react';
import { applyTheme } from '../../themes';
import SettingsPage from '../shared/SettingsPage';
import { CheckIcon } from '../shared/icons';

/**
 * Settings → Appearance. The picker offers one theme: the Codex Light
 * baseline every surface is now designed against. The other palettes in
 * `themes.ts` still resolve if an install carries one in localStorage,
 * but they are no longer offered — hence the "more coming" line rather
 * than a grid of swatches.
 */
function AppearancePage() {
  // Selecting the only option is a no-op, so normalise on mount instead:
  // an install that still carries an older palette lands on the baseline
  // the moment this page is opened.
  useEffect(() => { applyTheme('github-light'); }, []);

  return (
    <SettingsPage title="Appearance" subtitle="Choose a theme. Applies immediately." width={820}>
      <div className="sv2-card">
        <div className="sv2-card__head">
          <span className="sv2-card__title">Theme</span>
          <span className="sv2-card__hint">Light themes for daytime focus.</span>
        </div>
        <div style={{ padding: '0 18px 18px' }}>
          <div className="sv2-theme">
            <ThemePreview />
            <div className="sv2-theme__foot">
              <span className="sv2-theme__check"><CheckIcon size={11} width={3.2} /></span>
              <span style={{ minWidth: 0 }}>
                <span className="sv2-theme__name">Codex Light</span>
                <span className="sv2-theme__meta">Default · light · high-contrast diffs</span>
              </span>
              <span className="sv2-theme__pill">Active</span>
            </div>
          </div>
          <div className="sv2-soon">More themes coming soon.</div>
        </div>
      </div>
    </SettingsPage>
  );
}

/** Miniature of the three-pane app in this palette — rail, list, detail. */
function ThemePreview() {
  return (
    <div className="sv2-theme__preview" aria-hidden="true">
      <div className="sv2-theme__pane sv2-theme__pane--rail">
        <span className="sv2-theme__dots">
          <i style={{ background: '#ff5f57' }} />
          <i style={{ background: '#febc2e' }} />
          <i style={{ background: '#28c840' }} />
        </span>
        <Bar h={9} bg="#ececee" />
        <Bar h={9} bg="#f1f2f4" />
        <Bar h={9} bg="#f1f2f4" />
        <span style={{ height: 1, background: '#ececee', margin: '2px 0' }} />
        <Bar h={7} bg="#f4f5f6" />
        <Bar h={7} bg="#f4f5f6" />
      </div>

      <div className="sv2-theme__pane sv2-theme__pane--list">
        <span style={{ display: 'flex', gap: 5 }}>
          <Bar h={12} w="34px" bg="#ececee" r={6} />
          <Bar h={12} w="26px" bg="#f6f8fa" r={6} />
        </span>
        {[['88%', '58%', '#dfe3e7'], ['72%', '46%', '#e6eaee'], ['80%', '52%', '#e6eaee']].map(([top, bottom, bg], i) => (
          <span className="sv2-theme__item" key={i}>
            <Bar h={7} w={top} bg={bg} />
            <Bar h={6} w={bottom} bg="#f0f2f4" />
          </span>
        ))}
      </div>

      <div className="sv2-theme__pane sv2-theme__pane--detail">
        <Bar h={10} w="52%" bg="#d7dce1" r={4} />
        <span style={{ display: 'flex', gap: 5 }}>
          <Bar h={13} w="44px" bg="#1f883d" r={999} />
          <Bar h={13} w="56px" bg="#ddf4ff" r={999} />
        </span>
        <span style={{ height: 1, background: '#f0f2f4' }} />
        <span style={{ height: 26, borderRadius: 5, background: '#e6ffec', borderLeft: '2px solid #1a7f37' }} />
        <span style={{ height: 26, borderRadius: 5, background: '#ffebe9', borderLeft: '2px solid #cf222e' }} />
        <Bar h={6} w="70%" bg="#f0f2f4" />
        <Bar h={6} w="44%" bg="#f0f2f4" />
      </div>
    </div>
  );
}

function Bar({ h, w, bg, r = 3 }: { h: number; w?: string; bg: string; r?: number }) {
  return <span className="sv2-theme__bar" style={{ height: h, width: w, background: bg, borderRadius: r }} />;
}

export default AppearancePage;
