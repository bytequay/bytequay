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
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import type { Bridge } from './types';

export type TerminalStatus = { kind: 'opening' } | { kind: 'open' } | { kind: 'exited'; exitCode: number } | { kind: 'failed'; error: unknown };
type TerminalBridge = Pick<Bridge, 'writeReviewTerminal' | 'resizeReviewTerminal' | 'onReviewTerminalEvent'>;

// GitHub light's ANSI palette; xterm's defaults are tuned for a dark ground.
export const TERMINAL_THEME = {
  background: '#ffffff', foreground: '#1f2328', cursor: '#1f2328', cursorAccent: '#ffffff', selectionBackground: '#b6e3ff',
  black: '#24292f', red: '#cf222e', green: '#116329', yellow: '#4d2d00', blue: '#0550ae', magenta: '#8250df', cyan: '#1b7c83', white: '#6e7781',
  brightBlack: '#57606a', brightRed: '#a40e26', brightGreen: '#1a7f37', brightYellow: '#633c01', brightBlue: '#218bff', brightMagenta: '#a475f9',
  brightCyan: '#3192aa', brightWhite: '#8c959f',
};

/** Recolor existing output without restarting the terminal or its shell. */
export function followTerminalTheme(terminal: Terminal): () => void {
  const media = matchMedia('(prefers-color-scheme: dark)');
  const apply = () => {
    const scheme = getComputedStyle(document.documentElement).colorScheme;
    const dark = scheme === 'dark' || (scheme !== 'light' && media.matches);
    terminal.options.theme = dark ? {
      background: '#1e2025', foreground: '#c9cdd4', cursor: '#c9cdd4', cursorAccent: '#1e2025', selectionBackground: '#354d70',
      black: '#555d69', red: '#ff8589', green: '#7dce96', yellow: '#eac479', blue: '#80b6ff', magenta: '#c3a1f7', cyan: '#76c9c2', white: '#d2d7df',
      brightBlack: '#9ba3af', brightRed: '#ffa0a3', brightGreen: '#a4e1ad', brightYellow: '#f5d994', brightBlue: '#a5cdff', brightMagenta: '#d9bcff',
      brightCyan: '#9bded8', brightWhite: '#f2f4f7',
    } : TERMINAL_THEME;
  };
  apply();
  const observer = new MutationObserver(apply);
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['style'] });
  media.addEventListener('change', apply);
  return () => { observer.disconnect(); media.removeEventListener('change', apply); };
}

/** Attaches the terminal UI; disposing it detaches listeners without stopping the process. */
export function attachTerminal(element: HTMLDivElement, bridge: TerminalBridge,
  open: (cols: number, rows: number) => Promise<{ terminalId: string; scrollback: string }>,
  { scrollback, onStatus, exitMessage }: {
    scrollback: number;
    onStatus: (status: TerminalStatus) => void;
    exitMessage?: (exitCode: number) => string;
  },
): () => void {
  const term = new Terminal({ fontFamily: "ui-monospace, Menlo, 'Roboto Mono', monospace", fontSize: 12, cursorBlink: true, scrollback, theme: TERMINAL_THEME });
  const fit = new FitAddon();
  term.loadAddon(fit);
  term.open(element);
  const stopTheme = followTerminalTheme(term);
  // Hidden panes measure zero, and fitting to zero would shrink the shell to one column.
  const refit = () => {
    if (element.clientWidth > 0 && element.clientHeight > 0) fit.fit();
  };
  refit();

  let live = true;
  let terminalId: string | null = null;
  const stopEvents = bridge.onReviewTerminalEvent(event => {
    if (event.terminalId !== terminalId) return;
    if ('data' in event) {
      term.write(event.data);
      return;
    }
    terminalId = null;
    if (exitMessage) term.write(`\r\n\x1b[90m[${exitMessage(event.exitCode)}]\x1b[0m\r\n`);
    onStatus({ kind: 'exited', exitCode: event.exitCode });
  });
  const input = term.onData(data => {
    if (terminalId !== null) bridge.writeReviewTerminal(terminalId, data);
  });
  const resize = term.onResize(({ cols, rows }) => {
    if (terminalId !== null) bridge.resizeReviewTerminal(terminalId, cols, rows);
  });
  const observer = new ResizeObserver(refit);
  observer.observe(element);

  onStatus({ kind: 'opening' });
  // Output pushed before the attach answers is already in the scrollback, so it is dropped until the id is known.
  open(term.cols, term.rows).then(opened => {
    if (!live) return;
    terminalId = opened.terminalId;
    term.write(opened.scrollback);
    bridge.resizeReviewTerminal(opened.terminalId, term.cols, term.rows);
    onStatus({ kind: 'open' });
    term.focus();
  }, failure => {
    if (live) onStatus({ kind: 'failed', error: failure });
  });

  return () => {
    live = false;
    stopEvents();
    input.dispose();
    resize.dispose();
    observer.disconnect();
    stopTheme(); term.dispose();
  };
}
