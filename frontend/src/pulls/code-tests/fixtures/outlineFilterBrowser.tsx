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
import '../../../css/base.css';
import '../../../css/code-tests.css';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import CodeView from '../CodeView';
import type { ReviewCodeSymbolDto } from '../contracts';
import { OutlinePopover, SymbolChooser } from '../Outline';
import { assert, settle } from '../../../fixtures/harness';

const PATH = 'plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergMetadata.java';

const symbol = (name: string, startLine: number, kind = 'method'): ReviewCodeSymbolDto => ({
  name, kind, qualifiedName: `io.trino.plugin.iceberg::IcebergMetadata::${name}`, path: PATH,
  startLine, startColumn: 4, endLine: startLine + 6, signature: null, test: false, added: false,
});

// Enough members that the list must scroll inside the popover, as a real metadata class does.
const members: ReviewCodeSymbolDto[] = [
  symbol('IcebergMetadata', 120, 'class'),
  symbol('addColumn', 2754),
  symbol('addField', 2801),
  symbol('dropColumn', 2860),
  ...Array.from({ length: 40 }, (_, index) => symbol(`member${index}`, 300 + index * 10)),
];

function mounted(render: (root: ReturnType<typeof createRoot>) => void, scenario: (host: HTMLElement) => Promise<void>): Promise<void> {
  const host = document.createElement('div');
  host.style.position = 'relative';
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => render(root));
  return settle()
    .then(() => scenario(host))
    .finally(() => {
      flushSync(() => root.unmount());
      host.remove();
    });
}

function type(host: HTMLElement, value: string) {
  const input = host.querySelector<HTMLInputElement>('.ct-outline__filter input');
  assert(input !== null, 'The outline must offer a filter field');
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
  return settle();
}

const rows = (host: HTMLElement) => [...host.querySelectorAll<HTMLElement>('.ct-outline__row')];
const names = (host: HTMLElement) => rows(host).map(row => row.querySelector('.ct-outline__name')?.textContent);

const outline = (opened: number[]) => (root: ReturnType<typeof createRoot>) => root.render(
  <OutlinePopover path={PATH} outline={{ status: 'loaded', generation: 1, result: { status: 'READY', symbols: members } }}
    codeGraph="READY" currentLine={0} onOpen={line => opened.push(line)} onClose={() => {}}
    runs={[]} gate={{ hidden: true, blocked: null }} onRun={() => {}} />);

const filtersMembers = () => {
  const opened: number[] = [];
  return mounted(outline(opened), async host => {
    assert(rows(host).length === members.length, `The unfiltered outline must list every member, not ${rows(host).length}`);
    await type(host, 'addcol');
    assert(names(host).join() === 'addColumn()', `A filter must narrow the outline case-insensitively, not to ${names(host).join()}`);
    assert(rows(host)[0].classList.contains('is-active'), 'The first match must be marked, so Enter is not invisible');
    await type(host, 'add');
    assert(names(host).join() === 'addColumn(),addField()', 'A filter must keep every match in declaration order');
    await type(host, 'nothinghere');
    assert(rows(host).length === 0 && host.textContent?.includes('No type or method matches'),
      'A filter with no match must say so rather than claim the file has no types or methods');
    assert(host.querySelector('.ct-outline__filter input') !== null, 'The filter field must survive an empty result, so the query can be corrected');
    await type(host, 'dropColumn');
    host.querySelector<HTMLInputElement>('.ct-outline__filter input')?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    await settle();
    assert(opened.join() === '2860', `Enter must open the first match, not ${opened.join()}`);
  });
};

const filterStaysPinned = () => mounted(outline([]), async host => {
  const popover = host.querySelector<HTMLElement>('.ct-outline');
  const list = host.querySelector<HTMLElement>('.ct-outline ul');
  const field = host.querySelector<HTMLElement>('.ct-outline__filter');
  assert(popover !== null && list !== null && field !== null, 'The outline must render a filter, a list and a popover');
  assert(list.scrollHeight > list.clientHeight, 'A 44-member outline must scroll its list rather than grow the popover');
  const before = field.getBoundingClientRect().top;
  list.scrollTop = list.scrollHeight;
  await settle();
  assert(field.getBoundingClientRect().top === before, 'Scrolling the list must not carry the filter field away');
  assert(popover.getBoundingClientRect().bottom <= window.innerHeight, 'The outline must stay inside the window');
});

const chooserNamesATruncatedSearch = () => {
  const options = Array.from({ length: 200 }, (_, index) => symbol('addColumn', 100 + index));
  return mounted(
    root => root.render(<SymbolChooser chooser={{ name: 'addColumn', options: options.slice(0, 3), truncated: false, x: 10, y: 10 }}
      changedPaths={new Set()} onOpen={() => {}} onClose={() => {}} />),
    async host => {
      assert(host.querySelector('.ct-chooser__header span')?.textContent?.startsWith('3 possible definitions'),
        'A complete result must be counted plainly');
    })
    .then(() => mounted(
      root => root.render(<SymbolChooser chooser={{ name: 'addColumn', options, truncated: true, x: 10, y: 10 }}
        changedPaths={new Set()} onOpen={() => {}} onClose={() => {}} />),
      async host => {
        assert(host.querySelector('.ct-chooser__header span')?.textContent?.startsWith('The first 200 possible definitions'),
          'A search that filled its limit must not claim to have listed every definition');
      }));
};

