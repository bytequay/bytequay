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
import { expect, it } from 'vitest';
import Composer from './Composer';

it('renders on the server, where there is no voice and so no mic', () => {
  const html = renderToStaticMarkup(<Composer aria-label="Message" value="Keep my draft" onChange={() => {}} onSubmit={() => {}} submitEnabled />);
  expect(html).toContain('Keep my draft');
  expect(html).not.toContain('Dictate');
});
