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

import { javaMajorVersion, loginShellPath, mergedPath } from './backendProcess';

describe('mergedPath', () => {
  it('puts the login shell PATH first and keeps the fallback dirs', () => {
    const merged = mergedPath('/opt/tools/bin:/usr/bin', '/usr/bin:/bin').split(':');

    expect(merged[0]).toBe('/opt/tools/bin');
    expect(merged).toContain('/bin');
    expect(merged).toContain('/opt/homebrew/bin');
    // Deduped — /usr/bin appears in both inputs.
    expect(merged.filter((entry) => entry === '/usr/bin')).toHaveLength(1);
    expect(merged).not.toContain('');
  });

  it('still yields a usable PATH when the shell probe fails', () => {
    const merged = mergedPath(null, undefined).split(':');

    expect(merged).toContain('/opt/homebrew/bin');
    expect(merged).toContain('/usr/local/bin');
  });
});

describe('loginShellPath', () => {
  it('returns the shell PATH without the rc-file noise around it', () => {
    const resolved = loginShellPath();

    // No SHELL (or a shell that refuses -ilc) is a legitimate null; anything
    // else must be a clean PATH, not the marker or startup chatter.
    if (resolved === null) return;
    expect(resolved).not.toContain('__BYTEQUAY_PATH__');
    expect(resolved).not.toContain('\n');
    expect(resolved.split(':')).toContain('/usr/bin');
  });
});

describe('javaMajorVersion', () => {
  // JAVA_HOME first so this runs on CI's Linux boxes, where the macOS-only
  // /usr/libexec/java_home doesn't exist; null on a machine with no JDK.
  const jdkHome = (): string | null => {
    if (process.env.JAVA_HOME) return process.env.JAVA_HOME;
    try {
      return execFileSync('/usr/libexec/java_home', ['-v', '17+'], { encoding: 'utf8' }).trim();
    } catch {
      return null;
    }
  };

  // The assertion is about parsing a real `java -version`, so it sits below
  // the app's own minimum — whatever JDK the machine happens to have does.
  it.skipIf(jdkHome() === null)('reads the major version from a working JDK', () => {
    expect(javaMajorVersion(`${jdkHome()}/bin/java`)).toBeGreaterThanOrEqual(17);
  });

  it('returns null when the binary is missing or not a JDK', () => {
    expect(javaMajorVersion('/nonexistent/bin/java')).toBeNull();
    expect(javaMajorVersion('/bin/echo')).toBeNull();
  });
});
