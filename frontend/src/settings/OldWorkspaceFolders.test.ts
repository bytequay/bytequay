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

import { describe, expect, it } from 'vitest';
import { folderDetails, folderSize } from './OldWorkspaceFolders';

describe('old workspace folders', () => {
  it('sizes folders in decimal units, with one decimal below ten', () => {
    expect(folderSize(512)).toBe('512 bytes');
    expect(folderSize(1_500)).toBe('1.5 KB');
    expect(folderSize(380_000_000)).toBe('380 MB');
    expect(folderSize(41_200_000_000)).toBe('41 GB');
    expect(folderSize(2_700_000_000_000_000)).toBe('2700 TB');
  });

  it('describes a folder by size, checkouts and last change', () => {
    const folder = { id: 'old', path: '/root/old', repository: 'owner/repository', sizeBytes: 2_700_000_000, modifiedAt: '2026-10-16T12:00:00Z', checkouts: 1 };
    expect(folderDetails(folder)).toMatch(/^2\.7 GB · 1 checkout · last changed /);
    expect(folderDetails({ ...folder, checkouts: 35 })).toContain('35 checkouts');
  });
});
