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
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Nav } from '../App';
import { useSurfaceVisitCapture } from './useSurfaceVisitCapture';

let recordSurfaceVisit: ReturnType<typeof vi.fn>;

beforeEach(() => {
  recordSurfaceVisit = vi.fn().mockResolvedValue(undefined);
  (window as { bridge?: unknown }).bridge = { recordSurfaceVisit };
});

afterEach(() => {
  cleanup();
  (window as { bridge?: unknown }).bridge = undefined;
});

function Harness({ nav }: { nav: Nav }): null {
  useSurfaceVisitCapture(nav);
  return null;
}

describe('useSurfaceVisitCapture', () => {
  it('records exactly one visit when navigating to a tracked surface', () => {
    render(<Harness nav={{ view: 'thread-detail', threadId: 't1', taskId: 'k1' }} />);
    expect(recordSurfaceVisit).toHaveBeenCalledTimes(1);
    expect(recordSurfaceVisit).toHaveBeenCalledWith({
      surfaceType: 'TASK', surfaceId: 't1/k1', title: 'Task', context: 't1',
    });
  });

  it('does not re-record when re-rendered on the same surface', () => {
    const nav: Nav = { view: 'my-prs' };
    const { rerender } = render(<Harness nav={nav} />);
    rerender(<Harness nav={{ view: 'my-prs' }} />);
    expect(recordSurfaceVisit).toHaveBeenCalledTimes(1);
  });

  it('records again after navigating to a different surface', () => {
    const { rerender } = render(<Harness nav={{ view: 'my-prs' }} />);
    rerender(<Harness nav={{ view: 'thread-detail', threadId: 't1' }} />);
    expect(recordSurfaceVisit).toHaveBeenCalledTimes(2);
  });

  it('records nothing for untracked surfaces', () => {
    render(<Harness nav={{ view: 'home' }} />);
    expect(recordSurfaceVisit).not.toHaveBeenCalled();
  });

  it('re-records the same surface after leaving and returning to it', () => {
    const { rerender } = render(<Harness nav={{ view: 'my-prs' }} />);
    rerender(<Harness nav={{ view: 'home' }} />);       // leave tracked surfaces
    rerender(<Harness nav={{ view: 'my-prs' }} />);     // return
    expect(recordSurfaceVisit).toHaveBeenCalledTimes(2);
  });
});
