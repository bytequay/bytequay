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
import { useEffect, useRef, useState, useSyncExternalStore, type KeyboardEvent, type RefObject } from 'react';
import { settingsRoute } from '../settings/routes';
import type { DictationEvent, DictationRefusal, VoiceSettings } from './contracts';

/** How long right ⌥ must be held alone before dictation starts; a shorter press stays an ordinary ⌥. */
export const HOLD_MS = 250;

export type DictationView =
  | { kind: 'idle' }
  /** `finished` is in the written language; `originals` holds the spoken words it was translated from. */
  | { kind: 'listening'; id: string; hold: boolean; finished: string; originals: string; partial: string; since: number }
  | { kind: 'finishing'; id: string; finished: string; originals: string; partial: string }
  | { kind: 'failed'; id: string; seconds: number }
  | { kind: 'notice'; text: string; settings: boolean };

const idle: DictationView = { kind: 'idle' };

/** Applies one helper event to a composer's view. `done` carries text to insert, which the caller does. */
export function nextView(view: DictationView, event: DictationEvent): DictationView {
  if (!('id' in view) || view.id !== event.id) return view;
  switch (event.type) {
    case 'listening':
      return view;
    case 'partial':
      return view.kind === 'listening' || view.kind === 'finishing' ? { ...view, partial: event.text } : view;
    case 'final':
      return view.kind === 'listening' || view.kind === 'finishing'
        ? { ...view, finished: view.finished + event.text, originals: view.originals + (event.original ?? ''), partial: '' }
        : view;
    case 'finishing':
      return view.kind === 'listening' ? { kind: 'finishing', id: view.id, finished: view.finished, originals: view.originals, partial: view.partial } : view;
    case 'done':
    case 'cancelled':
      return idle;
    case 'failed':
      return event.seconds > 0 ? { kind: 'failed', id: event.id, seconds: event.seconds } : { kind: 'notice', text: startFailure(event.code), settings: true };
  }
}

/** Why a dictation stopped before any audio, in the user's words; each points at the Voice setting that fixes it. */
export function startFailure(code: string): string {
  switch (code) {
    case 'microphone-denied': return 'ByteQuay can’t use the microphone.';
    case 'no-microphone': return 'No microphone found.';
    case 'model-missing': return 'The speech model isn’t downloaded yet.';
    case 'translation-missing': return 'The translation isn’t downloaded yet.';
    default: return 'Dictation couldn’t start.';
  }
}

/** A language's name in the user's own language, from a helper id such as `zh_CN` or `en`. */
export function languageName(id: string): string {
  try {
    return new Intl.DisplayNames([navigator.language], { type: 'language' }).of(id.replace('_', '-')) ?? id;
  }
  catch {
    return id;
  }
}

export function refusalNotice(refused: DictationRefusal): DictationView {
  return refused === 'busy'
    ? { kind: 'notice', text: 'Dictation is running in another composer.', settings: false }
    : { kind: 'notice', text: 'Dictation isn’t available right now.', settings: true };
}

/** The words to insert at the caret, with one space on each side that touches other text. */
export function spaced(before: string, text: string, after = ''): string {
  const words = text.trim();
  return `${before === '' || /\s$/.test(before) ? '' : ' '}${words}${after === '' || /^\s/.test(after) ? '' : ' '}`;
}

// The Voice setting, read once and kept current for every composer in this window.
const OFF: VoiceSettings = { enabled: false, spoken: null, written: null };
let voice = OFF;
let watching = false;
const voiceListeners = new Set<() => void>();

function subscribeVoice(listener: () => void): () => void {
  // Fixtures and windows without the voice bridge simply have no mic.
  if (!watching && typeof window.bridge?.getVoiceSettings === 'function') {
    watching = true;
    const apply = (settings: VoiceSettings) => {
      voice = settings;
      for (const notify of voiceListeners) notify();
    };
    void window.bridge.getVoiceSettings().then(apply).catch(() => {});
    window.bridge.onVoiceChanged(apply);
  }
  voiceListeners.add(listener);
  return () => { voiceListeners.delete(listener); };
}

// The composer dictating now; Electron main allows one dictation at a time.
let owner: object | null = null;
const ownerListeners = new Set<() => void>();

function setOwner(next: object | null): void {
  owner = next;
  for (const notify of ownerListeners) notify();
}

function subscribeOwner(listener: () => void): () => void {
  ownerListeners.add(listener);
  return () => { ownerListeners.delete(listener); };
}

