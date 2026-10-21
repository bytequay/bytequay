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
import { FeatureCard } from './FeaturesPage';

const agent = vi.hoisted(() => ({ loading: false, development: false }));
vi.mock('../agents/availability', () => ({ useAgentAvailability: () => agent }));

function card(on: boolean) {
  return renderToStaticMarkup(<FeatureCard section="Today" title="Enable daily agenda" error={null} on={on} busy={false} onChange={() => {}}>
    <span>details</span>
  </FeatureCard>);
}

describe('FeatureCard', () => {
  it('turns on only with a usable Development agent, and always turns off', () => {
    agent.development = false;
    expect(card(false)).toContain('disabled=""');
    expect(card(false)).toContain('Models</a>');
    expect(card(true)).not.toContain('disabled=""');
    agent.development = true;
    expect(card(false)).not.toContain('disabled=""');
    expect(card(false)).not.toContain('Models</a>');
  });

  it('shows its own settings only while on', () => {
    agent.development = true;
    expect(card(false)).not.toContain('details');
    expect(card(true)).toContain('details');
  });
});
