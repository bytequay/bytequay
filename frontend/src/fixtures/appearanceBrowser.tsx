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
import type { AppPreferencesDto } from '../types';
import SettingsWindow from '../settings/SettingsWindow';
import MarkdownBody from '../pulls/MarkdownBody';
import { AgentMark } from '../agents/AgentMark';
import { Terminal } from '@xterm/xterm';
import { followTerminalTheme } from '../terminalSession';
import { assert, settle } from './harness';
import '../index.css';
import '../css/agents.css';
import '../css/goals.css';
import '../css/epic-view.css';
import '../css/memory.css';
import '../css/recent.css';
import '../css/repository-context.css';
import '../css/code-tests.css';
import '../css/cards.css';
import '../css/confirm-dialog.css';
import '../jira/jira.css';
import '../teams/teams.css';

let preferences: AppPreferencesDto = { theme: 'LIGHT', taskRetentionDays: 7, recentRetentionDays: 14, prWarmCount: 30 };
const surfaces = [
  ['Home', 'home-page'], ['Workspaces', 'ws-card'], ['Pull requests', 'pulls-toolbar-button'],
  ['Recent', 'rc-menu'], ['Commits', 'cmt-root'], ['Agents', 'agt-compose'], ['Goals', 'goal-panel'],
  ['Epics', 'epic-view__sync'], ['Memory', 'mem-head'], ['Repository context', 'rcp'],
  ['Tears of time', 'ct'], ['Settings', 'setw-btn'], ['Jira', 'jira-btn'], ['Circles', 'cir-page'],
  ['Cards', 'card'], ['Dialogs', 'bq-confirm-dialog'], ['Browser', 'inapp-browser__toolbar'],
];
const states = ['agt-state is-running', 'agt-state is-completed', 'agt-state is-failed', 'rc-badge is-purple', 'rc-badge is-amber'];
const style = { position: 'static', display: 'block', width: 'auto', height: 'auto', minHeight: 0, maxHeight: 'none', margin: 0, padding: 14, borderRadius: 8 } as const;

let terminal: Terminal;

export async function mount() {
  const host = document.createElement('div'); document.body.append(host);
  Object.assign(window, { bridge: {
    getAppVersion: async () => ({ version: '0.4.0' }), getAppPreferences: async () => preferences,
    saveAppPreferences: async (next: AppPreferencesDto) => {
      await (window as unknown as { appearance: { set: (source: string) => Promise<void> } }).appearance.set(next.theme.toLowerCase());
      preferences = next; return next;
    },
  } });
  createRoot(host).render(<div style={{ padding: 28, maxWidth: 1500, margin: 'auto' }}>
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 32 }}>
      <div><div style={{ height: 230 }}><SettingsWindow section="appearance" workspace={null} onWorkspacesChanged={() => {}} /></div>
        <div style={{ display: 'flex', gap: 8, marginTop: 20 }}>{states.map((state, i) => <span key={state} data-contrast className={state}>{['Running', 'Completed', 'Failed', 'Merged', 'Pending'][i]}</span>)}</div>
        <div style={{ display: 'flex', gap: 8, marginTop: 20 }}>
          <button data-contrast className="bq-button bq-button--primary">New task</button>
          <button data-contrast className="agr-action is-primary">Submit review</button>
          <button data-contrast className="bq-button">Cancel</button>
          <button className="bq-button" disabled>Unavailable</button>
          <AgentMark name="Preview backend" />
        </div>
      </div>
      <div className="lrc is-local" style={{ ...style, alignSelf: 'start' }}>
        <div className="lrc__bar" style={{ padding: 10 }}>Private review · Token cache</div>
        <MarkdownBody body={'## Keep the cache bounded\nUse an explicit **size limit**, then validate eviction.\n\n```typescript\nconst limit = 256;\nreturn cache.get(key) ?? null;\n```\n\n> [!WARNING]\n> Check the oldest entry before inserting.'} />
        <div style={{ fontFamily: 'var(--mono)' }}>
          <div data-contrast style={{ padding: 6, background: 'var(--diff-del)' }}>− const limit = Infinity;</div>
          <div data-contrast style={{ padding: 6, background: 'var(--diff-add)' }}>+ const limit = 256;</div>
          <div data-contrast style={{ padding: 6, background: 'var(--diff-selected)' }}>Selected line · add a comment</div>
        </div>
      </div>
    </div>
    <h2 style={{ marginTop: 28 }}>App surfaces</h2>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
      {surfaces.map(([name, className]) => <div key={name} data-surface={name} className={className} style={style}>
        <strong>{name}</strong><div style={{ color: 'var(--text-3)', fontSize: 12, marginTop: 6 }}>Secondary text · Updated just now</div>
      </div>)}
    </div>
    <div id="appearance-terminal" style={{ height: 100, marginTop: 20 }} />
  </div>);
  await settle();
  terminal = new Terminal({ rows: 3, cols: 110 });
  const screen = document.getElementById('appearance-terminal');
  assert(screen !== null, 'Terminal host must mount');
  terminal.open(screen);
  followTerminalTheme(terminal);
  terminal.write('ByteQuay terminal  \x1b[32m✓ All checks passed\x1b[0m\r\n\x1b[34msrc/cache.ts\x1b[0m  \x1b[33m1 warning\x1b[0m');
  return true;
}

