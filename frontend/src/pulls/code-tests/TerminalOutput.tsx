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
import { useEffect, useRef } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { TERMINAL_THEME, followTerminalTheme } from './TerminalView';

/** Historical output uses the same ANSI renderer as the terminal, with input disabled. */
export default function TerminalOutput({ text, onSelect }: { text: string; onSelect: (text: string) => void }) {
  const element = useRef<HTMLDivElement>(null);
  const terminal = useRef<Terminal | null>(null);
  const written = useRef('');
  const selected = useRef(onSelect); selected.current = onSelect;
  useEffect(() => {
    if (!element.current) return;
    const term = new Terminal({ disableStdin: true, convertEol: true, scrollback: 20_000, fontSize: 12, theme: TERMINAL_THEME });
    const fit = new FitAddon(); term.loadAddon(fit); term.open(element.current); terminal.current = term;
    const stopTheme = followTerminalTheme(term);
    const resize = new ResizeObserver(() => { if (element.current?.clientHeight && element.current?.clientWidth) fit.fit(); });
    resize.observe(element.current);
    const selection = term.onSelectionChange(() => selected.current(term.getSelection().slice(0, 30_000)));
    return () => { resize.disconnect(); stopTheme(); selection.dispose(); term.dispose(); terminal.current = null; written.current = ''; };
  }, []);
  useEffect(() => {
    const term = terminal.current;
    if (!term) return;
    if (!text.startsWith(written.current)) { term.reset(); written.current = ''; selected.current(''); }
    term.write(text.slice(written.current.length)); written.current = text;
  }, [text]);
  return <div className="ct-terminal-output" ref={element} />;
}
