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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Bridge, ReviewSkillDto } from '../../types';
import SkillsPage from './SkillsPage';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

afterEach(() => {
  cleanup();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

beforeEach(() => {
  window.confirm = vi.fn(() => true) as unknown as typeof window.confirm;
});

describe('SkillsPage', () => {
  it('renders the trigger line and the not-always-on banner on each row', async () => {
    installBridge([
      mkSkill({ id: 1, repo: '*', description: 'reviewing a PR touching auth' }),
    ]);

    render(<SkillsPage />);

    await waitFor(() => {
      expect(screen.getByText(/loads when/i)).toBeTruthy();
    });
    expect(screen.getByText(/reviewing a PR touching auth/)).toBeTruthy();
    // The page header carries the "model-triggered, not always-on" framing.
    expect(screen.getByText(/model-triggered/i)).toBeTruthy();
  });

  it('toggles the enable flag through the bridge endpoint', async () => {
    const setSkillEnabled = vi.fn(async (id: number, enabled: boolean) => mkSkill({ id, enabled }));
    installBridge([mkSkill({ id: 7, enabled: true })], { setSkillEnabled });

    render(<SkillsPage />);
    await waitFor(() => expect(screen.getByText('Always-on style skill')).toBeTruthy());

    const toggle = screen.getByTitle(/Disable skill/i);
    fireEvent.click(toggle);

    await waitFor(() => expect(setSkillEnabled).toHaveBeenCalledWith(7, false));
  });

  it('drafts a skill via the AI button and lands the proposal in the editor', async () => {
    const draftSkill = vi.fn(async () => ({
      name: 'Auth review checklist',
      description: 'reviewing a PR that touches authentication',
      body: 'Check token expiry handling.',
    }));
    installBridge([], { draftSkill });

    render(<SkillsPage />);
    await waitFor(() => expect(screen.getByText('+ New skill')).toBeTruthy());

    fireEvent.click(screen.getByText('+ New skill'));
    fireEvent.click(screen.getByText(/Draft with AI/i));
    const prompt = screen.getByPlaceholderText(/Describe the skill/i);
    fireEvent.change(prompt, { target: { value: 'remind me about auth review' } });
    fireEvent.click(screen.getByText(/Draft it/i));

    await waitFor(() => expect(draftSkill).toHaveBeenCalledTimes(1));
    // After the draft lands the modal flips to manual mode and the
    // proposal fills the form.
    await waitFor(() => {
      expect(screen.getByDisplayValue('Auth review checklist')).toBeTruthy();
    });
  });
});

function installBridge(
  initial: ReviewSkillDto[],
  overrides?: Partial<Bridge>,
): void {
  let snapshot = [...initial];
  const listReviewSkills = vi.fn(async () => snapshot);
  const listAiProviders = vi.fn(async () => []);
  const setSkillEnabled = overrides?.setSkillEnabled ?? vi.fn(async (id: number, enabled: boolean) => {
    snapshot = snapshot.map(s => s.id === id ? { ...s, enabled } : s);
    return snapshot.find(s => s.id === id)!;
  });
  const draftSkill = overrides?.draftSkill ?? vi.fn(async () => ({
    name: '', description: '', body: '',
  }));
  (window as unknown as {
    bridge: Pick<Bridge, 'listReviewSkills' | 'listAiProviders' | 'setSkillEnabled' | 'draftSkill'>;
  }).bridge = {
    listReviewSkills,
    listAiProviders,
    setSkillEnabled,
    draftSkill,
  };
}

function mkSkill(overrides: Partial<ReviewSkillDto>): ReviewSkillDto {
  return {
    id: 1,
    skillName: 'Always-on style skill',
    repo: '*',
    llmProvider: null,
    description: 'reviewing any PR',
    context: 'House style notes.',
    enabled: true,
    createdAt: '2026-05-25T12:00:00Z',
    updatedAt: '2026-05-25T12:00:00Z',
    ...overrides,
  };
}
