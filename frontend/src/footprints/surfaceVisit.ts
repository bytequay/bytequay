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
import type { Nav } from '../App';
import type { SurfaceVisitInput } from '../types';

/**
 * Maps a navigation state to the footprint visit it should record, or
 * null when the surface isn't one footprints tracks (home, settings,
 * repo browsing, etc.).
 *
 * `surfaceId` is the renderer's navigable key — the resume handler
 * ({@link surfaceVisitToNav}) parses it back into a Nav. Title/context
 * are a best-effort label from what the nav layer knows; the task and
 * thread cases only have ids here, so their titles are generic for v1.
 * // decision pending: enrich task/thread titles from the detail pages.
 */
export function navToSurfaceVisit(nav: Nav): SurfaceVisitInput | null {
  switch (nav.view) {
    case 'my-prs':
      return { surfaceType: 'PR_KANBAN', surfaceId: 'my-prs', title: 'PR kanban', context: null };
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
      // A task within the thread vs. the thread trunk itself.
      if (nav.taskId !== undefined) {
        return {
          surfaceType: 'TASK',
          surfaceId: `${nav.threadId}/${nav.taskId}`,
          title: 'Task',
          context: nav.threadId,
        };
      }
      return { surfaceType: 'THREAD', surfaceId: nav.threadId, title: 'Thread', context: null };
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
