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
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import SlackPage from './SlackPage';

describe('SlackPage (Slice 1 — pre-connect)', () => {
  // vitest.config.ts doesn't set globals:true, so RTL's auto-afterEach
  // never registers — call cleanup() ourselves so each test gets a
  // fresh DOM.
  afterEach(() => { cleanup(); });

  it('renders the connect prompt', () => {
    render(<SlackPage />);
    expect(
      screen.getByRole('heading', { name: /connect your slack workspace/i }),
    ).toBeDefined();
    expect(
      screen.getByRole('button', { name: /^connect slack workspace$/i }),
    ).toBeDefined();
    expect(screen.getByText(/not connected/i)).toBeDefined();
    expect(screen.getByText(/local-first/i)).toBeDefined();
  });

  // Locks in "Slice 1 deliberately doesn't wire OAuth or open content".
  // window.bridge is undefined under jsdom, so a handler that quietly
  // calls window.bridge.* would throw and fail this test.
  it('connect button and help links are no-ops in this slice', () => {
    render(<SlackPage />);
    fireEvent.click(screen.getByRole('button', { name: /^connect slack workspace$/i }));
    fireEvent.click(screen.getByRole('button', { name: /why these permissions/i }));
    fireEvent.click(screen.getByRole('button', { name: /what gets stored locally/i }));
    // No assertion needed — the test passes only when no handler threw.
    expect(true).toBe(true);
  });
});
