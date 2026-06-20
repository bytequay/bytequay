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
import type { FootprintStopDto } from '../types';

/**
 * The navigation a footprint stop can resume to. The caller supplies the
 * app's existing handlers; {@link resumeStop} picks the right one and
 * parses the stop's surfaceId back into its arguments.
 *
 * v1 resumes by navigating to the surface only.
 * // decision pending: restore exact scroll / position / draft.
 */
export type ResumeHandlers = {
  openPrKanban: () => void;
  openPr: (owner: string, repo: string, prNumber: number) => void;
  openTask: (threadId: string, taskId: string) => void;
  openThread: (threadId: string) => void;
};

const PR_KEY = /^([^/]+)\/([^/#]+)#(\d+)$/;

/** Routes a stop to the matching resume handler. Silently ignores a stop
 *  whose surfaceId doesn't parse (stale/corrupt row) rather than throwing
 *  in a click handler. */
export function resumeStop(stop: FootprintStopDto, handlers: ResumeHandlers): void {
  switch (stop.surfaceType) {
    case 'PR_KANBAN':
      handlers.openPrKanban();
      return;
    case 'PR': {
      const m = PR_KEY.exec(stop.surfaceId);
      if (m !== null) handlers.openPr(m[1], m[2], Number(m[3]));
      return;
    }
    case 'TASK': {
      const slash = stop.surfaceId.indexOf('/');
      if (slash > 0 && slash < stop.surfaceId.length - 1) {
        handlers.openTask(stop.surfaceId.slice(0, slash), stop.surfaceId.slice(slash + 1));
      }
      return;
    }
    case 'THREAD':
      if (stop.surfaceId.length > 0) handlers.openThread(stop.surfaceId);
      return;
  }
}
