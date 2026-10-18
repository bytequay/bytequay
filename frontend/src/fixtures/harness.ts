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

/**
 * Renderer-side helpers shared by the *Browser.tsx fixtures.
 *
 * These run inside the packaged page, so nothing here may touch node or electron — browserFixture.ts
 * is the node half and cannot hold them.
 */

/** Two frames, which is when React has committed and the browser has painted. */
export const settle = (): Promise<void> =>
  new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));

export function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

/** Settles until the condition holds: a load that chains requests and effects can outlast one settle on a slow machine. */
export async function until(condition: () => boolean, message: string): Promise<void> {
  for (let frame = 0; frame < 120 && !condition(); frame++) await settle();
  assert(condition(), message);
}

/** Sets a controlled textarea or input the way a user would, so React sees the change. */
export function changeInput(input: HTMLTextAreaElement | HTMLInputElement, value: string): void {
  Object.getOwnPropertyDescriptor(Object.getPrototypeOf(input), 'value')?.set?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}
