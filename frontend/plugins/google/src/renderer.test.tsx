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
import { expect, it, vi } from 'vitest';
import { createGoogleBridge } from './preload';
import { GooglePage, GoogleScreen, type GoogleHost } from './renderer';

it('renders the standalone Google surfaces with an explicit host and no desktop globals', () => {
  const invoke = vi.fn();
  const host: GoogleHost = {
    bridge: { ...createGoogleBridge({ invoke }), openExternal: vi.fn() },
    settingsUrl: '#/settings/integrations/google',
    openGitHubLink: vi.fn(),
    Markdown: ({ body }) => <p>{body}</p>,
    BetterWords: () => <button type="button">Better words</button>,
  };
  expect(renderToStaticMarkup(<GoogleScreen host={host} />)).toContain('Loading Google Apps');
  expect(renderToStaticMarkup(<GooglePage host={host} />)).toContain('Loading Google settings');
  expect(invoke).not.toHaveBeenCalled();
});
