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
import { useCallback, useEffect, useRef } from 'react';
import type { RecentItemType } from '../types';

export type RecentTarget = {
  type: RecentItemType;
  repo: string;
  number: number;
  workspaceId?: string | null;
};

/**
 * The threshold the backend enforces. Mirrored here only to know when the
 * first report is worth sending; the backend still decides what counts.
 */
const DWELL_SECONDS = 20;

function report(target: RecentTarget, signal: 'DWELL' | 'DIFF_OPENED', dwellSeconds: number) {
  void window.bridge.recordRecentTouch({
    type: target.type,
    repo: target.repo,
    number: target.number,
    workspaceId: target.workspaceId ?? null,
    signal,
    dwellSeconds,
  }).catch(() => {
    // Recent is a convenience. A failed report is never worth a visible error.
  });
}

/**
 * Records that a detail was actually read. Only the renderer can see how long
 * something stayed open, so the dwell is measured here: once at the threshold
 * so a row survives quitting the app, and again on the way out so the minute
 * count on the badge is the real one.
 */
export function useRecentTouch(target: RecentTarget | null) {
  const key = target ? `${target.type}:${target.repo}#${target.number}` : null;
  const current = useRef(target);
  current.current = target;

  useEffect(() => {
    if (!key) return;
    const openedAt = Date.now();
    let reached = false;
    const timer = window.setTimeout(() => {
      reached = true;
      if (current.current) report(current.current, 'DWELL', DWELL_SECONDS);
    }, DWELL_SECONDS * 1000);
    return () => {
      window.clearTimeout(timer);
      const seconds = Math.round((Date.now() - openedAt) / 1000);
      if (reached && current.current) report(current.current, 'DWELL', seconds);
    };
  }, [key]);

  /** Opening a diff is intent, so it lands immediately however briefly it stays open. */
  return useCallback(() => {
    if (current.current) report(current.current, 'DIFF_OPENED', 0);
  }, []);
}
