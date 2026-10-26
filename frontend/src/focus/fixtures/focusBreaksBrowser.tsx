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
import { useState } from 'react';
import { flushSync } from 'react-dom';
import InAppBrowser from '../../InAppBrowser';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { assert, button, element, runScenarios, settle, until } from '../../fixtures/harness';
import { GeneralPage } from '../../settings/PersonalPages';
import { FocusBreakProvider, useFocusBreaks } from '../FocusBreakProvider';
import type { FocusBreakBackground } from '../contracts';
import { BreakCompanion, CAT_ARRIVAL_SECONDS, CAT_LEAVE_SECONDS, CAT_REST_SECONDS, CAT_VIDEO_STALL_MS, COMPANION_ENTER_MS, COMPANION_LEAVE_MS, COMPANION_SETTLE_MS } from '../BreakCompanion';
import chubbyCatVideo from '../assets/chubby-cat.webm';
import chubbyCatLeaveVideo from '../assets/chubby-cat-leave.webm';
import dancingHuskySettle from '../assets/dancing-husky-settle.png';
import { FOCUS_PREFERENCES_KEY, parseFocusPreferences, type BreakCompanionKind, type FocusBreakPreferences } from '../model';
import '../../index.css';

declare global { interface Window {
  pressEscape: () => Promise<void>;
  clickPoint: (x: number, y: number) => Promise<void>;
  setReducedMotion: (reduced: boolean) => Promise<void>;
} }

function mount(saved: FocusBreakPreferences | null = null, capture: () => Promise<FocusBreakBackground[]> = async () => []) {
  Object.defineProperty(window, 'bridge', { configurable: true, value: { ...window.bridge, captureFocusBreakBackground: capture } });
  if (saved === null) localStorage.removeItem(FOCUS_PREFERENCES_KEY);
  else localStorage.setItem(FOCUS_PREFERENCES_KEY, JSON.stringify(saved));
  const view = createFixtureRoot();
  let workClicks = 0;
  flushSync(() => view.root.render(<FocusBreakProvider>
    <textarea aria-label="Unfinished work" defaultValue="Keep this draft." />
    <button type="button" style={{ position: 'fixed', top: 8, left: 8 }} onClick={() => { workClicks++; }}>Keep working</button>
    <GeneralPage preferences={{ theme: 'SYSTEM', taskRetentionDays: 7, recentRetentionDays: 14, prWarmCount: 30, autoMergeDelayMinutes: 60 }}
      quietHours={null} quietHoursBusy={false} version="test" clearing={false} error={null} onChange={() => {}} onQuietHours={() => {}} onClearRecent={() => {}} />
  </FocusBreakProvider>));
  return { ...view, workClicks: () => workClicks };
}

function browserSnapshot(id: string): FocusBreakBackground {
  return {
    image: `data:image/svg+xml,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="700" height="360"><rect width="700" height="360" fill="#dfe8ef"/><text x="30" y="60">${id}</text></svg>`)}`,
    x: 140, y: 120, width: 700, height: 360,
  };
}

function mountBrowser(capture: (tabId: string | null, url: string, loading: boolean) => Promise<FocusBreakBackground[]>) {
  const view = createFixtureRoot();
  let nativeTab: string | null = null;
  let currentUrl = 'https://example.com/first';
  let loadingPage = false;
  let workClicks = 0;
  let selectTab: (id: string | null) => void = () => {};
  let navigate: (url: string, loading: boolean) => void = () => {};
  let startBreak = () => {};
  const errors: string[] = [];
  const onError = (message: string | null) => { if (message !== null) errors.push(message); };
  localStorage.setItem(FOCUS_PREFERENCES_KEY, JSON.stringify(saved));
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    ...window.bridge,
    captureFocusBreakBackground: () => capture(nativeTab, currentUrl, loadingPage),
    mountInAppBrowser: async (id: string) => { nativeTab = id; },
    unmountInAppBrowser: async (id: string) => { if (nativeTab === id) nativeTab = null; }, setInAppBrowserBounds: async () => {},
    onFindResult: () => () => {}, onInAppBrowserFind: () => () => {}, stopFindInPage: async () => {},
  } });
  function BrowserPage() {
    const [tabId, setTabId] = useState<string | null>('first');
    const [url, setUrl] = useState('https://example.com/first');
    const [loading, setLoading] = useState(false);
    currentUrl = url;
    loadingPage = loading;
    selectTab = id => flushSync(() => { setTabId(id); setUrl(`https://example.com/${id}`); });
    navigate = (next, pending) => flushSync(() => { setUrl(next); setLoading(pending); });
    startBreak = useFocusBreaks().startBreak;
    return <>
      <div inert={tabId !== null}>
        <button type="button" style={{ position: 'fixed', left: 320, top: 220, width: 180, height: 40 }} onClick={() => { workClicks++; }}>Current app action</button>
      </div>
      {tabId !== null && <InAppBrowser tab={{ id: tabId, url, title: tabId, canGoBack: false, canGoForward: false,
        loading, pinned: false, name: null, favicon: null, error: null }} fullScreen railExpanded={false} error={null} onError={onError} onBack={() => setTabId(null)} />}
    </>;
  }
  flushSync(() => view.root.render(<FocusBreakProvider><BrowserPage /></FocusBreakProvider>));
  return { ...view, startBreak: () => flushSync(startBreak), selectTab: (id: string | null) => selectTab(id), navigate: (url: string, loading = false) => navigate(url, loading),
    workClicks: () => workClicks, errors };
}

function controlClock() {
  const originalNow = Date.now, originalSet = window.setInterval, originalClear = window.clearInterval;
  let now = 1_000_000, id = 1_000_000;
  const intervals = new Map<number, () => void>();
  Date.now = () => now;
  window.setInterval = ((handler: TimerHandler, delay?: number, ...args: unknown[]) => {
    if (delay !== 1000 || typeof handler !== 'function') return originalSet(handler, delay, ...args);
    const key = ++id;
    intervals.set(key, () => handler(...args));
    return key;
  }) as typeof window.setInterval;
  window.clearInterval = timer => { if (typeof timer === 'number' && intervals.delete(timer)) return; originalClear(timer); };
  return {
    tick: (milliseconds: number) => { now += milliseconds; flushSync(() => [...intervals.values()].forEach(run => run())); },
    count: () => intervals.size,
    restore: () => { Date.now = originalNow; window.setInterval = originalSet; window.clearInterval = originalClear; },
  };
}

const saved: FocusBreakPreferences = { enabled: true, workMinutes: 15, breakMinutes: 1, companion: 'chubby-cat' };
const catClipSeconds = CAT_REST_SECONDS + 80 / 30;
const mainVideoSelector = '.break-companion__video:not(.break-companion__leave-video)';
const leaveVideoSelector = '.break-companion__leave-video';

