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

export function errorMessage(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure);
}

/** Electron prefixes an invoke rejection with the channel and the error's class, which is not always a plain Error, such as TimeoutError. */
export function ipcFailure(failure: unknown): Error {
  return new Error(errorMessage(failure).replace(/^Error invoking remote method '[^']+': (?:\w*Error: )?/, ''));
}
