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
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import Avatar from './Avatar';

afterEach(cleanup);

describe('Avatar', () => {
  it('builds the plain github.com/{login}.png URL for a normal handle', () => {
    const { container } = render(<Avatar login="octocat" />);
    expect(container.querySelector('img')?.getAttribute('src'))
      .toBe('https://github.com/octocat.png?size=40');
  });

  it('strips the [bot] suffix from the image URL, not from alt text', () => {
    const { container } = render(<Avatar login="coderabbitai[bot]" />);
    const img = container.querySelector('img');
    expect(img?.getAttribute('src')).toBe('https://github.com/coderabbitai.png?size=40');
    expect(img?.getAttribute('alt')).toBe('coderabbitai[bot]');
  });

  it('is case-insensitive on the [bot] suffix', () => {
    const { container } = render(<Avatar login="github-actions[Bot]" />);
    expect(container.querySelector('img')?.getAttribute('src'))
      .toBe('https://github.com/github-actions.png?size=40');
  });
});
