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
import { execFileSync } from 'node:child_process';
import { describe, expect, it, vi } from 'vitest';

vi.mock('electron', () => ({ app: { isPackaged: false }, dialog: { showErrorBox: vi.fn() } }));

import { javaMajorVersion } from './backendProcess';

describe('javaMajorVersion', () => {
  it('reads the major version from a working JDK', () => {
    const home = execFileSync('/usr/libexec/java_home', ['-v', '21+'], { encoding: 'utf8' }).trim();
    expect(javaMajorVersion(`${home}/bin/java`)).toBeGreaterThanOrEqual(21);
  });

  it('returns null when the binary is missing or not a JDK', () => {
    expect(javaMajorVersion('/nonexistent/bin/java')).toBeNull();
    expect(javaMajorVersion('/bin/echo')).toBeNull();
  });
});
