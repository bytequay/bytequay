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

/**
 * Smoothly reveal {@code target} a few characters per animation frame so
 * that text which actually arrives in bursts (the agent CLI flushes its
 * stream-json in chunks, not token-by-token) reads as live typing —
 * Claude-Code-style — instead of popping in whole.
 *
 * <p>The reveal eases: it advances a fraction of the remaining gap each
 * frame (with a small floor), so it sprints when far behind a big chunk
 * and settles gently as it catches up. When {@code target} shrinks or
 * clears (the turn hands off to the durable message), the shown text
 * snaps back to match so nothing lingers. Honors reduced-motion by
 * showing the full text immediately.
 */
export function useTypewriter(target: string): string {
  const [shown, setShown] = useState(target);
  const shownLenRef = useRef(target.length);
  const targetRef = useRef(target);
  targetRef.current = target;

  // Target cleared or replaced by something shorter (flush / new turn):
  // collapse to it so we don't keep showing stale, longer text.
  if (target.length < shownLenRef.current) {
    shownLenRef.current = target.length;
  }

  useEffect(() => {
    const reduce = typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    let raf = 0;
    const tick = () => {
      const full = targetRef.current.length;
      let cur = shownLenRef.current;
      if (cur !== full) {
        if (reduce || full < cur) {
          cur = full;
        }
        else {
          // Reveal ~18% of the remaining gap each frame, min 2 chars, so
          // a big burst still finishes in well under a second.
          cur = Math.min(full, cur + Math.max(2, Math.ceil((full - cur) * 0.18)));
        }
        shownLenRef.current = cur;
        setShown(targetRef.current.slice(0, cur));
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);

  return shown;
}
