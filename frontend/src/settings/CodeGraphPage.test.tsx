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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import CodeGraphPage from './CodeGraphPage';

describe('code graph page', () => {
  it('explains what the selected mode does and reads the baseline', () => {
    const markup = renderToStaticMarkup(
      <CodeGraphPage mode="ON" baseline={{ repositories: 3, updatedAt: null }} error={null} onMode={vi.fn()} />,
    );

    expect(markup).toContain('Every task gets callers, callees, and cross-file structure in one call');
    expect(markup).toContain('3 repos');
  });

  it('offers only on and off', () => {
    const markup = renderToStaticMarkup(
      <CodeGraphPage mode="OFF" baseline={null} error={null} onMode={vi.fn()} />,
    );

    expect(markup).toContain('Tool hidden and indexing stopped');
    expect(markup).toContain('not read yet');
    expect(markup).not.toContain('Compare');
  });
});
