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

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { NO_AGENT_CAPABILITIES } from './capabilities';
import { ProviderPicker, providerFocusTarget } from './ProviderPicker';
import type { ModelProviderDto } from '../types';

function provider(id: string, readiness: ModelProviderDto['readiness']): ModelProviderDto {
  return {
    id,
    label: id === 'claude' ? 'Claude' : 'Codex',
    readiness,
    version: null,
    diagnostic: readiness === 'READY' ? 'ready' : 'sign in required',
    capabilities: NO_AGENT_CAPABILITIES,
    models: [],
    selectedModel: '',
    selectedEffort: '',
    main: id === 'codex',
  };
}

describe('provider picker accessibility', () => {
  it('focuses the selected ready lane or the first ready fallback', () => {
    const providers = [provider('claude', 'AUTHENTICATION_REQUIRED'), provider('codex', 'READY')];
    expect(providerFocusTarget(providers, 'codex')).toBe('codex');
    expect(providerFocusTarget(providers, 'claude')).toBe('codex');
    expect(providerFocusTarget([provider('claude', 'CLI_MISSING')], 'claude')).toBeNull();
  });

  it('exposes a named popup trigger and implements keyboard open and focus-restoring Escape', () => {
    const markup = renderToStaticMarkup(
      <ProviderPicker providers={[provider('codex', 'READY')]} selectedId="codex" onSelect={() => {}} />,
    );
    expect(markup).toContain('aria-label="Provider: Codex"');
    expect(markup).toContain('aria-haspopup="dialog"');
    expect(markup).toContain('aria-expanded="false"');

    const source = readFileSync(resolve(process.cwd(), 'src/agents/ProviderPicker.tsx'), 'utf8');
    expect(source).toContain("event.key === 'ArrowDown'");
    expect(source).toContain("event.key !== 'Escape'");
    expect(source.indexOf('[data-provider-focus="lane"]'))
      .toBeLessThan(source.indexOf('[data-provider-focus="remedy"]'));
    expect(source).toContain('triggerRef.current?.focus()');
  });
});
