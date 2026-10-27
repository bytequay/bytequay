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
import AssistantPanel from '../assistant/AssistantPanel';
import { agentAvailability } from '../agents/availability';
import { ZOOM_REFERENCE, type ZoomedReference } from '../githubReference';
import type { AppNotice } from '../notices/contracts';

/** Where something opened in the floating Assistant shows in the main window. */
export type QuickNavigation = { hash: string } | { zoom: ZoomedReference } | { url: string };

export type QuickAskBridge = {
  close: () => void;
  resize: (height: number) => void;
  navigate: (target: { hash: string } | { zoom: ZoomedReference }) => void;
};

declare global { interface Window { quickAskBridge: QuickAskBridge } }

/** The app's Assistant panel alone in a window above other apps: open while the window shows, and sized to it. */
export default function QuickAsk({ bridge = window.quickAskBridge }: { bridge?: QuickAskBridge }) {
  const [open, setOpen] = useState(() => document.visibilityState === 'visible');
  const [enabled, setEnabled] = useState(false);
  const [notices, setNotices] = useState<AppNotice[]>([]);
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const shown = () => setOpen(document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', shown);
    return () => document.removeEventListener('visibilitychange', shown);
  }, []);

  // Read again each time it shows, as the app reads on focus: the Assistant or its model may have changed meanwhile. The cached model
  // readiness is enough; the app's own start already probed the CLIs.
  useEffect(() => {
    if (!open) return;
    void Promise.all([window.bridge.getSetupPreferences(), window.bridge.getModelSettings()])
      .then(([setup, settings]) => setEnabled(setup.assistantEnabled && agentAvailability(settings).development)).catch(() => {});
  }, [open]);

  useEffect(() => {
    let received = false;
    const stop = window.bridge.onAppNotices(state => { received = true; setNotices(state.notices); });
    void window.bridge.readAppNotices().then(state => { if (!received) setNotices(state.notices); }).catch(() => {});
    return stop;
  }, []);

  // Pages and references open in the main window; this page never moves.
  useEffect(() => {
    const zoom = (event: Event) => bridge.navigate({ zoom: (event as CustomEvent<ZoomedReference>).detail });
    const hash = () => {
      if (window.location.hash === '') return;
      bridge.navigate({ hash: window.location.hash });
      history.replaceState(null, '', window.location.pathname);
    };
    window.addEventListener(ZOOM_REFERENCE, zoom);
    window.addEventListener('hashchange', hash);
    return () => { window.removeEventListener(ZOOM_REFERENCE, zoom); window.removeEventListener('hashchange', hash); };
  }, [bridge]);

  // The window is as tall as the panel, which grows with the conversation up to most of the screen.
  useEffect(() => {
    const node = root.current;
    if (!node) return;
    node.style.setProperty('--quick-max-height', `${Math.round(window.screen.availHeight * 0.75)}px`);
    const observer = new ResizeObserver(() => bridge.resize(node.getBoundingClientRect().height));
    observer.observe(node);
    return () => observer.disconnect();
  }, [bridge]);

  return <div className="quick-ask" ref={root}>
    <AssistantPanel open={open} chatEnabled={enabled} notices={notices} openCard={null} onScreen={null} onClose={bridge.close} />
  </div>;
}
