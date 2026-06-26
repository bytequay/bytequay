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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { PaneDiff } from './PaneDiff';
import type { DiffFileDto } from '../types';

afterEach(cleanup);

const FILE: DiffFileDto = {
  filename: 'backend/src/Composer.java',
  status: 'modified',
  additions: 2,
  deletions: 1,
  patch: '@@ -180,3 +180,4 @@\n context line\n-old filter\n+// half-open window\n+new filter\n',
};

describe('PaneDiff', () => {
  it('renders the file header with ± counts and parsed add/del lines', () => {
    const { container } = render(<PaneDiff files={[FILE]} />);
    expect(screen.getByText('Composer.java')).toBeTruthy();
    expect(screen.getByText('+2')).toBeTruthy();
    expect(screen.getByText('−1')).toBeTruthy();
    expect(container.querySelectorAll('.diff-line.add').length).toBe(2);
    expect(container.querySelectorAll('.diff-line.del').length).toBe(1);
    expect(screen.getByText('// half-open window')).toBeTruthy();
  });

  it('drops file-header lines and keeps a hunk marker', () => {
    const { container } = render(<PaneDiff files={[{
      ...FILE,
      patch: 'diff --git a/x b/x\nindex 1..2\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n',
    }]}
    />);
    // No row for the diff/index/+++/--- header lines; one hunk + one del + one add.
    expect(container.querySelectorAll('.diff-line.hunk').length).toBe(1);
    expect(container.querySelectorAll('.diff-line.add').length).toBe(1);
    expect(container.querySelectorAll('.diff-line.del').length).toBe(1);
  });
});
