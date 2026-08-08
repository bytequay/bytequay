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

/** App-level nav destinations in the sidebar's top section. */
export type SidebarNavKey = 'home' | 'my-work' | 'automations';

/** The window-chrome row: the macOS traffic-light dots + the sidebar
 *  collapse toggle on the left, and back/forward on the right. The dots
 *  stand in for the native macOS buttons — hidden behind the real ones
 *  while windowed, shown (red/yellow/green) only in fullscreen where the
 *  OS hides its own (driven by the `.is-fullscreen` class on the rail). */
export function TrafficLights({
  onBack, onForward, backEnabled = true, forwardEnabled = true,
  onToggleCollapse, hideNavArrows = false, className = '',
}: {
  onBack?: () => void;
  onForward?: () => void;
  /** Dim an arrow when its history edge is reached (default enabled). */
  backEnabled?: boolean;
  forwardEnabled?: boolean;
  onToggleCollapse?: () => void;
  hideNavArrows?: boolean;
  className?: string;
}) {
  return (
    <div className={`sb-traffic ${className}`.trim()}>
      {/* The dots stand in for the native macOS buttons in fullscreen
          (where the OS hides its own), so they must actually work. */}
      <div className="dots">
        <span
          className="r"
          role="button"
          aria-label="Close window"
          onClick={() => { void window.bridge.windowControl('close'); }}
        />
        <span
          className="y"
          role="button"
          aria-label="Minimize window"
          onClick={() => { void window.bridge.windowControl('minimize'); }}
        />
        <span
          className="g"
          role="button"
          aria-label="Toggle full screen"
          onClick={() => { void window.bridge.windowControl('zoom'); }}
        />
      </div>
      <span
        className="sb-toggle"
        role="button"
        tabIndex={0}
        aria-label="Toggle sidebar"
        onClick={onToggleCollapse}
      >
        <svg width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden>
          <rect x="2" y="3" width="12" height="10" rx="2.2" stroke="currentColor" strokeWidth="1.3" />
          <line x1="6.4" y1="3.4" x2="6.4" y2="12.6" stroke="currentColor" strokeWidth="1.3" />
        </svg>
      </span>
      {!hideNavArrows && (
        <div className="nav-arrows">
          <span
            role="button"
            tabIndex={0}
            aria-label="Back"
            aria-disabled={!backEnabled}
            onClick={backEnabled ? onBack : undefined}
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" aria-hidden>
              <path d="m15 18-6-6 6-6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </span>
          <span
            role="button"
            tabIndex={0}
            aria-label="Forward"
            aria-disabled={!forwardEnabled}
            onClick={forwardEnabled ? onForward : undefined}
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" aria-hidden>
              <path d="m9 18 6-6-6-6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </span>
        </div>
      )}
    </div>
  );
}
