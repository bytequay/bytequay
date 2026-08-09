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
import type { Nav } from '../workspace/workspaceRoutes';
import type { SurfaceVisitInput } from '../types';

/**
 * Maps a navigation state to the footprint visit it should record, or
 * null when the surface isn't one footprints tracks (thread trunks,
 * home, settings, repo browsing, etc.).
 *
 * `surfaceId` is the renderer's navigable key — the resume handler
 * ({@link surfaceVisitToNav}) parses it back into a Nav. Title/context
 * are a best-effort label from what the nav layer knows; the task and
 * thread cases only have ids here, so their titles are a generic
 * placeholder ("Task"/"Thread") at capture time. The sidebar's Recent
 * list (ui/workspace/RecentList.tsx, `enrichTitles`) swaps in the real
 * name at read time instead of enriching it here, since a stored
 * footprint's title would otherwise go stale the moment a task is
 * renamed or a thread's title changes.
 */
export function navToSurfaceVisit(nav: Nav): SurfaceVisitInput | null {
  switch (nav.view) {
    case 'repo':
      // Only an individual PR is a tracked surface; bare repo browsing
      // (pulls/issues lists) is not.
      if (nav.prNumber === undefined) return null;
      return {
        surfaceType: 'PR',
        surfaceId: `${nav.owner}/${nav.repo}#${nav.prNumber}`,
        title: `${nav.owner}/${nav.repo} #${nav.prNumber}`,
        context: `${nav.owner}/${nav.repo}`,
      };
    case 'task-brain':
      return {
        surfaceType: 'TASK',
        surfaceId: `${nav.threadId}/${nav.taskId}`,
        title: 'Task',
        context: nav.threadId,
      };
    case 'thread-detail':
      if (nav.taskId !== undefined) {
        return {
          surfaceType: 'TASK',
          surfaceId: `${nav.threadId}/${nav.taskId}`,
          title: 'Task',
          context: nav.threadId,
        };
      }
      return null;
    default:
      return null;
  }
}

/** Stable identity for a visit — the surface it lands on. Used to
 *  debounce duplicate immediate re-renders so one surface open records
 *  one visit. */
export function visitKey(visit: SurfaceVisitInput): string {
  return `${visit.surfaceType}:${visit.surfaceId}`;
}
