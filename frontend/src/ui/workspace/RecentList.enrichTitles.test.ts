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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { enrichTitles } from './RecentList';
import type { FootprintStopDto, WorkUnitTaskDto } from '../../types';

afterEach(() => {
  delete (window as unknown as { bridge?: unknown }).bridge;
});

function stop(over: Partial<FootprintStopDto> = {}): FootprintStopDto {
  return {
    surfaceType: 'TASK', surfaceId: 'thread-1/task-1', title: 'Task', context: 'thread-1',
    latestVisitAt: '2026-01-01T00:00:00Z', visitCount: 1,
    ...over,
  };
}

function task(over: Partial<WorkUnitTaskDto> = {}): WorkUnitTaskDto {
  return {
    id: 'task-1', name: 'Replace two lambdas', branchName: 'dev/replace-two-lambdas',
    ...over,
  } as unknown as WorkUnitTaskDto;
}

describe('enrichTitles', () => {
  it('swaps the placeholder task title for the real task name', async () => {
    window.bridge = {
      listTasksForThread: vi.fn(async () => [task()]),
    } as unknown as typeof window.bridge;

    const [enriched] = await enrichTitles([stop()]);

    expect(enriched.title).toBe('Replace two lambdas');
  });

  it('carries task repo and PR metadata for the approved second line', async () => {
    window.bridge = {
      listTasksForThread: vi.fn(async () => [task({
        linkedPrRef: 'chenjian2664/ByteQuay#42',
        prNumber: 42,
      })]),
    } as unknown as typeof window.bridge;

    const [enriched] = await enrichTitles([stop()]);

    expect(enriched.recentRepo).toBe('chenjian2664/ByteQuay');
    expect(enriched.recentNumber).toBe(42);
  });

  it('falls back to the humanised branch when the task has no name', async () => {
    window.bridge = {
      listTasksForThread: vi.fn(async () => [task({ name: null })]),
    } as unknown as typeof window.bridge;

    const [enriched] = await enrichTitles([stop()]);

    expect(enriched.title).toBe('replace two lambdas');
  });

  it('keeps the placeholder when the lookup fails', async () => {
    window.bridge = {
      listTasksForThread: vi.fn(async () => { throw new Error('offline'); }),
    } as unknown as typeof window.bridge;

    const [enriched] = await enrichTitles([stop()]);

    expect(enriched.title).toBe('Task');
  });

  it('keeps the placeholder when the task was deleted since the visit', async () => {
    window.bridge = {
      listTasksForThread: vi.fn(async (): Promise<WorkUnitTaskDto[]> => []),
    } as unknown as typeof window.bridge;

    const [enriched] = await enrichTitles([stop()]);

    expect(enriched.title).toBe('Task');
  });

  it('leaves PR/PR-kanban stops untouched', async () => {
    window.bridge = {} as unknown as typeof window.bridge;
    const prStop = stop({ surfaceType: 'PR', surfaceId: 'o/r#1', title: 'o/r #1' });

    const [enriched] = await enrichTitles([prStop]);

    expect(enriched).toEqual(prStop);
  });
});
