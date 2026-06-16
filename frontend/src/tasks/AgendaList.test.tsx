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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AgendaList, parseAgenda } from './AgendaList';

afterEach(cleanup);

describe('parseAgenda', () => {
  it('returns an empty list for null / blank / malformed json', () => {
    expect(parseAgenda(null)).toHaveLength(0);
    expect(parseAgenda('')).toHaveLength(0);
    expect(parseAgenda('not json')).toHaveLength(0);
    expect(parseAgenda('{"not":"an array"}')).toHaveLength(0);
  });

  it('parses well-formed agenda items and drops malformed ones', () => {
    const json = JSON.stringify([
      { id: 'a', title: 'Implement', status: 'DONE' },
      { id: 'b', title: 'Validate', status: 'IN_PROGRESS' },
      { id: 'c', title: 'no status' },              // dropped
      { id: 'd', title: 'Review', status: 'OPEN' },
    ]);
    const agenda = parseAgenda(json);
    expect(agenda.map(a => a.id)).toEqual(['a', 'b', 'd']);
  });
});

describe('AgendaList', () => {
  it('renders one row per milestone with status glyphs', () => {
    render(<AgendaList agenda={[
      { id: 'a', title: 'Implement', status: 'DONE' },
      { id: 'b', title: 'Validate', status: 'IN_PROGRESS' },
      { id: 'c', title: 'Review', status: 'OPEN' },
    ]} />);
    expect(screen.getByText('Implement')).toBeTruthy();
    expect(screen.getByText('Validate')).toBeTruthy();
    expect(screen.getByText('Review')).toBeTruthy();
    // Glyphs: ✓ done, ◼ in-progress, ◻ open.
    expect(screen.getByText('✓')).toBeTruthy();
    expect(screen.getByText('◼')).toBeTruthy();
    expect(screen.getByText('◻')).toBeTruthy();
  });
});