/** Dictation for one composer: the mic, hold right ⌥, the strip's view, and insertion of the finished text. */
export function useDictation(field: RefObject<HTMLTextAreaElement | null>, disabled: boolean) {
  const [me] = useState(() => ({}));
  // Server-rendered markup has no voice bridge, so no mic.
  const settings = useSyncExternalStore(subscribeVoice, () => voice, () => OFF);
  const on = settings.enabled;
  const elsewhere = useSyncExternalStore(subscribeOwner, () => owner !== null && owner !== me, () => false);
  const [view, setView] = useState<DictationView>(idle);
  const [now, setNow] = useState(() => Date.now());
  const hold = useRef<{ timer: number | undefined; held: boolean }>({ timer: undefined, held: false });
  const live = useRef<string | null>(null);
  const id = 'id' in view ? view.id : null;
  const listening = view.kind === 'listening' ? view.id : null;

  const release = () => {
    if (owner === me) setOwner(null);
  };
  const begin = async (byHold: boolean) => {
    if (owner !== null) return;
    setOwner(me);
    try {
      const result = await window.bridge.startDictation();
      if ('refused' in result) {
        release();
        setView(refusalNotice(result.refused));
        return;
      }
      const since = Date.now();
      setNow(since);
      setView({ kind: 'listening', id: result.id, hold: byHold, finished: '', originals: '', partial: '', since });
      // Right ⌥ came up while the helper was starting.
      if (byHold && !hold.current.held) void window.bridge.stopDictation(result.id);
    }
    catch {
      release();
      setView(refusalNotice('unavailable'));
    }
  };

  useEffect(() => {
    live.current = view.kind === 'listening' || view.kind === 'finishing' ? view.id : null;
  });


  useEffect(() => {
    if (id === null) return;
    return window.bridge.onDictationEvent(event => {
      if (event.id !== id) return;
      if ((event.type === 'done' || event.type === 'cancelled' || event.type === 'failed') && owner === me) setOwner(null);
      if (event.type === 'done') {
        const input = field.current;
        if (input === null || event.text.trim() === '') {
          setView({ kind: 'notice', text: 'No speech heard', settings: false });
          return;
        }
        input.focus();
        // Typed through the editing stack, so the host's onChange saves it and ⌘Z removes it.
        document.execCommand('insertText', false, spaced(input.value.slice(0, input.selectionStart), event.text, input.value.slice(input.selectionEnd)));
      }
      setView(current => nextView(current, event));
    });
  }, [id, field, me]);

  useEffect(() => {
    if (listening === null) return;
    const tick = window.setInterval(() => setNow(Date.now()), 1000);
    const blur = () => { void window.bridge.stopDictation(listening); };
    window.addEventListener('blur', blur);
    return () => {
      window.clearInterval(tick);
      window.removeEventListener('blur', blur);
    };
  }, [listening]);

  useEffect(() => {
    if (view.kind !== 'notice') return;
    const timer = window.setTimeout(() => setView(current => (current === view ? idle : current)), view.settings ? 6000 : 2000);
    return () => window.clearTimeout(timer);
  }, [view]);

  useEffect(() => () => {
    window.clearTimeout(hold.current.timer);
    // The draft goes with the composer, so its words have nowhere to land.
    if (live.current !== null) void window.bridge.cancelDictation(live.current);
    if (owner === me) setOwner(null);
  }, [me]);

  const ready = view.kind === 'idle' || view.kind === 'notice';
  const stop = () => {
    if (view.kind === 'listening') void window.bridge.stopDictation(view.id);
  };
  const cancel = () => {
    if (view.kind === 'listening' || view.kind === 'finishing') void window.bridge.cancelDictation(view.id);
  };
  const abandonHold = () => {
    window.clearTimeout(hold.current.timer);
    hold.current.timer = undefined;
  };

  return {
    available: on,
    /** The written language's name while dictated words are translated before they reach the draft. */
    into: settings.written === null ? null : languageName(settings.written),
    view,
    elapsed: view.kind === 'listening' ? Math.max(0, Math.floor((now - view.since) / 1000)) : 0,
    micDisabled: disabled || elsewhere || view.kind === 'finishing',
    toggle: () => {
      const input = field.current;
      if (input !== null) {
        // A text box never clicked into keeps its caret at the start; dictated words then follow the draft instead.
        if (document.activeElement !== input && input.selectionStart === 0 && input.selectionEnd === 0) input.setSelectionRange(input.value.length, input.value.length);
        // Esc and the finished words both work through the text box.
        input.focus();
      }
      if (view.kind === 'listening') stop();
      else if (ready) void begin(false);
    },
    retry: async () => {
      if (view.kind !== 'failed' || owner !== null) return;
      setOwner(me);
      const result = await window.bridge.retryDictation(view.id).catch((): { refused: DictationRefusal } => ({ refused: 'unavailable' }));
      if ('refused' in result) {
        release();
        setView(refusalNotice(result.refused));
      }
      else setView({ kind: 'finishing', id: result.id, finished: '', originals: '', partial: '' });
    },
    discard: () => {
      if (view.kind !== 'failed') return;
      void window.bridge.discardDictation(view.id);
      setView(idle);
    },
    openSettings: () => { window.location.hash = settingsRoute('features'); },
    keyDown: (event: KeyboardEvent<HTMLTextAreaElement>) => {
      if (!on) return;
      if (event.key === 'Escape' && (view.kind === 'listening' || view.kind === 'finishing')) {
        // Esc ends the dictation only; it must not also close the panel around the composer.
        event.preventDefault();
        event.stopPropagation();
        cancel();
        return;
      }
      if (event.code === 'AltRight' && !event.repeat && !event.metaKey && !event.ctrlKey && !event.shiftKey) {
        if (ready && !disabled && !elsewhere) {
          hold.current.held = true;
          hold.current.timer = window.setTimeout(() => {
            hold.current.timer = undefined;
            void begin(true);
          }, HOLD_MS);
        }
        return;
      }
      // Another key while ⌥ is down is an ordinary ⌥ shortcut or character.
      abandonHold();
    },
    keyUp: (event: KeyboardEvent<HTMLTextAreaElement>) => {
      if (event.code !== 'AltRight') return;
      hold.current.held = false;
      if (hold.current.timer !== undefined) abandonHold();
      else if (view.kind === 'listening' && view.hold) stop();
    },
  };
}
