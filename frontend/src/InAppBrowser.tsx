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
import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowLeft, ArrowRight, ExternalLink, Globe, Maximize2, Minimize2, PanelsTopLeft, Pin, RotateCw, X } from 'lucide-react';
import { WindowControls } from './AppSidebar';
import { readBrowserDisplayMode } from './browserPreferences';
import type { InAppBrowserTab } from './types';
import WebAddressForm from './WebAddressForm';
import FindBar from './FindBar';

type Props = {
  tab: InAppBrowserTab;
  fullScreen: boolean;
  railExpanded: boolean;
  error: string | null;
  onError: (message: string | null) => void;
  onBack: () => void;
};

export default function InAppBrowser({ tab, fullScreen, railExpanded, error, onError, onBack }: Props) {
  const [displayMode, setDisplayMode] = useState(readBrowserDisplayMode);
  const zoomed = displayMode === 'zoom';
  const slotRef = useRef<HTMLDivElement>(null);
  const tabId = tab.id;
  const pageError = tab.error;
  const run = useCallback((action: Promise<void>) => {
    onError(null);
    void action.catch(cause => onError(cause instanceof Error ? cause.message : 'Unable to update this page.'));
  }, [onError]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented) {
        event.preventDefault();
        onBack();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onBack]);

  useEffect(() => {
    const slot = slotRef.current;
    if (!slot || !tabId) return;
    const bounds = () => {
      const rect = slot.getBoundingClientRect();
      return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
    };
    const report = (cause: unknown) => onError(cause instanceof Error ? cause.message : 'Unable to display this page.');
    void window.bridge.mountInAppBrowser(tabId, bounds()).catch(report);
    const push = () => { void window.bridge.setInAppBrowserBounds(tabId, bounds()).catch(report); };
    const observer = new ResizeObserver(push);
    observer.observe(slot);
    window.addEventListener('resize', push);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', push);
      // Detach the native surface; its live page belongs to the tab, not this component.
      void window.bridge.unmountInAppBrowser(tabId).catch(() => {});
    };
  }, [tabId, pageError, railExpanded, displayMode, onError]);

  return (
    <div className={`inapp-browser-overlay${zoomed ? ' is-zoomed' : ''}`}>
      {zoomed && <button type="button" className="inapp-browser__backdrop" aria-label="Back to ByteQuay" onClick={onBack} />}
    <section className="inapp-browser" role="dialog" aria-label={tab.title || 'Web browser'}>
      <div className="inapp-browser__toolbar">
        {!zoomed && !fullScreen && <WindowControls />}
        <button type="button" className="inapp-browser__back-to-app" onClick={onBack} title="Return to ByteQuay and keep your pages open">
          <ArrowLeft size={16} aria-hidden="true" /> Back to app
        </button>
        <div className="inapp-browser__nav" role="group" aria-label="In-app browser navigation">
          <button type="button" className="inapp-browser__nav-btn" onClick={() => run(window.bridge.inAppGoBack(tab.id))}
            disabled={!tab.canGoBack} title="Back" aria-label="Back"><ArrowLeft size={16} /></button>
          <button type="button" className="inapp-browser__nav-btn" onClick={() => run(window.bridge.inAppGoForward(tab.id))}
            disabled={!tab.canGoForward} title="Forward" aria-label="Forward"><ArrowRight size={16} /></button>
          <button type="button" className="inapp-browser__nav-btn" onClick={() => run(window.bridge.inAppReload(tab.id))}
            disabled={!tab.url} title="Reload" aria-label="Reload"><RotateCw size={16} /></button>
        </div>
        <WebAddressForm key={tab.id} url={tab.url} onNavigate={url => window.bridge.inAppNavigate(tab.id, url)} />
        <button type="button" className={`inapp-browser__open-external${tab.pinned ? ' is-pinned' : ''}`}
          disabled={!tab.url}
          onClick={() => run(window.bridge.pinInAppBrowserTab(tab.id, !tab.pinned))}
          title={tab.pinned ? 'Unpin this page so you can close it permanently' : 'Pin this page to the left bar and keep it after restarting'}
          aria-pressed={tab.pinned} aria-label={tab.pinned ? 'Unpin page' : 'Pin page'}>
          <Pin size={15} />{tab.pinned ? 'Pinned' : 'Pin'}
        </button>
        <button type="button" className="inapp-browser__open-external inapp-browser__popout" onClick={() => run(window.bridge.inAppPopOut(tab.url))}
          disabled={!tab.url} title="Open this page in its own window" aria-label="Pop out page"><PanelsTopLeft size={16} aria-hidden="true" /><span className="inapp-browser__action-label">Pop out</span></button>
        <button type="button" className="inapp-browser__nav-btn" onClick={() => run(window.bridge.openExternal(tab.url))}
          disabled={!tab.url} title="Open in your default browser" aria-label="Open in default browser"><ExternalLink size={16} /></button>
        <button type="button" className="inapp-browser__nav-btn inapp-browser__size" onClick={() => setDisplayMode(zoomed ? 'fullscreen' : 'zoom')}
          title={zoomed ? 'Full screen' : 'Restore zoom view'} aria-label={zoomed ? 'Full screen' : 'Restore zoom view'}>
          {zoomed ? <Maximize2 size={16} /> : <Minimize2 size={16} />}
        </button>
        <button type="button" className="inapp-browser__nav-btn" onClick={() => run(window.bridge.closeInAppBrowserTab(tab.id))}
          title={tab.pinned ? 'Minimize page and keep pin' : 'Close page'} aria-label={tab.pinned ? 'Minimize page' : 'Close page'}><X size={16} /></button>
      </div>
      <FindBar key={`${tab.id}:${tab.url}`} browserTabId={tab.id} />
      {error && <div className="inapp-browser__error" role="alert">{error}</div>}
      <div className="inapp-browser__slot" ref={slotRef}>
        {!tab.url && <div className="inapp-browser__start">
          <Globe size={30} aria-hidden="true" /><h1>New page</h1><p>Enter a web address above to get started.</p>
        </div>}
        {tab.error && <div className="inapp-browser__start" role="alert">
          <Globe size={30} /><h1>Unable to display this page</h1><p>{tab.error}</p>
          <button type="button" className="bq-button" onClick={() => run(window.bridge.inAppReload(tab.id))}>Try again</button>
        </div>}
      </div>
    </section>
    </div>
  );
}