const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1;
const paint = canvas.getContext('2d');
assert(paint !== null, 'Canvas must be available');
function channels(color: string): number[] {
  paint.clearRect(0, 0, 1, 1); paint.fillStyle = color; paint.fillRect(0, 0, 1, 1);
  return [...paint.getImageData(0, 0, 1, 1).data];
}
function luminance(rgb: number[]) {
  const c = rgb.slice(0, 3).map(value => value / 255).map(value => value <= 0.04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4);
  return c[0] * .2126 + c[1] * .7152 + c[2] * .0722;
}
function background(element: Element): number[] {
  const value = channels(getComputedStyle(element).backgroundColor);
  if (value[3] === 255 || !element.parentElement) return value;
  const behind = background(element.parentElement); const opacity = value[3] / 255;
  return value.map((channel, i) => i === 3 ? 255 : channel * opacity + behind[i] * (1 - opacity));
}

export async function check(dark: boolean) {
  await settle();
  await new Promise(resolve => setTimeout(resolve, 180));
  assert(matchMedia('(prefers-color-scheme: dark)').matches === dark, 'Native preference must reach the renderer');
  assert((luminance(background(document.body)) < .1) === dark, 'The canvas must follow the native preference');
  assert(terminal.options.theme?.background === (dark ? '#1e2025' : '#ffffff'), 'Terminal colors must follow appearance');
  assert(terminal.buffer.active.getLine(0)?.translateToString().includes('All checks passed') === true, 'Changing theme must retain terminal output');
  const issues: string[] = [];
  for (const element of document.querySelectorAll('[data-contrast], [data-surface], .hljs, .hljs span')) {
    const fg = luminance(channels(getComputedStyle(element).color)); const bg = luminance(background(element));
    const ratio = (Math.max(fg, bg) + .05) / (Math.min(fg, bg) + .05);
    if (ratio < 4.5) issues.push(`${element.getAttribute('data-surface') ?? element.textContent}: ${ratio.toFixed(2)} ${getComputedStyle(element).color} on ${getComputedStyle(element).backgroundColor} dark=${dark}`);
    if (dark && element.hasAttribute('data-surface') && bg > .15) issues.push(`${element.getAttribute('data-surface')}: light surface`);
  }
  assert(issues.length === 0, issues.join('\n'));
  return { dark, checked: document.querySelectorAll('[data-contrast], [data-surface], .hljs, .hljs span').length };
}

export async function choose(label: string) {
  const button = [...document.querySelectorAll<HTMLButtonElement>('[aria-label="Theme"] button')].find(element => element.textContent === label);
  assert(button !== undefined && !button.disabled, `${label} must be available`);
  button.click(); await settle();
}