function controlCatVideo() {
  type Media = { time: number; ready: boolean; paused: boolean; plays: number; rejectPlay: boolean; callbacks: Map<number, VideoFrameRequestCallback> };
  const states = new Map<HTMLVideoElement, Media>();
  const original = new Map<string, PropertyDescriptor>();
  let callbackId = 0, rejectPlayback = false;
  const state = (video: HTMLVideoElement) => {
    let value = states.get(video);
    if (!value) { value = { time: 0, ready: false, paused: true, plays: 0, rejectPlay: rejectPlayback, callbacks: new Map() }; states.set(video, value); }
    return value;
  };
  const ownEvents = new WeakSet<Event>();
  const emit = (video: HTMLVideoElement, name: string) => { const event = new Event(name); ownEvents.add(event); video.dispatchEvent(event); };
  const eventNames = ['loadedmetadata', 'loadeddata', 'canplay', 'timeupdate', 'ended', 'playing', 'waiting', 'stalled', 'error', 'seeking', 'seeked'];
  const suppressNative = (event: Event) => { if (event.target instanceof HTMLVideoElement && !ownEvents.has(event)) event.stopImmediatePropagation(); };
  for (const name of eventNames) document.addEventListener(name, suppressNative, true);
  const patch = (prototype: object, key: string, descriptor: PropertyDescriptor) => {
    const previous = Object.getOwnPropertyDescriptor(prototype, key);
    assert(previous, `The browser supports ${key}`);
    original.set(key, previous);
    Object.defineProperty(prototype, key, { configurable: true, ...descriptor });
  };
  patch(HTMLMediaElement.prototype, 'play', { value: function (this: HTMLVideoElement) {
    const media = state(this); media.plays++;
    if (media.rejectPlay) return Promise.reject(new DOMException('Playback unavailable', 'NotSupportedError'));
    media.paused = false;
    return Promise.resolve();
  } });
  patch(HTMLMediaElement.prototype, 'pause', { value: function (this: HTMLVideoElement) { state(this).paused = true; } });
  patch(HTMLMediaElement.prototype, 'load', { value: function (this: HTMLVideoElement) { state(this).paused = true; } });
  patch(HTMLMediaElement.prototype, 'currentTime', { get: function (this: HTMLVideoElement) { return state(this).time; }, set: function (this: HTMLVideoElement, value: number) { state(this).time = value; } });
  patch(HTMLMediaElement.prototype, 'paused', { get: function (this: HTMLVideoElement) { return state(this).paused; } });
  patch(HTMLMediaElement.prototype, 'readyState', { get: function (this: HTMLVideoElement) { return state(this).ready ? 2 : 0; } });
  patch(HTMLMediaElement.prototype, 'duration', { get: function (this: HTMLVideoElement) { return this.matches(leaveVideoSelector) ? CAT_LEAVE_SECONDS : catClipSeconds; } });
  patch(HTMLMediaElement.prototype, 'ended', { get: function (this: HTMLVideoElement) { return state(this).time >= this.duration; } });
  patch(HTMLVideoElement.prototype, 'requestVideoFrameCallback', { value: function (this: HTMLVideoElement, callback: VideoFrameRequestCallback) {
    const id = ++callbackId; state(this).callbacks.set(id, callback); return id;
  } });
  patch(HTMLVideoElement.prototype, 'cancelVideoFrameCallback', { value: function (this: HTMLVideoElement, id: number) { state(this).callbacks.delete(id); } });
  return {
    state,
    rejectPlayback: () => { rejectPlayback = true; },
    event: emit,
    ready: (video: HTMLVideoElement) => { state(video).ready = true; emit(video, 'loadeddata'); },
    frame: (video: HTMLVideoElement, time: number) => {
      const media = state(video); media.time = time;
      const callbacks = [...media.callbacks.values()]; media.callbacks.clear();
      flushSync(() => callbacks.forEach(callback => callback(performance.now(), {
        mediaTime: time, presentedFrames: Math.round(time * (video.matches(leaveVideoSelector) ? 24 : 30)) + 1, width: 2736, height: 1536,
        expectedDisplayTime: performance.now(), presentationTime: performance.now(), processingDuration: 0,
      })));
      emit(video, 'timeupdate');
    },
    restore: () => {
      for (const name of eventNames) document.removeEventListener(name, suppressNative, true);
      for (const [key, descriptor] of original) Object.defineProperty(key.includes('VideoFrame') ? HTMLVideoElement.prototype : HTMLMediaElement.prototype, key, descriptor);
      states.clear();
    },
  };
}

function controlCompanionTimers() {
  const originalSet = window.setTimeout, originalClear = window.clearTimeout;
  const media = controlCatVideo();
  const delays = new Set([COMPANION_ENTER_MS, COMPANION_SETTLE_MS, COMPANION_LEAVE_MS, CAT_VIDEO_STALL_MS]);
  const timers = new Map<number, { delay: number; run: () => void }>();
  let id = 2_000_000;
  window.setTimeout = ((handler: TimerHandler, delay?: number, ...args: unknown[]) => {
    if (delay === undefined || !delays.has(delay) || typeof handler !== 'function') return originalSet(handler, delay, ...args);
    const key = ++id;
    timers.set(key, { delay, run: () => handler(...args) });
    return key;
  }) as typeof window.setTimeout;
  window.clearTimeout = key => { if (typeof key === 'number' && timers.delete(key)) return; originalClear(key); };
  return {
    media,
    tick: async (delay: number) => {
      await until(() => [...timers.values()].some(timer => timer.delay === delay), `The companion must schedule its ${delay}ms transition`);
      const due = [...timers].filter(([, timer]) => timer.delay === delay);
      flushSync(() => { for (const [key, timer] of due) { timers.delete(key); timer.run(); } });
      await settle();
    },
    count: (delay?: number) => delay === undefined ? timers.size : [...timers.values()].filter(timer => timer.delay === delay).length,
    restore: () => { window.setTimeout = originalSet; window.clearTimeout = originalClear; media.restore(); },
  };
}

const companionPhase = (root: ParentNode) => root.querySelector<HTMLElement>('.break-companion')?.dataset.phase;

async function startWalking(root: ParentNode, motion: ReturnType<typeof controlCompanionTimers>) {
  await until(() => root.querySelector('.break-companion') !== null, 'The companion appears after its background is ready');
  const video = root.querySelector<HTMLVideoElement>(mainVideoSelector);
  if (video && companionPhase(root) === 'loading') {
    motion.media.ready(video);
    await settle();
    assert(companionPhase(root) === 'loading', 'Metadata and readiness do not expose an undecoded video');
    motion.media.frame(video, 0);
  }
  await until(() => companionPhase(root) === 'entering', 'The selected companion has a decoded frame before walking');
}

async function companionArrived(root: ParentNode, motion: ReturnType<typeof controlCompanionTimers>) {
  await startWalking(root, motion);
  const companion = element('.break-companion', root).dataset.companion === 'dancing-husky' ? 'dancing-husky' : 'chubby-cat';
  const walkArtwork = await animatedArtwork(root, companion);
  assert(!element<HTMLDialogElement>('dialog[open]', root).matches(':modal'), 'The walk does not lock the app before arrival');
  if (companion === 'chubby-cat') {
    const video = element<HTMLVideoElement>(mainVideoSelector, root);
    motion.media.frame(video, CAT_ARRIVAL_SECONDS - 1 / 30); await settle();
    assert(!element<HTMLDialogElement>('dialog[open]', root).matches(':modal'), 'The cat must finish its walk before locking');
    motion.media.frame(video, CAT_ARRIVAL_SECONDS); await settle();
    await until(() => companionPhase(root) === 'performing', 'The decoded arrival frame locks the app and continues the performance');
    assert(!video.paused && video.currentTime === CAT_ARRIVAL_SECONDS, 'Locking resumes the same clip without jumping or replaying its walk');
  }
  else {
    await motion.tick(COMPANION_ENTER_MS);
    await until(() => companionPhase(root) === 'settling', 'The husky settles only after arriving and locking the app');
    const settleArtwork = await animatedArtwork(root, companion);
    assert(settleArtwork !== walkArtwork, 'Settling uses its own poses after the walk');
    await motion.tick(COMPANION_SETTLE_MS);
    assert(companionPhase(root) === 'looping', 'The companion animates for the remaining break');
    const loopArtwork = await animatedArtwork(root, companion);
    assert(loopArtwork !== walkArtwork && loopArtwork !== settleArtwork, 'The dancing loop follows both arrival sequences');
  }
  assert(element<HTMLDialogElement>('dialog[open]', root).matches(':modal'), 'Arrival locks interaction behind the break');
}

async function confirmDismiss(root: ParentNode) {
  button('Dismiss', root).click();
  await until(() => root.querySelector('.bq-confirm-dialog:modal') !== null, 'Dismiss asks before ending the break');
  button('End break', root).click();
  await settle();
}

