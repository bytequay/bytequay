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
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import UsageMeter from './UsageMeter';
import type { ProviderUsageDto } from '../types';
import { assert, settle, element } from '../fixtures/harness';
import '../css/base.css';

let calls = 0;
const now = Date.now();
const usage = (accountId: string, usedPercent: number): ProviderUsageDto => ({ accountId, plan: accountId === 'codex' ? 'pro' : 'max',
  checkedAt: new Date().toISOString(), message: null, windows: [{ id: 'five_hour', label: '5-hour', usedPercent,
    startsAt: new Date(now - 2 * 3600_000).toISOString(), resetsAt: new Date(now + 3 * 3600_000).toISOString(), amount: null }] });
const work = 'claude-account-11111111-1111-4111-8111-111111111111';
const apiKey = 'api-key-22222222-2222-4222-8222-222222222222';

export async function run() {
  window.bridge = {
    getUsageAccounts: async () => [{ id: 'codex', label: 'Codex' }, { id: 'claude', label: 'Claude' }, { id: work, label: 'Claude · Work' }, { id: apiKey, label: 'DeepSeek · ds-001' }],
    getProviderUsage: async accountId => {
      calls++;
      if (accountId === apiKey) return { ...usage(accountId, 0), plan: null, windows: [], message: 'Available balance: USD 12.3456.' };
      return accountId === work ? { ...usage(accountId, 0), windows: [], message: 'This account does not expose usage limits.' } : usage(accountId, accountId === 'codex' ? 18 : 62);
    },
  } as typeof window.bridge;
  const host = document.createElement('aside');
  host.className = 'app-sidebar';
  host.style.height = 'calc(100vh - 24px)';
  document.body.style.display = 'block';
  document.body.style.padding = '12px';
  document.body.append(host);
  flushSync(() => createRoot(host).render(<div style={{ marginTop: 'auto' }}><UsageMeter /></div>));
  await settle();
  assert(calls === 0, 'Closed usage popover must not query providers');
  const trigger = element<HTMLButtonElement>('button', host, 'Usage trigger is missing');
  trigger.focus();
  trigger.click();
  await settle();
  await settle();
  assert(document.querySelector('.usage-popover:popover-open') !== null, 'Usage popover did not open');
  const segment = (name: string) => parseFloat(document.querySelector<HTMLElement>('.usage-track .' + name)?.style.width ?? '-1');
  assert(Math.abs(segment('usage-used') - 18) < .1 && Math.abs(segment('usage-room') - 22) < .1, 'Below-pace segments are incorrect');
  const select = element<HTMLSelectElement>('.usage-account', document, 'Account selector is missing');
  select.value = 'claude'; select.dispatchEvent(new Event('change', { bubbles: true }));
  await settle(); await settle();
  assert(Math.abs(segment('usage-used') - 40) < .1 && Math.abs(segment('usage-over') - 22) < .1, 'Above-pace segments are incorrect');
  select.value = work; select.dispatchEvent(new Event('change', { bubbles: true }));
  await settle(); await settle();
  assert(document.querySelector('.usage-track') === null, 'Unavailable account must not display zero usage');
  assert(document.querySelector('.usage-empty')?.textContent?.includes('does not expose'), 'Unavailable message is missing');
  select.value = apiKey; select.dispatchEvent(new Event('change', { bubbles: true }));
  await settle(); await settle();
  assert(select.selectedOptions[0]?.textContent === 'DeepSeek · ds-001', 'Saved API alias is missing');
  assert(document.querySelector('.usage-empty')?.textContent?.includes('Available balance: USD 12.3456.'), 'DeepSeek balance is missing');
  assert(document.querySelector('.usage-track') === null, 'API-key account must not inherit a subscription bar');
  select.value = 'claude'; select.dispatchEvent(new Event('change', { bubbles: true }));
  await settle(); await settle();
  checkLayout();
  return { calls, abovePace: true, belowPace: true, unavailable: true, apiAlias: true };
}

export function checkLayout() {
  const panel = element<HTMLElement>('.usage-popover', document, 'Missing popover');
  const rect = panel.getBoundingClientRect();
  assert(rect.left >= 0 && rect.right <= innerWidth && rect.top >= 0 && rect.bottom <= innerHeight, 'Popover leaves viewport');
  assert(panel.scrollWidth === panel.clientWidth, 'Popover content overflows horizontally');
  return true;
}

export function checkClosed() {
  assert(document.querySelector('.usage-popover:popover-open') === null, 'Escape did not close the popover');
  assert(document.activeElement?.getAttribute('aria-label') === 'Account usage', 'Escape did not return focus to the trigger');
  return true;
}
