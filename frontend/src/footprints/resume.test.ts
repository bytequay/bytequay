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
import { describe, expect, it, vi } from 'vitest';
import type { FootprintStopDto, SurfaceType } from '../types';
import { resumeStop, type ResumeHandlers } from './resume';

function stop(surfaceType: SurfaceType, surfaceId: string): FootprintStopDto {
  return { surfaceType, surfaceId, title: null, context: null, latestVisitAt: '', visitCount: 1 };
}

function handlers(): ResumeHandlers & { [k in keyof ResumeHandlers]: ReturnType<typeof vi.fn> } {
  return {
    openPrKanban: vi.fn(),
    openPr: vi.fn(),
    openTask: vi.fn(),
    openThread: vi.fn(),
  };
}

describe('resumeStop', () => {
  it('routes the PR kanban surface to the kanban handler', () => {
    const h = handlers();
    resumeStop(stop('PR_KANBAN', 'my-prs'), h);
    expect(h.openPrKanban).toHaveBeenCalledTimes(1);
  });

  it('parses a PR surfaceId into owner/repo/number', () => {
    const h = handlers();
    resumeStop(stop('PR', 'trinodb/trino#5680'), h);
    expect(h.openPr).toHaveBeenCalledWith('trinodb', 'trino', 5680);
  });

  it('parses a task surfaceId into thread/task ids', () => {
    const h = handlers();
    resumeStop(stop('TASK', 't1/k1'), h);
    expect(h.openTask).toHaveBeenCalledWith('t1', 'k1');
  });

  it('routes a thread surfaceId straight through', () => {
    const h = handlers();
    resumeStop(stop('THREAD', 't1'), h);
    expect(h.openThread).toHaveBeenCalledWith('t1');
  });

  it('ignores a PR stop whose surfaceId does not parse', () => {
    const h = handlers();
    resumeStop(stop('PR', 'garbage'), h);
    expect(h.openPr).not.toHaveBeenCalled();
  });
});
