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
import { remoteErrorMessage } from './remoteError';

describe('remoteErrorMessage', () => {
  it('shows the backend message without Electron IPC noise', () => {
    expect(remoteErrorMessage(new Error(
      "Error invoking remote method 'prs:metadata:update': Error: Grant it access to this repository",
    ))).toBe('Grant it access to this repository');
    expect(remoteErrorMessage(new Error(
      "Error invoking remote method 'agent:turns:start': TimeoutError: The operation was aborted due to timeout",
    ))).toBe('The operation was aborted due to timeout');
  });

  it('preserves ordinary errors and non-error rejections', () => {
    expect(remoteErrorMessage(new Error('Request failed'))).toBe('Request failed');
    expect(remoteErrorMessage('Permission denied')).toBe('Permission denied');
    expect(remoteErrorMessage(null)).toBe('null');
    expect(remoteErrorMessage(new Error(''))).toBe('');
  });
});
