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
import { describePermission } from './PermissionCard';

describe('describePermission', () => {
  it('renders a shell command as a plain action + the command body', () => {
    const d = describePermission(
      'mcp__bytequay__run_shell',
      JSON.stringify({ command: 'perl -0777 -pi -e s/a/b/g lib/Foo.java' }));
    expect(d.action).toBe('Run shell command');
    expect(d.target).toBeNull();
    expect(d.body).toBe('perl -0777 -pi -e s/a/b/g lib/Foo.java');
  });

  it('renders a file edit with the basename as the target', () => {
    const d = describePermission(
      'Edit',
      JSON.stringify({ file_path: '/repo/lib/base/security/FileBasedAccessControl.java' }));
    expect(d.action).toBe('Edit file');
    expect(d.target).toBe('FileBasedAccessControl.java');
    expect(d.body).toBe('/repo/lib/base/security/FileBasedAccessControl.java');
  });

  it('recovers the command from truncated (unparseable) JSON', () => {
    // Backend caps the summary at 240 chars, so a long command can lose
    // its closing quote/brace. The lenient extractor still lifts it.
    const truncated = '{"command":"perl -0777 -pi -e s/x/y/g lib/trino/Foo.java && mvn -q';
    const d = describePermission('Bash', truncated);
    expect(d.action).toBe('Run shell command');
    expect(d.body).toBe('perl -0777 -pi -e s/x/y/g lib/trino/Foo.java && mvn -q');
  });

  it('humanizes an unknown mcp tool and has no body for empty input', () => {
    const d = describePermission('mcp__bytequay__list_prs', '{}');
    expect(d.action).toBe('list prs');
    expect(d.body).toBeNull();
  });

  it('unescapes newlines in a recovered command', () => {
    const d = describePermission('Bash', '{"command":"echo one\\necho two');
    expect(d.body).toBe('echo one\necho two');
  });
});
