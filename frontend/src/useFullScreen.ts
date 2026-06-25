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
import { useEffect, useState } from 'react';

/**
 * Tracks macOS native fullscreen. In windowed mode the OS draws the
 * inset traffic-light buttons at the window's top-left; in fullscreen it
 * hides them, leaving a gap. The rail uses this to swap its own
 * red/yellow/green dots in only when the native ones are gone. Seeds from
 * a synchronous pull, then follows the enter/leave-fullscreen events.
 */
export function useFullScreen(): boolean {
  const [fullScreen, setFullScreen] = useState(false);

  useEffect(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge === undefined) return;
    let cancelled = false;
    bridge.getFullScreenState?.()
      .then(v => { if (!cancelled) setFullScreen(v); })
      .catch(() => { /* default windowed */ });
    const unsubscribe = bridge.onFullScreenChange?.(({ isFullScreen }) => setFullScreen(isFullScreen));
    return () => { cancelled = true; unsubscribe?.(); };
  }, []);

  return fullScreen;
}
