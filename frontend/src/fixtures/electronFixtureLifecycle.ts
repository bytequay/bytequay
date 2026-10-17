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

// Each fixture owns its lifetime even if Vitest terminates its worker before cleanup runs.
export function electronFixtureLifecycle(timeoutMs: number): string {
  return `
    // Avoid Dock activation transitions while concurrent fixtures start and exit on macOS.
    if (process.platform === 'darwin') app.setActivationPolicy('accessory');
    process.stdin.on('end', () => app.exit(1));
    process.stdin.resume();
    setTimeout(() => {
      console.error('Electron fixture timed out');
      app.exit(1);
    }, ${timeoutMs}).unref();
  `;
}