async function companionLeaves(root: ParentNode, motion: ReturnType<typeof controlCompanionTimers>) {
  const phase = element('.break-companion', root).dataset.companion === 'chubby-cat' ? 'fading' : 'leaving';
  await until(() => companionPhase(root) === phase, 'The companion begins its short exit before the resting section');
  assert(element<HTMLDialogElement>('dialog[open]', root).matches(':modal'), 'The break stays locked during its exit');
  const leaveVideo = root.querySelector<HTMLVideoElement>(leaveVideoSelector);
  assert(!leaveVideo || leaveVideo.paused && motion.media.state(leaveVideo).plays === 0, 'Early dismissal does not play the resting exit clip');
  await motion.tick(COMPANION_LEAVE_MS);
  assert(root.querySelector('dialog') === null, 'The break releases the app when the companion leaves');
}

async function animatedArtwork(root: ParentNode, companion: BreakCompanionKind) {
  assert(element('.break-companion', root).dataset.companion === companion, 'The chosen companion is rendered');
  if (companion === 'chubby-cat') {
    const video = element<HTMLVideoElement>(mainVideoSelector, root);
    assert(video.readyState >= 2 && !video.paused, 'The cat renders a decoded native video');
    assert(video.muted && video.playsInline && !video.loop, 'The performance plays silently inline without replaying the entrance');
    return video.getAttribute('src');
  }
  const frames = element('.break-companion__frames', root);
  const source = getComputedStyle(frames).backgroundImage.replace(/^url\(["']?|["']?\)$/g, '');
  const artwork = new Image();
  artwork.src = source;
  await artwork.decode();
  assert(artwork.naturalWidth > 100 && artwork.naturalHeight > 100, 'The companion renders a loaded raster sprite sheet');
  assert(frames.getAnimations().some(animation => animation.playState === 'running'), 'The selected companion continues its sprite animation');
  return source;
}

async function chooseCompanion(root: ParentNode, label: string) {
  button('Break companion', root).click(); await settle();
  button(label, element('[role="listbox"]', root)).click(); await settle();
}

async function clickLikeUser(target: HTMLElement) {
  const bounds = target.getBoundingClientRect();
  await window.clickPoint(Math.round(bounds.left + bounds.width / 2), Math.round(bounds.top + bounds.height / 2));
  await settle();
}

export async function run() {
  return runScenarios([
    ['Enable reminders, change durations, and pause the countdown', async () => {
      const clock = controlClock();
      const view = mount();
      try {
        await settle();
        assert(button('Break reminders', view.host).getAttribute('aria-checked') === 'false', 'Reminders start disabled');
        assert(clock.count() === 0 && view.host.querySelector('dialog') === null, 'Disabled reminders create no timer or break');
        button('Focus time', view.host).click(); await settle(); button('15 min', element('[role="listbox"]', view.host)).click(); await settle();
        button('Break time', view.host).click(); await settle(); button('1 min', element('[role="listbox"]', view.host)).click(); await settle();
        button('Break reminders', view.host).click(); await settle();
        const stored = parseFocusPreferences(JSON.parse(localStorage.getItem(FOCUS_PREFERENCES_KEY) ?? '{}'));
        assert(stored.enabled && stored.workMinutes === 15 && stored.breakMinutes === 1 && stored.companion === 'chubby-cat', 'Settings persist selected times and the default companion');
        clock.tick(30_000); await settle();
        assert(view.host.textContent?.includes('14:30 remaining'), 'Visible focus time counts down');
        button('Pause timer', view.host).click(); await settle(); clock.tick(30_000); await settle();
        assert(view.host.textContent?.includes('14:30 remaining'), 'Paused focus time holds');
        button('Resume timer', view.host).click(); await settle(); clock.tick(30_000); await settle();
        assert(view.host.textContent?.includes('14:00 remaining'), 'Resuming continues the same countdown');
      }
      finally { view.dispose(); clock.restore(); }
    }],
    ['The larger animal locks over the existing page and confirmed dismissal restores the draft', async () => {
      const clock = controlClock();
      const motion = controlCompanionTimers();
      const view = mount(saved);
      try {
        await settle();
        const draft = element<HTMLTextAreaElement>('textarea', view.host); draft.focus();
        for (let step = 0; step < 30; step++) clock.tick(30_000);
        await settle();
        const dialog = element<HTMLDialogElement>('dialog[open]', view.host);
        await startWalking(view.host, motion);
        assert(document.activeElement === draft && !dialog.matches(':modal'), 'The walk preserves focus and leaves the app unlocked');
        await clickLikeUser(button('Keep working', view.host));
        assert(view.workClicks() === 1, 'The user can continue working while the companion walks in');
        draft.focus();
        assert(dialog.getBoundingClientRect().width === innerWidth && dialog.getBoundingClientRect().height === innerHeight, 'Break covers the app viewport');
        clock.tick(30_000); await settle();
        assert(element('[role="timer"]', dialog).textContent === '1:00', 'Walking does not consume the break countdown');
        await companionArrived(view.host, motion);
        const dismiss = button('Dismiss', dialog);
        assert(document.activeElement === dismiss && dialog.getAttribute('aria-label') === 'Focus break', 'Locking focuses the compact dismiss control in a named dialog');
        assert(dialog.querySelector('h1, p, .focus-break__eyebrow, .focus-break__caption, .focus-break__hint') === null, 'The companion screen removes visible headings and instructions');
        assert(dialog.querySelectorAll('button').length === 1 && element('[role="timer"]', dialog).getBoundingClientRect().width <= 1, 'Only Dismiss remains visible while the clock remains accessible');
        assert(getComputedStyle(dialog).backgroundColor === 'rgba(0, 0, 0, 0)' && getComputedStyle(dialog).backgroundImage === 'none'
          && getComputedStyle(dialog, '::backdrop').backgroundColor === 'rgba(0, 0, 0, 0)', 'The current page remains visible through the break and its backdrop');
        assert(element('.break-companion', dialog).getBoundingClientRect().width >= 900, 'The cat fills most of the app viewport');
        assert(dismiss.getBoundingClientRect().height <= 32 && dismiss.getBoundingClientRect().width < 100, 'Dismiss stays compact beside the large companion');
        await clickLikeUser(button('Keep working', view.host));
        assert(view.workClicks() === 1, 'The modal lock blocks underlying app actions');
        draft.focus();
        assert(document.activeElement !== draft, 'The lock also prevents focusing an underlying draft');
        clock.tick(1000); await settle();
        assert(element('[role="timer"]', dialog).textContent === '0:59', 'The countdown starts when the companion locks the app');
        await window.pressEscape();
        await until(() => view.host.querySelector('.bq-confirm-dialog:modal') !== null, 'Escape asks before ending the break');
        button('End break', view.host).click(); await settle();
        await companionLeaves(view.host, motion);
        assert(document.activeElement === draft && draft.value === 'Keep this draft.', 'Closing the break returns to the untouched draft');
        assert(view.host.textContent?.includes('15:00 remaining'), 'Dismiss starts a fresh focus session');
      }
      finally { view.dispose(); clock.restore(); motion.restore(); }
    }],
    ['Capture the native page before entrance and keep its image until the break closes', async () => {
      const clock = controlClock();
      const motion = controlCompanionTimers();
      let completeCapture: ((panes: FocusBreakBackground[]) => void) | undefined;
      const view = mount(saved, () => new Promise(resolve => { completeCapture = resolve; }));
      const snapshot: FocusBreakBackground = {
        image: 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" width="600" height="400"%3E%3Crect width="600" height="400" fill="%23dfe8ef"/%3E%3C/svg%3E',
        x: 24, y: 48, width: 600, height: 400,
      };
      try {
        await settle(); button('Take a break now', view.host).click(); await settle();
        await until(() => completeCapture !== undefined, 'Starting a break asks for the visible native page');
        assert(view.host.querySelector('.focus-break') === null, 'The page is captured before the overlay detaches its native view');
        completeCapture?.([snapshot]); await settle();
        await startWalking(view.host, motion);
        const background = element<HTMLImageElement>('.focus-break__background', view.host);
        await background.decode();
        assert(background.getAttribute('src') === snapshot.image && background.alt === '', 'The visible native page becomes a decorative underlay');
        const bounds = background.getBoundingClientRect();
        assert(bounds.x === snapshot.x && bounds.y === snapshot.y && bounds.width === snapshot.width && bounds.height === snapshot.height,
          'The captured page stays in the original native view bounds');
        assert(getComputedStyle(background).pointerEvents === 'none', 'The captured page cannot intercept companion controls');
        await companionArrived(view.host, motion);
        assert(background.isConnected && element<HTMLDialogElement>('.focus-break', view.host).matches(':modal'), 'The native page stays visible while the break locks');
        await confirmDismiss(view.host); await companionLeaves(view.host, motion);
        assert(!background.isConnected, 'Ending the break removes the captured page along with the overlay');
      }
      finally { view.dispose(); clock.restore(); motion.restore(); }
    }],
    ['Canceled page captures cannot reopen a break and capture failures keep it usable', async () => {
      const clock = controlClock();
      const motion = controlCompanionTimers();
      let completeCapture: ((panes: FocusBreakBackground[]) => void) | undefined;
      let view = mount(saved, () => new Promise(resolve => { completeCapture = resolve; }));
      try {
        await settle(); button('Take a break now', view.host).click(); await settle();
        await until(() => completeCapture !== undefined, 'The pending break starts its page capture');
        button('Break reminders', view.host).click(); await settle();
        completeCapture?.([]); await settle();
        assert(view.host.querySelector('.focus-break') === null && clock.count() === 0, 'A capture completed after disabling cannot lock the app');
        view.dispose();
        for (const failure of ['capture', 'image'] as const) {
          view = mount(saved, async () => {
            if (failure === 'capture') throw new Error('Page closed during capture');
            return [{ image: 'data:image/png;base64,invalid', x: 0, y: 0, width: 600, height: 400 }];
          });
          await settle(); button('Take a break now', view.host).click(); await settle();
          await companionArrived(view.host, motion);
          assert(view.host.querySelector('.focus-break__background') === null, 'An unavailable captured page is omitted instead of showing a broken image');
          await confirmDismiss(view.host); await companionLeaves(view.host, motion);
          view.dispose();
        }
      }
      finally { view.dispose(); clock.restore(); motion.restore(); }
    }],
    ['Leaving the browser during entrance removes its screenshot before app controls can be clicked', async () => {
      const clock = controlClock();
      const motion = controlCompanionTimers();
      const captures: (string | null)[] = [];
      const view = mountBrowser(async tabId => { captures.push(tabId); return tabId === null ? [] : [browserSnapshot(tabId)]; });
      try {
        await settle(); view.startBreak(); await startWalking(view.host, motion);
        const previous = element<HTMLImageElement>('.focus-break__background', view.host);
        assert(previous.checkVisibility(), 'The native browser has a captured background during entrance');
        assert(Number(getComputedStyle(element('.focus-break', view.host)).zIndex) > Number(getComputedStyle(element('.inapp-browser-overlay', view.host)).zIndex),
          'The companion and captured page render above the browser DOM during entrance');
        await clickLikeUser(button('Back to app', view.host));
        assert(view.host.querySelector('.inapp-browser') === null && !previous.isConnected,
          'Returning to the app removes the old browser pixels with the browser');
        assert(view.host.querySelector('.focus-break__background') === null && view.workClicks() === 0,
          'Newly enabled app controls must not be hidden under a click-through screenshot');
        const action = button('Current app action', view.host);
        const bounds = action.getBoundingClientRect();
        assert(document.elementFromPoint(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2) === action,
          'The exposed app action receives its own pointer target');
        await clickLikeUser(action);
        assert(view.workClicks() === 1, 'A native click activates the now-visible app control');
        await startWalking(view.host, motion);
        assert(captures.includes(null) && element('[role="timer"]', view.host).textContent === '1:00',
          'Navigation recaptures the current page and preserves the full break');
        assert(view.errors.length === 0, 'Native browser cleanup does not report a page error');
      }
      finally { view.dispose(); clock.restore(); motion.restore(); }
    }],
    ['Navigating within the same browser tab reattaches its page before recapturing', async () => {
      const clock = controlClock();
      const motion = controlCompanionTimers();
      const captures: (string | null)[] = [];
      const oldUrl = 'https://example.com/first';
      const newUrl = 'https://example.com/new-page';
      const view = mountBrowser(async (tabId, url, loading) => { captures.push(tabId); return tabId === null ? [] : [browserSnapshot(loading ? oldUrl : url)]; });
      try {
        await settle(); view.startBreak(); await startWalking(view.host, motion);
        const previous = element<HTMLImageElement>('.focus-break__background', view.host);
        view.navigate(newUrl, true);
        assert(!previous.isConnected, 'A changed URL invalidates the image even when the tab ID remains the same');
        await startWalking(view.host, motion);
        const loadingPicture = element<HTMLImageElement>('.focus-break__background', view.host);
        assert(loadingPicture.getAttribute('src') === browserSnapshot(oldUrl).image, 'A loading native tab can still contain its previous document');
        view.navigate(newUrl, false);
        assert(!loadingPicture.isConnected, 'Load completion invalidates old document pixels without another URL change');
        await startWalking(view.host, motion);
        assert(element<HTMLImageElement>('.focus-break__background', view.host).getAttribute('src') === browserSnapshot(newUrl).image,
          'The loaded document replaces the old pixels in the existing native tab');
        assert(captures.length === 3 && captures.every(tabId => tabId === 'first'),
          'Every capture runs after the current native page is mounted, including a same-tab navigation');
      }
      finally { view.dispose(); clock.restore(); motion.restore(); }
    }],
    ['Replacing a browser tab during entrance captures the new page and restarts its walk', async () => {
      const clock = controlClock();
      const motion = controlCompanionTimers();
      const view = mountBrowser(async tabId => tabId === null ? [] : [browserSnapshot(tabId)]);
      try {
        await settle(); view.startBreak(); await startWalking(view.host, motion);
        const previous = element<HTMLVideoElement>(mainVideoSelector, view.host);
        const oldPicture = browserSnapshot('first').image;
        motion.media.frame(previous, 2); clock.tick(30_000); await settle();
        view.selectTab('second');
        assert([...view.host.querySelectorAll<HTMLImageElement>('.focus-break__background')].every(image => image.getAttribute('src') !== oldPicture),
          'The old tab image is removed in the same commit that selects its replacement');
        await startWalking(view.host, motion);
        assert(element<HTMLImageElement>('.focus-break__background', view.host).getAttribute('src') === browserSnapshot('second').image,
          'The restarted entrance displays the newly selected native page');
        const current = element<HTMLVideoElement>(mainVideoSelector, view.host);
        assert(current !== previous && current.currentTime === 0 && element('[role="timer"]', view.host).textContent === '1:00',
          'The new page receives a fresh entrance without consuming break time');
        await companionArrived(view.host, motion);
        clock.tick(1000); await settle();
        assert(element('[role="timer"]', view.host).textContent === '0:59', 'The preserved break counts down only after the new walk finishes');
      }
      finally { view.dispose(); clock.restore(); motion.restore(); }
    }],
    ['A capture completed after browser navigation cannot replace the current page image', async () => {
      const clock = controlClock();
      const motion = controlCompanionTimers();
      const captures: { tabId: string | null; finish: (pictures: FocusBreakBackground[]) => void }[] = [];
      const view = mountBrowser(tabId => new Promise(resolve => captures.push({ tabId, finish: resolve })));
      try {
        await settle(); view.startBreak();
        await until(() => captures.some(capture => capture.tabId === 'first'), 'The old native page capture is pending');
        view.selectTab('second');
        await until(() => captures.some(capture => capture.tabId === 'second'), 'Navigation requests the replacement page before the old capture returns');
        captures.find(capture => capture.tabId === 'second')?.finish([browserSnapshot('second')]);
        await startWalking(view.host, motion);
        const current = element<HTMLVideoElement>(mainVideoSelector, view.host);
        captures.find(capture => capture.tabId === 'first')?.finish([browserSnapshot('first')]);
        await settle();
        assert(element<HTMLImageElement>('.focus-break__background', view.host).getAttribute('src') === browserSnapshot('second').image,
          'A late old capture cannot put stale pixels over the replacement page');
        assert(element<HTMLVideoElement>(mainVideoSelector, view.host) === current && element('[role="timer"]', view.host).textContent === '1:00',
          'The discarded capture neither restarts the current walk nor changes its break timer');
      }
      finally { view.dispose(); clock.restore(); motion.restore(); }
    }],
    ['A confirmation opened during the walk finishes before the companion locks the app', async () => {
      const clock = controlClock();
      const motion = controlCompanionTimers();
      const view = mount(saved);
      const confirmation = document.createElement('dialog');
      confirmation.innerHTML = '<button>Finish confirmation</button>';
      document.body.append(confirmation);
      try {
        await settle(); button('Take a break now', view.host).click(); await settle();
        await startWalking(view.host, motion);
        const video = element<HTMLVideoElement>(mainVideoSelector, view.host);
        confirmation.showModal();
        const finish = element<HTMLButtonElement>('button', confirmation); finish.focus();
        motion.media.frame(video, CAT_ARRIVAL_SECONDS);
        await until(() => companionPhase(view.host) === 'waiting', 'The arrived companion waits for the open confirmation');
        const breakDialog = element<HTMLDialogElement>('dialog[open]', view.host);
        assert(confirmation.matches(':modal') && !breakDialog.matches(':modal') && document.activeElement === finish,
          'Arrival never covers or steals focus from an existing confirmation');
        clock.tick(30_000); await settle();
        assert(element('[role="timer"]', breakDialog).textContent === '1:00' && video.paused, 'Waiting neither spends break time nor starts the performance');
        assert(video.currentTime === CAT_ARRIVAL_SECONDS, 'The cat holds its exact arrival frame while confirmation is open');
        const draft = element<HTMLTextAreaElement>('textarea', view.host);
        confirmation.close(); confirmation.remove(); draft.focus();
        await until(() => breakDialog.matches(':modal') && companionPhase(view.host) === 'performing', 'Closing the confirmation lets the companion lock and resume');
        assert(!video.paused && video.currentTime === CAT_ARRIVAL_SECONDS, 'The performance resumes from the held frame');
        assert(element('[role="timer"]', breakDialog).textContent === '1:00', 'The user receives the full break after the confirmation');
        assert(companionPhase(view.host) === 'performing', 'The cat performs after the screen is locked');
        await confirmDismiss(view.host); await companionLeaves(view.host, motion);
        assert(document.activeElement === draft, 'The lock returns focus to the work selected after the confirmation');
      }
      finally { confirmation.remove(); view.dispose(); clock.restore(); motion.restore(); }
    }],
    ['Cancel dismissal or let the break finish automatically', async () => {
      const clock = controlClock();
      const motion = controlCompanionTimers();
      const view = mount(saved);
      try {
        await settle(); button('Take a break now', view.host).click(); await settle();
        await companionArrived(view.host, motion);
        await animatedArtwork(view.host, 'chubby-cat');
        button('Dismiss', view.host).click(); await settle();
        await until(() => view.host.querySelector('.bq-confirm-dialog:modal') !== null, 'Dismiss opens a confirmation');
        const confirmation = element<HTMLDialogElement>('.bq-confirm-dialog', view.host);
        assert(confirmation.textContent?.includes('End your break?') && document.activeElement === button('Keep taking a break', confirmation),
          'The confirmation describes ending the break and focuses the safe choice');
        const confirmationTimer = element('[role="timer"]', confirmation);
        assert(confirmationTimer.checkVisibility() && confirmationTimer.textContent === '1:00', 'The confirmation displays the remaining break time');
        clock.tick(1000); await settle();
        assert(element('[role="timer"]', confirmation).textContent === '0:59', 'The visible confirmation countdown continues while dismissal is awaiting confirmation');
        button('Keep taking a break', confirmation).click(); await settle();
        assert(view.host.querySelector('.bq-confirm-dialog') === null && element<HTMLDialogElement>('.focus-break', view.host).matches(':modal'),
          'Canceling the confirmation leaves the current break locked');
        assert(document.activeElement === button('Dismiss', view.host), 'Canceling returns focus to Dismiss');
        button('Dismiss', view.host).click(); await settle();
        await until(() => view.host.querySelector('.bq-confirm-dialog:modal') !== null, 'The confirmation can be opened again');
        clock.tick(59_000); await settle();
        await companionLeaves(view.host, motion);
        assert(view.host.querySelector('dialog') === null && view.host.textContent?.includes('15:00 remaining'),
          'An elapsed break returns automatically even when dismissal was awaiting confirmation');
      }
      finally { view.dispose(); clock.restore(); motion.restore(); }
    }],
    ['Choose a different companion without resetting the timer and restore that choice', async () => {
      const clock = controlClock();
      const motion = controlCompanionTimers();
      let view = mount(saved);
      try {
        await settle(); button('Take a break now', view.host).click(); await settle();
        await companionArrived(view.host, motion);
        const catArtwork = await animatedArtwork(view.host, 'chubby-cat');
        await confirmDismiss(view.host);
        await companionLeaves(view.host, motion);
        clock.tick(30_000); await settle();
        await chooseCompanion(view.host, 'Dancing husky');
        assert(view.host.textContent?.includes('14:30 remaining'), 'Changing companion preserves the active countdown');
        clock.tick(30_000); await settle();
        assert(view.host.textContent?.includes('14:00 remaining'), 'The existing countdown continues after a companion change');
        button('Pause timer', view.host).click(); await settle();
        await chooseCompanion(view.host, 'Chubby orange cat');
        await chooseCompanion(view.host, 'Dancing husky');
        clock.tick(30_000); await settle();
        assert(view.host.textContent?.includes('14:00 remaining') && button('Resume timer', view.host), 'Changing companions preserves the paused countdown');
        const restored = parseFocusPreferences(JSON.parse(localStorage.getItem(FOCUS_PREFERENCES_KEY) ?? '{}'));
        assert(restored.companion === 'dancing-husky', 'The selected companion is persisted');
        view.dispose(); view = mount(restored); await settle();
        assert(button('Break companion', view.host).textContent?.includes('Dancing husky'), 'A new window restores the selected companion');
        button('Take a break now', view.host).click(); await settle();
        await companionArrived(view.host, motion);
        const huskyArtwork = await animatedArtwork(view.host, 'dancing-husky');
        assert(huskyArtwork !== catArtwork, 'The two choices render their own artwork');
      }
      finally { view.dispose(); clock.restore(); motion.restore(); }
    }],
    ['Restart with saved preferences and stop the timer when disabled', async () => {
      const clock = controlClock();
      const view = mount(saved);
      try {
        await settle();
        assert(button('Break reminders', view.host).getAttribute('aria-checked') === 'true', 'Remount restores enabled preference');
        assert(button('Focus time', view.host).textContent?.includes('15 min') && button('Break time', view.host).textContent?.includes('1 min'), 'Remount restores durations');
        assert(view.host.textContent?.includes('15:00 remaining'), 'A new window starts a fresh session');
        button('Break reminders', view.host).click(); await settle();
        assert(clock.count() === 0, 'Disabled reminders release the timer');
        clock.tick(30_000); await settle();
        assert(view.host.querySelector('dialog') === null && !view.host.textContent?.includes('Take a break now'), 'Disabled reminders show no break actions');
        assert(JSON.parse(localStorage.getItem(FOCUS_PREFERENCES_KEY) ?? '{}').enabled === false, 'Disabled preference is retained');
      }
      finally { view.dispose(); clock.restore(); localStorage.removeItem(FOCUS_PREFERENCES_KEY); }
    }],
    ['Changing companions during the walk restarts arrival and disabling cancels it', async () => {
      const clock = controlClock();
      const motion = controlCompanionTimers();
      const view = mount(saved);
      try {
        await settle(); button('Take a break now', view.host).click(); await settle();
        await startWalking(view.host, motion);
        const oldVideo = element<HTMLVideoElement>(mainVideoSelector, view.host);
        clock.tick(30_000); await settle();
        await chooseCompanion(view.host, 'Dancing husky');
        assert(oldVideo.paused && motion.media.state(oldVideo).callbacks.size === 0, 'Switching companion stops the old video and decoded-frame callbacks');
        await until(() => companionPhase(view.host) === 'entering'
          && element('.break-companion', view.host).dataset.companion === 'dancing-husky' && motion.count() === 1,
        'Switching companions cancels the previous walk and starts one arrival timer after the new artwork loads');
        assert(!element<HTMLDialogElement>('dialog[open]', view.host).matches(':modal') && element('[role="timer"]', view.host).textContent === '1:00',
          'Switching during entry keeps the app unlocked and preserves the full break');
        button('Break reminders', view.host).click(); await settle();
        assert(view.host.querySelector('dialog') === null && motion.count() === 0 && clock.count() === 0, 'Disabling during entry removes the companion and releases both timers');
        clock.tick(60_000); await settle();
        assert(view.host.querySelector('dialog') === null, 'A canceled walk cannot later lock the app');
      }
      finally { view.dispose(); clock.restore(); motion.restore(); }
    }],
    ['Selecting the cat again requires a new arrival while another modal is open', async () => {
      const clock = controlClock();
      const motion = controlCompanionTimers();
      const view = mount(saved);
      const confirmation = document.createElement('dialog');
      confirmation.innerHTML = '<button>Finish confirmation</button>';
      document.body.append(confirmation);
      try {
        await settle(); button('Take a break now', view.host).click(); await settle();
        await startWalking(view.host, motion);
        confirmation.showModal();
        motion.media.frame(element<HTMLVideoElement>(mainVideoSelector, view.host), CAT_ARRIVAL_SECONDS); await settle();
        assert(companionPhase(view.host) === 'waiting', 'The first cat waits after reaching the confirmation');
        await chooseCompanion(view.host, 'Dancing husky');
        await chooseCompanion(view.host, 'Chubby orange cat');
        confirmation.close(); confirmation.remove(); await settle();
        assert(!element<HTMLDialogElement>('dialog[open]', view.host).matches(':modal'), 'A previous appearance of this companion cannot lock the new entrance');
        await companionArrived(view.host, motion);
      }
      finally { confirmation.remove(); view.dispose(); clock.restore(); motion.restore(); }
    }],
    ['Escape cancels confirmation and either dismiss control can skip a confirmed exit', async () => {
      const clock = controlClock();
      const motion = controlCompanionTimers();
      const view = mount(saved);
      try {
        for (const skip of ['Escape', 'Dismiss']) {
          await settle(); button('Take a break now', view.host).click(); await settle();
          await companionArrived(view.host, motion);
          const video = element<HTMLVideoElement>(mainVideoSelector, view.host);
          const leaveVideo = element<HTMLVideoElement>(leaveVideoSelector, view.host);
          motion.media.frame(video, CAT_REST_SECONDS); await settle();
          assert(companionPhase(view.host) === 'resting' && !video.paused, 'The resting cat keeps breathing while dismissal is available');
          await window.pressEscape(); await settle();
          await until(() => view.host.querySelector('.bq-confirm-dialog:modal') !== null, 'The first Escape asks to end the break');
          await window.pressEscape(); await settle();
          assert(view.host.querySelector('.bq-confirm-dialog') === null && element<HTMLDialogElement>('.focus-break', view.host).matches(':modal'),
            'Escape in the confirmation cancels dismissal without unlocking the break');
          await confirmDismiss(view.host);
          assert(companionPhase(view.host) === 'leaving' && video.paused && motion.media.state(video).callbacks.size === 0 && !leaveVideo.paused,
            'Confirmed dismissal replaces the resting loop with the native exit clip');
          if (skip === 'Escape') await window.pressEscape();
          else button('Dismiss', view.host).click();
          await until(() => view.host.querySelector('dialog') === null, `${skip} during the confirmed exit returns immediately`);
          assert(leaveVideo.paused && motion.media.state(leaveVideo).callbacks.size === 0 && motion.count() === 0,
            'Skipping the exit cancels playback, decoded-frame callbacks, and its watchdog');
          assert(view.host.textContent?.includes('15:00 remaining'), 'Skipping the animation preserves the restarted session');
        }
      }
      finally { view.dispose(); clock.restore(); motion.restore(); }
    }],
    ['Reduced motion keeps either companion still and dismisses without an exit delay', async () => {
      await window.setReducedMotion(true);
      const clock = controlClock();
      const motion = controlCompanionTimers();
      try {
        for (const companion of ['chubby-cat', 'dancing-husky'] as const) {
          const view = mount({ ...saved, companion });
          try {
            await settle(); button('Take a break now', view.host).click(); await settle();
            await until(() => companionPhase(view.host) === 'still', 'Reduced motion uses a resting still');
            await until(() => element<HTMLDialogElement>('dialog[open]', view.host).matches(':modal'), 'Reduced motion locks directly without a walk');
            assert(element('.break-companion', view.host).dataset.companion === companion, 'Reduced motion keeps the selected companion');
            assert(motion.count() === 0, 'Reduced motion schedules no animation transitions');
            assert(!element('.break-companion', view.host).getAnimations({ subtree: true }).some(animation => animation.playState === 'running'), 'Reduced motion stops the sprite and entrance animations');
            await confirmDismiss(view.host);
            await until(() => view.host.querySelector('dialog') === null, 'Reduced motion returns without playing an exit');
          }
          finally { view.dispose(); }
        }
      }
      finally { clock.restore(); motion.restore(); await window.setReducedMotion(false); }
    }],
    ['Unavailable companion artwork leaves the accessible clock and dismiss control usable', async () => {
      const originalImage = window.Image;
      const clock = controlClock();
      const motion = controlCompanionTimers();
      try {
        for (const failure of ['all', 'settle'] as const) {
          window.Image = class extends originalImage {
            override set src(source: string) {
              if (failure === 'all' || source === dancingHuskySettle) queueMicrotask(() => this.dispatchEvent(new Event('error')));
              else super.src = source;
            }
            override get src() { return super.src; }
          };
          const view = mount({ ...saved, companion: 'dancing-husky' });
          try {
            await settle(); button('Take a break now', view.host).click(); await settle();
            await until(() => companionPhase(view.host) === (failure === 'all' ? 'unavailable' : 'still'), 'Failed artwork produces a usable fallback');
            await until(() => element<HTMLDialogElement>('dialog[open]', view.host).matches(':modal'), 'Missing artwork still opens the dismissible break');
            assert(element('[role="timer"]', view.host).textContent === '1:00' && motion.count() === 0, 'Artwork failure keeps the full countdown and skips incomplete arrival animations');
            await confirmDismiss(view.host);
            if (failure === 'all') await until(() => view.host.querySelector('dialog') === null, 'Missing artwork never traps the app behind an animation');
            else await companionLeaves(view.host, motion);
          }
          finally { view.dispose(); }
        }
      }
      finally { clock.restore(); motion.restore(); window.Image = originalImage; }
    }],
    ['Video failure, rejected playback, and missing decoded progress keep the break usable', async () => {
      for (const failure of ['error', 'rejected-play', 'loading-timeout', 'stalled-playback'] as const) {
        const clock = controlClock();
        const motion = controlCompanionTimers();
        if (failure === 'rejected-play') motion.media.rejectPlayback();
        const view = mount(saved);
        try {
          await settle(); button('Take a break now', view.host).click(); await settle();
          const video = element<HTMLVideoElement>(mainVideoSelector, view.host);
          if (failure === 'error') motion.media.event(video, 'error');
          if (failure === 'stalled-playback') await startWalking(view.host, motion);
          if (failure === 'loading-timeout' || failure === 'stalled-playback') await motion.tick(CAT_VIDEO_STALL_MS);
          await until(() => companionPhase(view.host) === 'still', `${failure} falls back to a static cat`);
          await until(() => element<HTMLDialogElement>('dialog[open]', view.host).matches(':modal'), 'Failed playback cannot leave the break stuck in its entrance');
          assert(video.paused && motion.media.state(video).callbacks.size === 0 && motion.count() === 0, 'Fallback releases playback, decoded-frame callbacks, and watchdogs');
          assert(element('[role="timer"]', view.host).textContent === '1:00', 'Failed playback retains the full break');
          await confirmDismiss(view.host);
          await until(() => view.host.querySelector('dialog') === null, 'Static fallback returns immediately without waiting for media');
        }
        finally { view.dispose(); clock.restore(); motion.restore(); }
      }
      for (const failure of ['error', 'rejected-play', 'loading-timeout', 'stalled-playback'] as const) {
        const clock = controlClock();
        const motion = controlCompanionTimers();
        const view = mount(saved);
        try {
          await settle(); button('Take a break now', view.host).click(); await settle();
          await companionArrived(view.host, motion);
          const video = element<HTMLVideoElement>(mainVideoSelector, view.host);
          const leaveVideo = element<HTMLVideoElement>(leaveVideoSelector, view.host);
          motion.media.frame(video, CAT_REST_SECONDS); await settle();
          if (failure === 'rejected-play') motion.media.state(leaveVideo).rejectPlay = true;
          await confirmDismiss(view.host);
          if (failure === 'error') motion.media.event(leaveVideo, 'error');
          if (failure === 'stalled-playback') { motion.media.ready(leaveVideo); motion.media.frame(leaveVideo, 0); await settle(); }
          if (failure === 'loading-timeout' || failure === 'stalled-playback') await motion.tick(CAT_VIDEO_STALL_MS);
          await until(() => view.host.querySelector('dialog') === null, `Exit ${failure} releases the lock without waiting for another animation`);
          assert(video.paused && leaveVideo.paused && motion.media.state(video).callbacks.size === 0
            && motion.media.state(leaveVideo).callbacks.size === 0 && motion.count() === 0,
          'A failed exit releases both videos, frame callbacks, and watchdogs');
          assert(view.host.textContent?.includes('15:00 remaining'), 'A failed exit starts a complete focus session');
        }
        finally { view.dispose(); clock.restore(); motion.restore(); }
      }
    }],
    ['Resting repeats until expiry and the native exit keeps the lock until it ends', async () => {
      const clock = controlClock();
      const motion = controlCompanionTimers();
      const view = mount(saved);
      const visibility = Object.getOwnPropertyDescriptor(document, 'visibilityState');
      const hidden = Object.getOwnPropertyDescriptor(document, 'hidden');
      const setVisible = (visible: boolean) => {
        Object.defineProperty(document, 'visibilityState', { configurable: true, value: visible ? 'visible' : 'hidden' });
        Object.defineProperty(document, 'hidden', { configurable: true, value: !visible });
        document.dispatchEvent(new Event('visibilitychange'));
      };
      try {
        await settle(); button('Take a break now', view.host).click(); await settle();
        await startWalking(view.host, motion);
        const video = element<HTMLVideoElement>(mainVideoSelector, view.host);
        const leaveVideo = element<HTMLVideoElement>(leaveVideoSelector, view.host);
        assert(leaveVideo.preload === 'auto' && leaveVideo.paused && leaveVideo.getAttribute('data-ready') === 'false', 'The exit clip preloads without playing or covering the main video');
        setVisible(false); await settle();
        assert(video.paused && motion.count() === 0, 'A hidden window pauses playback and its stall watchdog');
        setVisible(true); await settle();
        assert(!video.paused, 'Returning to the window resumes the same entrance');
        await companionArrived(view.host, motion);
        motion.media.frame(video, CAT_REST_SECONDS); await settle();
        assert(companionPhase(view.host) === 'resting' && !video.paused, 'The performance flows into the moving resting section');
        const actor = element<HTMLElement>('.break-companion__video-actor', view.host);
        for (let loop = 0; loop < 3; loop++) {
          motion.media.frame(video, catClipSeconds); motion.media.event(video, 'ended'); await settle();
          assert(video.currentTime === CAT_REST_SECONDS && !video.paused && companionPhase(view.host) === 'resting',
            'Each completed rest loop returns to the resting section instead of the entrance');
          assert(motion.media.state(video).callbacks.size === 1 && motion.count() === 1, 'Each loop keeps one frame callback and one stall watchdog');
          assert(actor.style.getPropertyValue('--cat-entry-progress') === '1' && element<HTMLDialogElement>('.focus-break', view.host).matches(':modal'),
            'Looping leaves the cat centered and the break locked');
          motion.media.frame(video, CAT_REST_SECONDS + 0.5); await settle();
        }
        setVisible(false); await settle();
        assert(video.paused && motion.media.state(video).callbacks.size === 0 && motion.count() === 0, 'Hiding the window pauses the resting loop and clears pending frame work');
        const restingTime = video.currentTime;
        setVisible(true); await settle();
        assert(!video.paused && video.currentTime === restingTime && companionPhase(view.host) === 'resting', 'Returning resumes the current resting pose without replaying the walk');
        clock.tick(1000); await settle();
        assert(element('[role="timer"]', view.host).textContent === '0:59', 'The break countdown continues while the cat rests');
        clock.tick(59_000); await settle();
        assert(companionPhase(view.host) === 'leaving' && video.paused && motion.media.state(video).callbacks.size === 0 && !leaveVideo.paused,
          'Expiry stops the resting loop and starts the separate exit clip');
        assert(!video.hidden && leaveVideo.getAttribute('data-ready') === 'false', 'The last resting frame remains visible until the first decoded exit frame');
        motion.media.ready(leaveVideo); await settle();
        assert(!video.hidden && leaveVideo.getAttribute('data-ready') === 'false', 'Readiness alone does not expose an undecoded exit frame');
        assert(motion.count(COMPANION_LEAVE_MS) === 0, 'Native exit never schedules the old short fade timeout');
        motion.media.event(video, 'ended'); await settle();
        assert(companionPhase(view.host) === 'leaving' && !leaveVideo.paused, 'A late end from the old clip cannot interrupt the exit');
        motion.media.frame(leaveVideo, 0); await settle();
        assert(video.hidden && leaveVideo.getAttribute('data-ready') === 'true' && getComputedStyle(leaveVideo).opacity === '1',
          'The decoded exit frame replaces the held resting frame');
        motion.media.frame(leaveVideo, 1); await settle();
        setVisible(false); await settle();
        assert(leaveVideo.paused && motion.media.state(leaveVideo).callbacks.size === 0 && motion.count() === 0,
          'A hidden window pauses the exit without a pending stall watchdog');
        setVisible(true); await settle();
        assert(!leaveVideo.paused && leaveVideo.currentTime === 1 && motion.media.state(leaveVideo).callbacks.size === 1,
          'Returning resumes the exit from its held frame');
        clock.tick(CAT_LEAVE_SECONDS * 1000); await settle();
        assert(element<HTMLDialogElement>('.focus-break', view.host).matches(':modal'), 'Wall time cannot unlock the break before native exit playback ends');
        assert(view.host.textContent?.includes('15:00 remaining'), 'The next focus countdown stays at its full duration while the cat leaves');
        motion.media.frame(leaveVideo, CAT_LEAVE_SECONDS);
        motion.media.event(leaveVideo, 'ended'); motion.media.event(leaveVideo, 'ended');
        await until(() => view.host.querySelector('dialog') === null, 'The native ended event releases the break');
        assert(video.paused && leaveVideo.paused && motion.media.state(video).callbacks.size === 0
          && motion.media.state(leaveVideo).callbacks.size === 0 && motion.count() === 0, 'Completion releases both videos and every pending frame and timer');
        assert(view.host.textContent?.includes('15:00 remaining'), 'The next focus session receives its full duration after the exit finishes');
        clock.tick(1000); motion.media.event(leaveVideo, 'ended'); await settle();
        assert(view.host.textContent?.includes('14:59 remaining'), 'A late duplicate ending cannot restart the new focus session');

        const direct = createFixtureRoot();
        let exits = 0;
        const render = (exiting: boolean) => flushSync(() => direct.root.render(<BreakCompanion companion="chubby-cat" locked exiting={exiting}
          onArrived={() => {}} onExited={() => { exits++; }} />));
        try {
          render(false);
          const main = element<HTMLVideoElement>(mainVideoSelector, direct.host);
          const leave = element<HTMLVideoElement>(leaveVideoSelector, direct.host);
          motion.media.frame(main, CAT_ARRIVAL_SECONDS); motion.media.frame(main, CAT_REST_SECONDS);
          render(true);
          motion.media.event(leave, 'ended'); motion.media.event(leave, 'ended');
          assert(exits === 1, 'Duplicate native ending events invoke the completion callback exactly once');
        }
        finally { direct.dispose(); }
      }
      finally {
        view.dispose(); clock.restore(); motion.restore();
        if (visibility) Object.defineProperty(document, 'visibilityState', visibility); else Reflect.deleteProperty(document, 'visibilityState');
        if (hidden) Object.defineProperty(document, 'hidden', hidden); else Reflect.deleteProperty(document, 'hidden');
      }
    }],
    ['The shipped transparent videos decode, rest, and finish the native exit in Electron', async () => {
      const view = mount(saved);
      try {
        await settle(); button('Take a break now', view.host).click();
        await until(() => view.host.querySelector(mainVideoSelector) !== null, 'The cat video mounts after the page capture');
        const video = element<HTMLVideoElement>(mainVideoSelector, view.host);
        assert(video.getAttribute('src') === chubbyCatVideo, 'Native playback uses the shipped cat asset');
        await until(() => video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA && video.currentTime > 0.3, 'Electron decodes and advances the delivered video');
        assert(video.videoWidth === 2736 && video.videoHeight === 1536, 'The player decodes the higher-resolution cat video');
        const firstTime = video.currentTime, firstFrames = video.getVideoPlaybackQuality().totalVideoFrames;
        await until(() => video.currentTime > firstTime + 0.1 && video.getVideoPlaybackQuality().totalVideoFrames > firstFrames, 'Native decoded frames continue advancing');
        const canvas = document.createElement('canvas'); canvas.width = video.videoWidth; canvas.height = video.videoHeight;
        const context = canvas.getContext('2d', { willReadFrequently: true });
        assert(context, 'Canvas can inspect the decoded alpha channel');
        context.drawImage(video, 0, 0);
        const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
        let transparent = 0, opaque = 0, soft = 0;
        for (let index = 3; index < pixels.length; index += 4) {
          const alpha = pixels[index];
          if (alpha === 0) transparent++;
          else if (alpha === 255) opaque++;
          else soft++;
        }
        assert(transparent > canvas.width * canvas.height / 4, 'The delivered clip has a transparent background instead of a video rectangle');
        assert(opaque > 1000 && soft > 100, 'Opaque animal detail and soft fur edges survive decoding');
        assert(video.duration > CAT_REST_SECONDS + 2, 'The clip contains an appended resting cycle after the one-time performance');
        video.currentTime = CAT_ARRIVAL_SECONDS;
        await until(() => companionPhase(view.host) === 'performing', 'A decoded arrival frame starts the performance');
        let endings = 0;
        const ended = () => { endings++; };
        video.addEventListener('ended', ended);
        try {
          for (let loop = 0; loop < 2; loop++) {
            video.currentTime = video.duration - 0.1;
            await until(() => endings > loop && video.currentTime >= CAT_REST_SECONDS && video.currentTime < CAT_REST_SECONDS + 1
              && !video.paused && companionPhase(view.host) === 'resting', 'Native playback restarts only the appended resting cycle');
            const restingTime = video.currentTime, restingFrames = video.getVideoPlaybackQuality().totalVideoFrames;
            await until(() => video.currentTime > restingTime + 0.1 && video.getVideoPlaybackQuality().totalVideoFrames > restingFrames,
              'Native resting frames keep decoding after the loop boundary');
            assert(element<HTMLDialogElement>('.focus-break', view.host).matches(':modal'), 'The break remains locked through native rest loops');
          }
        }
        finally { video.removeEventListener('ended', ended); }
        const leaveVideo = element<HTMLVideoElement>(leaveVideoSelector, view.host);
        assert(leaveVideo.getAttribute('src') === chubbyCatLeaveVideo && leaveVideo.paused && leaveVideo.getAttribute('data-ready') === 'false',
          'The shipped exit asset is preloaded without covering the resting video');
        await confirmDismiss(view.host);
        await until(() => leaveVideo.getAttribute('data-ready') === 'true' && leaveVideo.currentTime > 0.1, 'Electron decodes and shows the native exit');
        assert(video.paused && video.hidden && leaveVideo.videoWidth === 2736 && leaveVideo.videoHeight === 1536,
          'The high-resolution exit replaces the stopped resting clip');
        assert(Math.abs(leaveVideo.duration - CAT_LEAVE_SECONDS) < 0.1, 'The separate exit retains its complete nine-second performance');
        context.clearRect(0, 0, canvas.width, canvas.height);
        context.drawImage(leaveVideo, 0, 0);
        const exitPixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
        let exitTransparent = 0, exitOpaque = 0;
        for (let index = 3; index < exitPixels.length; index += 4) {
          if (exitPixels[index] === 0) exitTransparent++;
          else if (exitPixels[index] === 255) exitOpaque++;
        }
        assert(exitTransparent > canvas.width * canvas.height / 4 && exitOpaque > 1000, 'Native exit frames preserve the transparent background and opaque cat detail');
        const exitTime = leaveVideo.currentTime, exitFrames = leaveVideo.getVideoPlaybackQuality().totalVideoFrames;
        await until(() => leaveVideo.currentTime > exitTime + 0.1 && leaveVideo.getVideoPlaybackQuality().totalVideoFrames > exitFrames,
          'Native exit media time and decoded frames both advance');
        assert(element<HTMLDialogElement>('.focus-break', view.host).matches(':modal'), 'The real exit keeps the app locked while it plays');
        leaveVideo.currentTime = leaveVideo.duration - 0.1;
        await until(() => view.host.querySelector('dialog') === null, 'The native exit ended event releases the app');
        assert(video.paused && leaveVideo.paused, 'Native completion leaves both detached videos paused');
      }
      finally { view.dispose(); }
    }],
  ]);
}
