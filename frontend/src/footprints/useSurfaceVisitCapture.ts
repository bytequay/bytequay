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
import { useEffect, useRef } from 'react';
import type { Nav } from '../workspace/workspaceRoutes';
import { navToSurfaceVisit, visitKey } from './surfaceVisit';

/**
 * Records a footprint whenever the app navigates to a tracked surface.
 * The single capture point — fires from the renderer's navigation layer
 * (App's nav state) so no individual call site can be missed.
 *
 * Fire-and-forget: the write is never awaited and failures are
 * swallowed, so footprint capture can't break or slow navigation.
 * Debounced by surface key via a ref, so re-renders that don't change
 * the surface (or land back on the same surface) record at most one
 * visit per distinct surface arrival.
 */
export function useSurfaceVisitCapture(nav: Nav): void {
  const lastKey = useRef<string | null>(null);

  useEffect(() => {
    const visit = navToSurfaceVisit(nav);
    if (visit === null) {
      // Leaving tracked surfaces clears the guard so returning to the
      // same surface later records a fresh visit.
      lastKey.current = null;
      return;
    }
    const key = visitKey(visit);
    if (key === lastKey.current) return;
    lastKey.current = key;
    // Nudge any mounted Recent list to refetch so the surface just visited
    // shows up now, not on its next slow poll (it stays mounted across
    // navigations, so a remount won't do it).
    void window.bridge.recordSurfaceVisit(visit)
      .then(() => window.dispatchEvent(new Event('footprint-recorded')))
      .catch(() => { /* fire-and-forget */ });
  }, [nav]);
}
