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
import type { Bridge, SkillDto } from '../../types';
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
      mkSkill({ id: 1, scope: 'global', description: 'reviewing a PR touching auth' }),
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

  it('splits the nav into Development / Review and filters by the active branch', async () => {
    installBridge([
      mkSkill({ id: 1, name: 'Dev voice', usage: 'build', description: 'editing backend code' }),
      mkSkill({ id: 2, name: 'Concurrency Hawk', usage: 'review', description: 'reviewing concurrency' }),
    ]);

    render(<SkillsPage />);

    // Development is the default branch — only the build skill shows.
    await waitFor(() => expect(screen.getByText('Dev voice')).toBeTruthy());
    expect(screen.queryByText('Concurrency Hawk')).toBeNull();

    // Switching to the Review branch swaps the visible set.
    fireEvent.click(screen.getByText('✦ Review'));
    await waitFor(() => expect(screen.getByText('Concurrency Hawk')).toBeTruthy());
    expect(screen.queryByText('Dev voice')).toBeNull();
  });

  it('opens the add modal in review mode with the @mention identity field', async () => {
    installBridge([mkSkill({ id: 2, name: 'Concurrency Hawk', usage: 'review' })]);

    render(<SkillsPage />);
    await waitFor(() => expect(screen.getByText('⚒ Development')).toBeTruthy());

    fireEvent.click(screen.getByText('✦ Review'));
    await waitFor(() => expect(screen.getByText('+ New review skill')).toBeTruthy());
    fireEvent.click(screen.getByText('+ New review skill'));

    // Review skills are named voices — the modal foregrounds the
    // @mention identity, and there is no kind / role picker.
    await waitFor(() => expect(screen.getByText('@mention identity')).toBeTruthy());
    expect(screen.queryByText('Per-role')).toBeNull();
  });

  it('drafts a skill via the AI button and lands the proposal in the editor', async () => {
    const draftSkill = vi.fn(async () => ({
      name: 'Auth review checklist',
      description: 'reviewing a PR that touches authentication',
      body: 'Check token expiry handling.',
    }));
    installBridge([], { draftSkill });

    render(<SkillsPage />);
    await waitFor(() => expect(screen.getByText('+ New development skill')).toBeTruthy());

    fireEvent.click(screen.getByText('+ New development skill'));
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
  initial: SkillDto[],
  overrides?: Partial<Bridge>,
): void {
  let snapshot = [...initial];
  const listSkills = vi.fn(async () => snapshot);
  const setSkillEnabled = overrides?.setSkillEnabled ?? vi.fn(async (id: number, enabled: boolean) => {
    snapshot = snapshot.map(s => s.id === id ? { ...s, enabled } : s);
    return snapshot.find(s => s.id === id)!;
  });
  const draftSkill = overrides?.draftSkill ?? vi.fn(async () => ({
    name: '', description: '', body: '',
  }));
  (window as unknown as {
    bridge: Pick<Bridge, 'listSkills' | 'setSkillEnabled' | 'draftSkill'>;
  }).bridge = {
    listSkills,
    setSkillEnabled,
    draftSkill,
  };
}

function mkSkill(overrides: Partial<SkillDto>): SkillDto {
  return {
    id: 1,
    scope: 'global',
    repo: null,
    threadId: null,
    name: 'Always-on style skill',
    description: 'reviewing any PR',
    body: 'House style notes.',
    kind: 'library',
    usage: 'build',
    roleTag: null,
    enabled: true,
    isDefault: false,
    source: 'authored',
    provenance: null,
    contentHash: 'deadbeef',
    createdAt: '2026-05-25T12:00:00Z',
    updatedAt: '2026-05-25T12:00:00Z',
    ...overrides,
  };
}
