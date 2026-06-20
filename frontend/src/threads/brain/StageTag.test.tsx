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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { StageTag } from './StageTag';
import { personaForStageType } from './stageMeta';
import type { StageType } from '../../types/brainView';

afterEach(cleanup);

describe('personaForStageType', () => {
  it('maps each stage type to its persona color class', () => {
    expect(personaForStageType('DEVELOPMENT_STAGE')).toBe('dev');
    expect(personaForStageType('CI_FIXING_STAGE')).toBe('cifix');
    expect(personaForStageType('REVIEW_MONITOR_STAGE')).toBe('revmon');
    expect(personaForStageType('REVIEW_STAGE')).toBe('review');
    expect(personaForStageType('CLEANUP_STAGE')).toBe('neutral');
    expect(personaForStageType(null)).toBe('neutral');
  });
});

describe('StageTag', () => {
  it('renders the persona class for the given stage type', () => {
    const cases: Array<[StageType, string]> = [
      ['DEVELOPMENT_STAGE', 'dev'],
      ['CI_FIXING_STAGE', 'cifix'],
      ['REVIEW_MONITOR_STAGE', 'revmon'],
      ['REVIEW_STAGE', 'review'],
    ];
    for (const [type, persona] of cases) {
      const { container, unmount } = render(<StageTag label="X" stageType={type} />);
      const tag = container.querySelector('.stage-tag');
      expect(tag?.classList.contains(persona)).toBe(true);
      unmount();
    }
  });

  it('exposes role=link and calls onOpen when clicked', () => {
    const onOpen = vi.fn();
    render(<StageTag label="DevelopmentStage" stageType="DEVELOPMENT_STAGE" onOpen={onOpen} />);
    const link = screen.getByRole('link', { name: /DevelopmentStage/ });
    fireEvent.click(link);
    expect(onOpen).toHaveBeenCalledTimes(1);
  });
});
