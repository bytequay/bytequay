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
import { useEffect, useRef, useState } from 'react';
import type { InAppNavState } from './types';

type Props = {
  /** URL to load into the embedded WebContentsView. */
  url: string;
  /** Closes the overlay; the renderer should setState back to null so the
   *  React UI shows again. Also drives the × button in the toolbar. */
  onClose: () => void;
  /** True while the main window is in macOS native fullscreen — the
   *  inset traffic lights vanish in that state, so the toolbar can
   *  drop its 78px left reserve and let "Back to app" sit at the edge. */
  fullScreen: boolean;
};

/**
 * Full-screen overlay that wraps a WebContentsView for any URL. Used
 * when the user clicks an external link in the React UI — the link
 * opens here instead of in the OS browser, so the user has a clear
 * path back via the × close button.
 *
 * Toolbar parallels Chrome's top bar: ‹ back · › forward · ↻ reload ·
 * URL/title field · ↗ open in OS browser (escape hatch) · × close.
 *
 * Lifecycle:
 * - Mount: bridge.mountInAppBrowser(url, bounds), wire up nav-state
 *   listener.
 * - Resize: ResizeObserver pushes new bounds so the WebContentsView
 *   tracks the slot div.
 * - Unmount: bridge.unmountInAppBrowser() releases the WebContents.
 */
export default function InAppBrowser({ url, onClose, fullScreen }: Props) {
  const slotRef = useRef<HTMLDivElement>(null);
  const [nav, setNav] = useState<InAppNavState>({
    url,
    title: '',
    canGoBack: false,
    canGoForward: false,
    loading: true,
  });

  useEffect(() => {
    const unsub = window.bridge.onInAppNavState((s) => setNav(s));
    return unsub;
  }, []);

  // Escape always closes — same affordance Chrome / Safari use to dismiss
  // a transient overlay. Listening on window so the keystroke is captured
  // even when the user's focus is on a toolbar control rather than the
  // (out-of-DOM) WebContentsView.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  useEffect(() => {
    const el = slotRef.current;
    if (!el) return;
    const readBounds = () => {
      const r = el.getBoundingClientRect();
      return { x: r.x, y: r.y, width: r.width, height: r.height };
    };
    void window.bridge.mountInAppBrowser(url, readBounds()).catch(() => {
      /* non-fatal — overlay simply stays empty */
    });
    const push = () => {
      void window.bridge.setInAppBrowserBounds(readBounds()).catch(() => { /* best-effort */ });
    };
    const ro = new ResizeObserver(push);
    ro.observe(el);
    window.addEventListener('resize', push);
    return () => {
      ro.disconnect();
      window.removeEventListener('resize', push);
      void window.bridge.unmountInAppBrowser().catch(() => { /* best-effort */ });
    };
    // Re-mount when url prop changes — main process replaces the
    // WebContentsView entirely, easiest to mirror that on the renderer.
  }, [url]);

  const displayUrl = nav.url || url;
  const displayTitle = nav.title || displayUrl;
  return (
    <div className="inapp-browser">
      <div className={`inapp-browser__toolbar${fullScreen ? '' : ' inapp-browser__toolbar--with-tl-reserve'}`}>
        <button
          type="button"
          className="inapp-browser__back-to-app"
          onClick={onClose}
          title="Close this page and return to the app (Esc)"
        >
          ← Back to app
        </button>
        <div className="inapp-browser__nav" role="group" aria-label="In-app browser navigation">
          <button
            type="button"
            className="inapp-browser__nav-btn"
            onClick={() => void window.bridge.inAppGoBack()}
            disabled={!nav.canGoBack}
            title="Back"
            aria-label="Back"
          >
            ‹
          </button>
          <button
            type="button"
            className="inapp-browser__nav-btn"
            onClick={() => void window.bridge.inAppGoForward()}
            disabled={!nav.canGoForward}
            title="Forward"
            aria-label="Forward"
          >
            ›
          </button>
          <button
            type="button"
            className="inapp-browser__nav-btn"
            onClick={() => void window.bridge.inAppReload()}
            title="Reload"
            aria-label="Reload"
          >
            ↻
          </button>
        </div>
        <div className="inapp-browser__title" title={displayUrl}>
          {nav.loading ? <span className="inapp-browser__loading" aria-hidden="true">…</span> : null}
          <span className="inapp-browser__title-text">{displayTitle}</span>
        </div>
        <button
          type="button"
          className="inapp-browser__open-external"
          onClick={() => void window.bridge.inAppPopOut(displayUrl)}
          title="Open this page in its own window so you can keep the app and the page side-by-side"
        >
          ⧉ Pop out
        </button>
        <button
          type="button"
          className="inapp-browser__open-external"
          onClick={() => void window.bridge.openExternal(displayUrl)}
          title="Open in your default browser"
        >
          ↗ Browser
        </button>
      </div>
      <div className="inapp-browser__slot" ref={slotRef} />
    </div>
  );
}
