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

/**
 * Two frames, which is when React has committed and the browser has painted, then one message turn: a busy renderer
 * runs late frames back to back ahead of the message-channel tasks that carry React's scheduled renders, and a
 * message posted now runs after those. Fixtures may stub setTimeout, so this does not use it.
 */
export const settle = async (): Promise<void> => {
  await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
  await new Promise<void>(resolve => {
    const channel = new MessageChannel();
    channel.port1.onmessage = () => { channel.port1.close(); resolve(); };
    channel.port2.postMessage(null);
  });
};

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

export function element<T extends Element = HTMLElement>(selector: string, root: ParentNode = document, message = `Missing ${selector}`): T {
  const found = root.querySelector<T>(selector);
  assert(found !== null, message);
  return found;
}

export function button(label: string, root: ParentNode = document, message = `Missing button: ${label}`): HTMLButtonElement {
  const found = [...root.querySelectorAll<HTMLButtonElement>('button')]
    .find(candidate => candidate.textContent?.trim() === label || candidate.getAttribute('aria-label') === label);
  assert(found !== undefined, message);
  return found;
}

export function textButton(label: string, root: ParentNode = document, message = `Missing ${label} button`): HTMLButtonElement {
  const found = [...root.querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent === label);
  assert(found !== undefined, message);
  return found;
}

/** Runs in order and records each failure without preventing the next scenario from exercising its own setup. */
export async function runScenarios(
  scenarios: readonly (readonly [string, () => unknown])[],
  afterEach?: () => void | Promise<void>,
): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  for (const [name, run] of scenarios) {
    try { await run(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    const cleanup = afterEach?.();
    if (cleanup) await cleanup;
  }
  return results;
}
