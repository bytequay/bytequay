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
import WorkspaceVisualFixture from './WorkspaceVisualFixture';
import { installWorkspaceVisualBridge } from './workspaceVisualFixtureData';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
});

describe('locked workspace page fixtures', () => {
  for (const frame of ['7a', '7b', '7c']) {
    it(`renders frame ${frame}`, () => {
      installWorkspaceVisualBridge(frame);
      const { container } = render(<WorkspaceVisualFixture frame={frame} />);
      expect(container.querySelector(`.workspace-visual-frame-${frame}`)).not.toBeNull();
    });
  }
});
