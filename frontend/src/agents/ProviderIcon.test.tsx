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
import { describe, expect, it } from 'vitest';
import { ProviderAvatar, ProviderIcon, providerBrand } from './ProviderIcon';

describe('provider icon', () => {
  it('knows each lane and saved key provider by its company', () => {
    expect(providerBrand('claude')).toBe('claude');
    expect(providerBrand('claude-account-1')).toBe('claude');
    expect(providerBrand('codex')).toBe('openai');
    expect(providerBrand('OpenAI')).toBe('openai');
    expect(providerBrand('deepseek-key-1')).toBe('deepseek');
    expect(providerBrand('DeepSeek')).toBe('deepseek');
    expect(providerBrand('key:other')).toBeNull();
  });

  it('draws the logo, and the plain agent avatar for a provider without one', () => {
    expect(renderToStaticMarkup(<ProviderIcon id="claude" />)).toContain('data-brand="claude"');
    expect(renderToStaticMarkup(<ProviderIcon id="key:other" />)).toBe('');
    expect(renderToStaticMarkup(<ProviderAvatar id="codex" />)).toContain('class="provider-avatar"');
    expect(renderToStaticMarkup(<ProviderAvatar id="key:other" />)).toContain('aria-label="Agent"');
  });
});
