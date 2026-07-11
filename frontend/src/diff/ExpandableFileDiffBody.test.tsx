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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DiffFileDto } from '../types';
import { ExpandableFileDiffBody } from './ExpandableFileDiffBody';

afterEach(cleanup);

const FILE: DiffFileDto = {
  filename: 'src/foo.ts',
  status: 'modified',
  additions: 2,
  deletions: 2,
  patch: '@@ -1,1 +1,1 @@\n-old one\n+new one\n@@ -5,1 +5,1 @@\n-old five\n+new five\n',
};

describe('ExpandableFileDiffBody', () => {
  it('calls the injected blob fetcher and renders loaded unchanged lines', async () => {
    const fetchFileBlob = vi.fn().mockResolvedValue({
      lines: ['new one', 'same two', 'same three', 'same four', 'new five'],
    });
    render(<ExpandableFileDiffBody file={FILE} fetchFileBlob={fetchFileBlob} />);

    fireEvent.click(screen.getByRole('button', { name: '3 unmodified lines' }));

    await waitFor(() => expect(fetchFileBlob).toHaveBeenCalledWith('src/foo.ts'));
    expect(await screen.findByText('same two')).toBeTruthy();
    expect(screen.getByText('same three')).toBeTruthy();
    expect(screen.getByText('same four')).toBeTruthy();
  });

  it('surfaces an expansion error from the injected fetcher', async () => {
    const fetchFileBlob = vi.fn().mockRejectedValue(new Error('Blob unavailable'));
    render(<ExpandableFileDiffBody file={FILE} fetchFileBlob={fetchFileBlob} />);

    fireEvent.click(screen.getByRole('button', { name: '3 unmodified lines' }));

    expect((await screen.findByRole('alert')).textContent).toContain('Blob unavailable');
  });
});
