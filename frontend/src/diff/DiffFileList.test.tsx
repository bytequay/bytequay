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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FileDiffBody } from './DiffFileList';
import type { DiffFileDto } from '../types';

afterEach(cleanup);

const FILE: DiffFileDto = {
  filename: 'src/Foo.ts',
  status: 'modified',
  additions: 2,
  deletions: 2,
  patch: '@@ -1,1 +1,1 @@\n-old one\n+new one\n@@ -5,1 +5,1 @@\n-old five\n+new five\n',
};

describe('FileDiffBody expand controls', () => {
  it('renders up, all, and down controls for hidden middle lines', () => {
    const onExpandClick = vi.fn();
    render(<FileDiffBody file={FILE} onExpandClick={onExpandClick} />);

    fireEvent.click(screen.getByRole('button', { name: 'Expand 20 lines above' }));
    expect(onExpandClick).toHaveBeenLastCalledWith(expect.objectContaining({ index: 1 }), 'up');

    fireEvent.click(screen.getByRole('button', { name: '3 unmodified lines' }));
    expect(onExpandClick).toHaveBeenLastCalledWith(expect.objectContaining({ index: 1 }), 'all');

    fireEvent.click(screen.getByRole('button', { name: 'Expand 20 lines below' }));
    expect(onExpandClick).toHaveBeenLastCalledWith(expect.objectContaining({ index: 1 }), 'down');
  });
});
