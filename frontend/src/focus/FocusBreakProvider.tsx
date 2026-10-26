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
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { FocusBreakScreen } from './FocusBreakScreen';
import { FocusBreakBackgroundContext } from './background';
import type { FocusBreakBackground } from './contracts';
import { advanceClock, FOCUS_PREFERENCES_KEY, parseFocusPreferences, startClock, type FocusBreakPreferences, type FocusClock } from './model';

type FocusBreakState = {
  preferences: FocusBreakPreferences;
  phase: FocusClock['phase'];
  remainingSeconds: number;
  paused: boolean;
  error: string | null;
  updatePreferences: (next: FocusBreakPreferences) => void;
  togglePause: () => void;
  startBreak: () => void;
  dismiss: () => void;
};

const FocusBreakContext = createContext<FocusBreakState | null>(null);

function readPreferences(): FocusBreakPreferences {
  try { return parseFocusPreferences(JSON.parse(window.localStorage.getItem(FOCUS_PREFERENCES_KEY) ?? 'null')); }
  catch { return parseFocusPreferences(null); }
}

export function FocusBreakProvider({ children }: { children: ReactNode }) {
  const [preferences, setPreferences] = useState(readPreferences);
  const [clock, setClock] = useState(() => startClock('focus', preferences.workMinutes, Date.now()));
  const [error, setError] = useState<string | null>(null);
  const [companionVisible, setCompanionVisible] = useState(false);
  const [background, setBackground] = useState<FocusBreakBackground[]>([]);
  const [captureRevision, setCaptureRevision] = useState(0);
  const cancelCapture = useRef<(() => void) | undefined>(undefined);
  const phase = useRef(clock.phase);
  phase.current = clock.phase;
  const invalidateBackground = useCallback(() => {
    cancelCapture.current?.();
    setBackground([]);
    if (phase.current === 'entering') setCompanionVisible(false);
    setCaptureRevision(current => current + 1);
  }, []);
  const companionExited = useCallback(() => {
    setCompanionVisible(false);
    setBackground([]);
    setClock(current => current.phase === 'focus' ? startClock('focus', preferences.workMinutes, Date.now()) : current);
  }, [preferences.workMinutes]);
  const lockBreak = useCallback(() => setClock(current => current.phase === 'entering' ? { ...current, phase: 'break', updatedAt: Date.now() } : current), []);

  useEffect(() => {
    if (!preferences.enabled) { setCompanionVisible(false); setBackground([]); return; }
    if (clock.phase === 'focus' || companionVisible) return;
    let active = true;
    const finish = (pictures: FocusBreakBackground[]) => {
      if (!active) return;
      active = false;
      window.clearTimeout(timeout);
      setBackground(pictures);
      setCompanionVisible(true);
    };
    const timeout = window.setTimeout(() => finish([]), 2000);
    const cancel = () => { active = false; window.clearTimeout(timeout); };
    cancelCapture.current = cancel;
    // Capture before mounting the dialog, which makes native pages detach from their slots.
    void Promise.resolve().then(() => window.bridge.captureFocusBreakBackground()).then(async pictures => {
      const decoded = await Promise.allSettled(pictures.map(async picture => {
        const image = new Image();
        image.src = picture.image;
        await image.decode();
        return picture;
      }));
      finish(decoded.flatMap(result => result.status === 'fulfilled' ? [result.value] : []));
    }).catch(() => finish([]));
    return cancel;
  }, [preferences.enabled, clock.phase, companionVisible, captureRevision]);

  useEffect(() => {
    if (!preferences.enabled) return;
    let visible = document.visibilityState !== 'hidden';
    const tick = () => {
      // Finish the user's open confirmation or form before bringing in the companion.
      const canInterrupt = ![...document.querySelectorAll('dialog[open], [aria-modal="true"]:not([data-navigation-layer])')]
        .some(dialog => dialog.checkVisibility());
      setClock(current => companionVisible && current.phase === 'focus'
        ? current
        : advanceClock(current, preferences, Date.now(), visible, canInterrupt));
    };
    const visibility = () => {
      tick();
      visible = document.visibilityState !== 'hidden';
    };
    const timer = window.setInterval(tick, 1000);
    document.addEventListener('visibilitychange', visibility);
    return () => { window.clearInterval(timer); document.removeEventListener('visibilitychange', visibility); };
  }, [preferences, companionVisible]);

  const updatePreferences = (next: FocusBreakPreferences) => {
    const parsed = parseFocusPreferences(next);
    try {
      window.localStorage.setItem(FOCUS_PREFERENCES_KEY, JSON.stringify(parsed));
      setError(null);
    }
    catch { setError('Could not save focus breaks. These settings will last until you close ByteQuay.'); }
    setPreferences(parsed);
    if (parsed.enabled !== preferences.enabled || parsed.workMinutes !== preferences.workMinutes || parsed.breakMinutes !== preferences.breakMinutes) {
      setClock(startClock('focus', parsed.workMinutes, Date.now()));
    }
  };
  const dismiss = () => setClock(startClock('focus', preferences.workMinutes, Date.now()));
  const state: FocusBreakState = {
    preferences, phase: clock.phase, paused: clock.paused, remainingSeconds: Math.ceil(clock.remainingMs / 1000), error,
    updatePreferences, dismiss,
    togglePause: () => setClock(current => ({ ...current, paused: !current.paused, updatedAt: Date.now() })),
    startBreak: () => { if (preferences.enabled) setClock(startClock('entering', preferences.breakMinutes, Date.now())); },
  };
  return (
    <FocusBreakContext.Provider value={state}>
      <FocusBreakBackgroundContext.Provider value={invalidateBackground}>{children}</FocusBreakBackgroundContext.Provider>
      {preferences.enabled && companionVisible && <FocusBreakScreen companion={preferences.companion} background={background}
        remainingSeconds={clock.phase === 'focus' ? 0 : state.remainingSeconds}
        exiting={clock.phase === 'focus'} onLocked={lockBreak} onExited={companionExited} onDismiss={dismiss} />}
    </FocusBreakContext.Provider>
  );
}

export function useFocusBreaks(): FocusBreakState {
  const state = useContext(FocusBreakContext);
  if (state === null) throw new Error('Focus break controls require FocusBreakProvider');
  return state;
}