const chooserStaysOnScreen = () => {
  const options = Array.from({ length: 48 }, (_, index) => symbol('addColumn', 100 + index));
  const opened: ReviewCodeSymbolDto[] = [];
  // A ⌘-click near the bottom edge is the case that ran 48 rows past the window.
  const y = window.innerHeight - 40;
  return mounted(
    root => root.render(<SymbolChooser chooser={{ name: 'addColumn', options, truncated: false, x: 200, y }} changedPaths={new Set()}
      onOpen={option => opened.push(option)} onClose={() => {}} />),
    async host => {
      const popover = host.querySelector<HTMLElement>('.ct-chooser');
      const list = host.querySelector<HTMLElement>('.ct-chooser ul');
      assert(popover !== null && list !== null, 'The chooser must render its list');
      const box = popover.getBoundingClientRect();
      assert(box.top >= 0 && box.bottom <= window.innerHeight,
        `48 definitions clicked at y=${y} must stay inside the window, not span ${Math.round(box.top)}-${Math.round(box.bottom)}`);
      assert(list.scrollHeight > list.clientHeight, 'The chooser list must scroll when it cannot show every definition');
      assert(host.querySelector('.ct-chooser__caveat') !== null, 'The structural-match caveat must stay above the scrolling list');
      list.scrollTop = list.scrollHeight;
      await settle();
      const last = [...host.querySelectorAll<HTMLElement>('.ct-chooser__row')].at(-1);
      assert(last !== undefined && last.getBoundingClientRect().bottom <= window.innerHeight + 1,
        'Every definition must be reachable by scrolling the chooser');
      last.click();
      await settle();
      assert(opened.length === 1, 'A definition scrolled into view must still be clickable');
    });
};

const LINES = 5_000;
const NEEDLE = 'zzTargetMethod';
const big = Array.from({ length: LINES }, (_, index) => index === LINES - 2 ? `    void ${NEEDLE}() {}` : `    // filler ${index + 1}`).join('\n');

/** The search panel tests `keyCode` for Enter, which a synthetic event leaves at 0 unless it is given one. */
function key(target: Element, init: KeyboardEventInit & { keyCode?: number }) {
  target.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init }));
  return settle();
}

/** The reason the editor searches its own document: find-in-page can only ever see the lines CodeMirror has rendered. */
const findsOutsideTheViewport = () => mounted(
  root => root.render(<div style={{ height: '400px', display: 'flex' }} className="ct-code">
    <CodeView path="Big.java" value={big} saved={big} line={0} token={0} />
  </div>),
  async host => {
    const rendered = () => host.querySelectorAll('.cm-content .cm-line').length;
    assert(rendered() < LINES / 4, `CodeMirror must render only a viewport, not ${rendered()} of ${LINES} lines`);
    assert(!(host.querySelector('.cm-content')?.textContent ?? '').includes(NEEDLE),
      'The needle must start outside the rendered DOM, which is exactly what find-in-page cannot reach');

    const content = host.querySelector('.cm-content');
    assert(content !== null, 'The editor must render its content');
    (content as HTMLElement).focus();
    const shortcut = new KeyboardEvent('keydown', { key: 'f', metaKey: true, bubbles: true, cancelable: true });
    content.dispatchEvent(shortcut);
    await settle();
    const field = host.querySelector<HTMLInputElement>('.cm-search input[name=search]');
    assert(field !== null, '⌘F must open the editor’s own find panel');
    // FindBar skips a handled ⌘F, so the window find bar does not open a second one on top of this panel.
    assert(shortcut.defaultPrevented, '⌘F handled by the editor must be marked handled');

    field.value = NEEDLE;
    field.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
    await settle();
    await key(field, { key: 'Enter', keyCode: 13 });
    const selected = host.querySelector('.cm-searchMatch-selected');
    assert(selected !== null && selected.textContent === NEEDLE,
      `Find must reach line ${LINES - 1}, far past the rendered viewport`);
    assert(host.querySelector('.cm-search [name=replace]') === null, 'Read-only source must offer no replace controls');

    await key(field, { key: 'Escape' });
    assert(host.querySelector('.cm-search') === null, 'Escape must close the find panel');
  });

export async function run(): Promise<{ name: string; error?: string }[]> {
  const cases: Array<[string, () => Promise<void>]> = [
    ['Code & Tests outline filters its types and methods', filtersMembers],
    ['Code & Tests outline keeps its filter pinned above a scrolling list', filterStaysPinned],
    ['Code & Tests definition chooser stays inside the window and scrolls', chooserStaysOnScreen],
    ['Code & Tests definition chooser says when its search filled the limit', chooserNamesATruncatedSearch],
    ['Code & Tests finds text outside the rendered viewport', findsOutsideTheViewport],
  ];
  const results: { name: string; error?: string }[] = [];
  for (const [name, scenario] of cases) {
    try {
      await scenario();
      results.push({ name });
    }
    catch (error) {
      results.push({ name, error: error instanceof Error ? error.message : String(error) });
    }
  }
  return results;
}
