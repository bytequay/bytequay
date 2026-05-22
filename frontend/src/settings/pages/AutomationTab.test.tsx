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
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Bridge } from '../../types';
import AutomationTab from './AutomationTab';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

afterEach(() => {
  cleanup();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

describe('AutomationTab', () => {
  it('loads the current setting and reflects it in the checkbox', async () => {
    installBridge({
      getScheduledReviewSettings: vi.fn(async () => ({ enabled: true })),
    });

    render(<AutomationTab />);

    await waitFor(() => {
      expect((screen.getByRole('checkbox') as HTMLInputElement).checked).toBe(true);
    });
    expect(screen.getByText('Enabled')).toBeTruthy();
  });

  it('flips the setting through the bridge when the checkbox is toggled', async () => {
    const setScheduledReviewSettings = vi.fn(async () => ({ enabled: true }));
    installBridge({
      getScheduledReviewSettings: vi.fn(async () => ({ enabled: false })),
      setScheduledReviewSettings,
    });

    render(<AutomationTab />);

    await waitFor(() => screen.getByText('Disabled'));
    const checkbox = screen.getByRole('checkbox') as HTMLInputElement;
    expect(checkbox.checked).toBe(false);

    await act(async () => { fireEvent.click(checkbox); });

    expect(setScheduledReviewSettings).toHaveBeenCalledWith(true);
    await waitFor(() => {
      expect((screen.getByRole('checkbox') as HTMLInputElement).checked).toBe(true);
    });
  });

  it('surfaces a bridge error inline and keeps the toggle live for retry', async () => {
    installBridge({
      getScheduledReviewSettings: vi.fn(async () => ({ enabled: false })),
      setScheduledReviewSettings: vi.fn(async () => {
        throw new Error('backend PUT /api/reviews/scheduled-settings returned 500');
      }),
    });

    render(<AutomationTab />);
    await waitFor(() => screen.getByRole('checkbox'));
    const checkbox = screen.getByRole('checkbox') as HTMLInputElement;

    await act(async () => { fireEvent.click(checkbox); });

    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toContain('500');
    });
    // Checkbox stayed at its previous value (false) so the user
    // doesn't see a phantom-on state when the write didn't land.
    expect(checkbox.checked).toBe(false);
  });
});

function installBridge(overrides: Partial<Bridge>) {
  (window as unknown as { bridge: Partial<Bridge> }).bridge = {
    getScheduledReviewSettings: vi.fn(async () => ({ enabled: false })),
    setScheduledReviewSettings: vi.fn(async () => ({ enabled: true })),
    getReviewPersona: vi.fn(async () => ({ persona: '' })),
    setReviewPersona: vi.fn(async (p: string) => ({ persona: p })),
    ...overrides,
  } as Partial<Bridge>;
}
