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
import { afterEach, expect, it } from 'vitest';
import { CreationOriginBadge, isAutomatedOrigin } from './CreationOriginBadge';

afterEach(cleanup);

it('labels human and automated creators distinctly', () => {
  const { rerender } = render(<CreationOriginBadge origin="user" />);
  expect(screen.getByText('User')).toBeTruthy();
  rerender(<CreationOriginBadge origin="issue-monitor" />);
  expect(screen.getByText('Issue monitor')).toBeTruthy();
  expect(isAutomatedOrigin('quality-scan')).toBe(true);
  expect(isAutomatedOrigin('user-report')).toBe(false);
  expect(isAutomatedOrigin('unknown')).toBe(false);
});
