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
import type { VoiceState } from '../dictation/contracts';
import { downloadProgress, FeatureCard, voiceFooter, voiceSwitchDisabled } from './FeaturesPage';

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

describe('Voice card', () => {
  const footer = (state: VoiceState | null, on = false, fraction: number | null = null, translating = false) => renderToStaticMarkup(<>{voiceFooter({
    support: state === null ? null : { state, macos: '15.6', language: 'Chinese (China mainland)' },
    on, fraction, written: 'English', translating, onCheck: () => {}, onPrivacy: () => {}, onDownload: () => {}, onTranslate: () => {},
  })}</>);

  it.each([
    [null, 'Checking this Mac…', null],
    ['macos-too-old', 'Needs macOS 26 or later. This Mac has 15.6. Update macOS first.', null],
    ['unavailable', 'This Mac can’t run on-device dictation.', null],
    ['language-unsupported', 'Dictation doesn’t support Chinese (China mainland) yet.', null],
    ['no-microphone', 'No microphone found. Connect one, then check again.', 'Check again'],
    ['microphone-denied', 'ByteQuay can’t use the microphone.', 'Open Privacy settings'],
    ['needs-model', 'Chinese (China mainland) speech model needed.', 'Download'],
    ['needs-translation', 'Chinese (China mainland) to English translation needed.', 'Download'],
  ] as const)('says what %s needs', (state, text, action) => {
    const html = footer(state);
    expect(html).toContain(text);
    if (action === null) expect(html).not.toContain('<button');
    else expect(html).toContain(`>${action}</button>`);
  });

  it('shows download progress, and the hold hint only once on', () => {
    expect(footer('needs-model', false, 0.38)).toContain('value="38"');
    expect(footer('needs-model', false, 0.38)).toContain('38%');
    expect(footer('downloading')).toContain('Downloading the Chinese (China mainland) speech model');
    expect(footer('ready')).toBe('');
    expect(footer('ready', true)).toContain('Hold right ⌥ in any composer to talk.');
  });

  it('moves the bar only for a download the card started', () => {
    expect(downloadProgress(0, 0.38)).toBe(0.38);
    expect(downloadProgress(null, 0)).toBeNull();
  });

  it('waits on Apple’s download window without offering another download', () => {
    const html = footer('needs-translation', false, null, true);
    expect(html).toContain('Finish the download in the window Apple opened.');
    expect(html).not.toContain('<button');
  });

  it('turns on only when every check passes, and always turns off', () => {
    expect(voiceSwitchDisabled(false, 'ready', false)).toBe(false);
    expect(voiceSwitchDisabled(false, 'needs-model', false)).toBe(true);
    expect(voiceSwitchDisabled(false, undefined, false)).toBe(true);
    expect(voiceSwitchDisabled(true, 'microphone-denied', false)).toBe(false);
    expect(voiceSwitchDisabled(false, 'ready', true)).toBe(true);
  });
});
