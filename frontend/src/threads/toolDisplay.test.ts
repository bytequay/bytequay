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
import { isShellTool, shellCommand } from './toolDisplay';

describe('isShellTool', () => {
  it('recognises the shell tool names across agents', () => {
    expect(isShellTool('Bash')).toBe(true);
    expect(isShellTool('command_execution')).toBe(true);
    expect(isShellTool('shell')).toBe(true);
    expect(isShellTool('run_shell')).toBe(true);
  });

  it('is false for non-shell tools', () => {
    expect(isShellTool('Read')).toBe(false);
    expect(isShellTool('Grep')).toBe(false);
    expect(isShellTool('tool')).toBe(false);
  });
});

describe('shellCommand', () => {
  it('reads a plain command string', () => {
    expect(shellCommand({ command: '/bin/zsh -lc "git diff"' })).toBe('/bin/zsh -lc "git diff"');
  });

  it('joins an argv-array command', () => {
    expect(shellCommand({ command: ['bash', '-lc', 'git diff'] })).toBe('bash -lc git diff');
  });

  it('falls back to the cmd alias', () => {
    expect(shellCommand({ cmd: 'ls -la' })).toBe('ls -la');
  });

  it('returns empty string when there is no command or input is not an object', () => {
    expect(shellCommand({})).toBe('');
    expect(shellCommand(null)).toBe('');
    expect(shellCommand('git diff')).toBe('');
  });
});
