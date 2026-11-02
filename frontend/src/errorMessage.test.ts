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
import { errorMessage, ipcFailure } from './errorMessage';

describe('ipcFailure', () => {
  it('keeps the main process reason without Electron IPC noise', () => {
    expect(ipcFailure(new Error(
      "Error invoking remote method 'prs:metadata:update': Error: Grant it access to this repository",
    )).message).toBe('Grant it access to this repository');
    expect(ipcFailure(new Error(
      "Error invoking remote method 'agent:turns:start': TimeoutError: The operation was aborted due to timeout",
    )).message).toBe('The operation was aborted due to timeout');
    expect(ipcFailure(new Error('Request failed')).message).toBe('Request failed');
  });
});

describe('errorMessage', () => {
  it('reads errors and non-error rejections', () => {
    expect(errorMessage(new Error('Request failed'))).toBe('Request failed');
    expect(errorMessage('Permission denied')).toBe('Permission denied');
    expect(errorMessage(null)).toBe('null');
    expect(errorMessage(new Error(''))).toBe('');
  });
});
