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

/** Eases a displayed number toward {@code target} instead of
 *  snapping to it. The task metrics arrive in 5s polls, so a turn's
 *  token usage lands as one big jump; tweening the displayed value
 *  makes it count up the way the streaming CLI does.
 *
 *  <p>The tween always runs forward from whatever is currently on
 *  screen, so a poll that arrives mid-animation re-targets smoothly
 *  rather than restarting from the old value. Respects
 *  `prefers-reduced-motion` — when set, the value snaps. The first
 *  value (and any decrease, e.g. a fresh task resetting to 0) snaps
 *  too, so we only ever animate an increase. */
export function useAnimatedNumber(target: number, durationMs: number = 650): number {
  const [display, setDisplay] = useState<number>(target);
  const displayRef = useRef<number>(target);
  const rafRef = useRef<number | null>(null);

  useEffect(() => {
    const from = displayRef.current;
    if (target === from) return;

    const reduce = typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    // Snap on reduce-motion and on any decrease — a count-down reads
    // as a glitch, and a reset to a smaller task shouldn't crawl.
    if (reduce || target < from) {
      displayRef.current = target;
      setDisplay(target);
      return;
    }

    const delta = target - from;
    let startTs: number | null = null;
    const step = (ts: number) => {
      if (startTs === null) startTs = ts;
      const t = Math.min(1, (ts - startTs) / durationMs);
      const eased = 1 - Math.pow(1 - t, 3); // easeOutCubic
      const value = Math.round(from + delta * eased);
      displayRef.current = value;
      setDisplay(value);
      if (t < 1) {
        rafRef.current = requestAnimationFrame(step);
      }
    };
    rafRef.current = requestAnimationFrame(step);

    return () => {
      if (rafRef.current !== null) {
        cancelAnimationFrame(rafRef.current);
        rafRef.current = null;
      }
    };
  }, [target, durationMs]);

  return display;
}
