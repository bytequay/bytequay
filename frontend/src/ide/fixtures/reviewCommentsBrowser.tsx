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
import { getService, ICodeEditorService } from '@codingame/monaco-vscode-api';
// eslint-disable-next-line import/no-unresolved -- Use the same themes as the native workbench.
import { IWorkbenchThemeService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/themes/common/workbenchThemeService.service';
import { editor, Uri } from 'monaco-editor';
import { useCodeComments, type CodeCommentsOptions } from '../../pulls/code-tests/useCodeComments';
import type { DiffAgentComment } from '../../pulls/PullChanges';
import type { PullRequestDto } from '../../types';
import LocalReviewThreadCard from '../../pulls/local-review/LocalReviewThreadCard';
import type { LocalReviewThreadDto } from '../../pulls/local-review/types';
import { assert, changeInput, settle, element } from '../../fixtures/harness';
import ReviewZones from '../ReviewZones';
import type { IdeRuntime } from '../runtime';
import '../../css/base.css';
import '../../css/markdown-editor.css';
import '../../css/pulls.css';
import '../../css/code-tests.css';
import '../workbench.css';

export async function verifyReviewComments() {
  const host = document.createElement('div'); host.className = 'ide-workbench';
  host.style.cssText = 'position:fixed;inset:0;z-index:100'; document.body.append(host);
  const native = document.createElement('div'); native.style.cssText = 'height:100%;width:100%'; host.append(native);
  const shell = document.createElement('div'); host.append(shell);
  const root = createRoot(shell);
  const code = editor.create(native, { automaticLayout: true, minimap: { enabled: false }, lineNumbers: 'on', folding: true });
  const text = Array.from({ length: 150 }, (_, i) => `line ${i + 1}`).join('\n');
  const model = editor.createModel(text, 'plaintext', Uri.parse('vscode-remote://fixture/comments.txt'));
  const other = editor.createModel(text, 'plaintext', Uri.parse('vscode-remote://fixture/other.txt'));
  code.setModel(model);
  const runtime = { editors: await getService(ICodeEditorService) } as IdeRuntime;
  const sent: DiffAgentComment[] = [];
  const options: CodeCommentsOptions = { repo: 'owner/repo', number: 0, canReview: false, viewer: null, pending: [], onPendingChange: () => {},
    threads: { activities: [], pr: { repo: 'owner/repo', number: 0 } as PullRequestDto, unavailable: false, onRefresh: async () => {}, onDetailUpdate: () => {} },
    onDetailChange: () => {}, localReview: { threads: [], onReply: async () => {}, onResolve: async () => {}, onRetry: async () => {} },
    onAskAgent: async comment => { sent.push(comment); } };
  let enabled = true;
  function Harness() {
    const current = code.getModel() ?? model;
    const comments = useCodeComments(enabled ? options : undefined, { path: current.uri.path, text, prPatch: null, draft: false,
      uncommitted: false, sourceHead: 'head', observedHead: 'head', askBlocked: null });
    return <ReviewZones runtime={runtime} uri={current.uri.toString()} {...comments} />;
  }
  const render = () => flushSync(() => root.render(<Harness />));
  const input = () => host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea');
  const heading = () => host.querySelector('.pull-diff-review__header strong')?.textContent;
  const mouse = (type: string, line: number, init: MouseEventInit = {}, content = false) => {
    const bounds = native.getBoundingClientRect(), layout = code.getLayoutInfo();
    const position = code.getScrolledVisiblePosition({ lineNumber: line, column: 1 });
    assert(position !== null, 'Target line must be visible');
    const clientX = bounds.left + (content ? layout.contentLeft + 10 : layout.lineNumbersLeft + layout.lineNumbersWidth - 4);
    const clientY = bounds.top + position.top + position.height / 2;
    const target = document.elementFromPoint(clientX, clientY);
    assert(target !== null, 'Native editor must have a mouse target');
    flushSync(() => target.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, clientX, clientY, button: 0, ...init })));
  };
  const click = async (line: number, init: MouseEventInit = {}, content = false) => {
    mouse('mousedown', line, init, content); mouse('mouseup', line, init, content); await settle();
  };
  const cancel = async () => {
    const button = [...host.querySelectorAll('button')].find(button => button.textContent === 'Cancel');
    assert(button !== undefined, 'Composer must have Cancel'); flushSync(() => button.click()); await settle();
  };
  try {
    render(); code.render(true); await settle();
    await click(2, {}, true); assert(input() === null, 'Source clicks must retain ordinary editing');
    await click(2, { button: 2 }); assert(input() === null, 'Right-click must not start a comment');
    mouse('mousemove', 2, {}, true); await settle();
    const add = native.querySelector<HTMLButtonElement>('.ide-add-comment');
    assert(add !== null && add.textContent === '+' && add.getAttribute('aria-label') === 'Add a comment on line 2'
      && getComputedStyle(add).opacity === '1' && add.getBoundingClientRect().width === 24, 'Hover exposes the shared blue add-comment button');
    const addBounds = add.getBoundingClientRect();
    assert(addBounds.left >= native.getBoundingClientRect().left && addBounds.right <= native.getBoundingClientRect().left + code.getLayoutInfo().contentLeft,
      'The add button stays in the gutter, clear of code');
    mouse('mousemove', 3, {}, true); await settle();
    assert(add.getAttribute('aria-label') === 'Add a comment on line 3', 'The add button follows the hovered line');
    const overlay = document.createElement('div'); host.append(overlay);
    overlay.onmousemove = event => event.stopPropagation();
    overlay.dispatchEvent(new MouseEvent('mousemove', { bubbles: true })); await settle();
    assert(getComputedStyle(add).display === 'none', 'Moving into an overlay hides the button even if it stops mouse propagation');
    overlay.remove(); mouse('mousemove', 2, {}, true); await settle();
    window.dispatchEvent(new Event('blur')); await settle();
    assert(getComputedStyle(add).display === 'none', 'Leaving the window clears the hover button');
    mouse('mousemove', 2, {}, true); await settle();
    await click(2);
    assert(getComputedStyle(add).display === 'none', 'Opening a review card clears the old line hover');
    assert(heading() === 'Add a comment on line R2', 'Clicking a line number opens the shared private composer');
    const draft = input(); assert(draft !== null, 'Private composer must be editable');
    changeInput(draft, 'Please explain these lines'); await settle();
    await click(4, { shiftKey: true });
    assert(heading() === 'Add a comment on lines R2 to R4' && input()?.value === 'Please explain these lines', 'Shift-click extends the range and preserves the draft');
    assert(![...host.querySelectorAll('button')].some(button => button.textContent === 'Post now'), 'A private task must not offer GitHub publication');
    const send = [...host.querySelectorAll('button')].find(button => button.textContent === 'Send to agent');
    assert(send !== undefined && !send.disabled, 'The private send action must be available');
    flushSync(() => send.click()); await settle();
    assert(sent.length === 1 && sent[0].comment.startLine === 2 && sent[0].comment.line === 4
      && sent[0].comment.path === '/comments.txt' && sent[0].code === 'line 2\nline 3\nline 4', 'Sending retains the selected file, lines and code');
    assert(input() === null, 'Successful private submission closes the composer');
    mouse('mousedown', 5); await settle();
    assert(input() === null, 'Wait until release before opening a drag composer');
    mouse('mousemove', 3, { buttons: 1 }, true); mouse('mouseup', 3, {}, true); await settle();
    assert(getComputedStyle(add).display === 'none', 'Dragging away from the starting line must not leave its plus visible');
    assert(heading() === 'Add a comment on lines R3 to R5', 'Dragging backwards from the gutter across source selects the full range');
    assert(code.getModel()?.getAllDecorations().some(item => item.options.className === 'ide-comment-selection'
      && item.range.startLineNumber === 3 && item.range.endLineNumber === 5), 'The selected review range is highlighted');
    await cancel();
    mouse('mousemove', 2, {}, true); await settle();
    flushSync(() => add.click()); await settle();
    assert(heading() === 'Add a comment on line R2', 'Keyboard activation of the add button opens the same composer');
    await cancel();
    mouse('mousedown', 3); mouse('mousemove', 5, { buttons: 1 }); mouse('mousemove', 3, { buttons: 1 }); mouse('mouseup', 3); await settle();
    assert(heading() === 'Add a comment on line R3', 'Dragging back to the anchor shrinks the range');
    await cancel();
    mouse('mousedown', 3); mouse('mousemove', 4, { buttons: 1 });
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    mouse('mouseup', 4); await settle(); assert(input() === null, 'Escape cancels an in-progress range');
    code.updateOptions({ lineNumbers: line => `<span class="ide-blame-line">Author</span><span class="ide-blame-number">${line}</span>`, lineNumbersMinChars: 20 });
    code.render(true); await settle();
    const blame = element('.ide-blame-line', native, 'Blame label must render');
    const rect = blame.getBoundingClientRect();
    blame.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, clientX: rect.left + 2, clientY: rect.top + 2 }));
    window.dispatchEvent(new MouseEvent('mouseup', { bubbles: true })); await settle();
    assert(input() === null, 'Blame labels retain their history action');
    await click(2); assert(heading() === 'Add a comment on line R2', 'Line numbers remain commentable beside blame');
    await cancel();
    code.setModel(other); render(); code.render(true); await settle();
    await click(6, { shiftKey: true });
    assert(heading() === 'Add a comment on line R6', 'Switching files reattaches gestures without extending the previous file range');
    await cancel();
    enabled = false; render(); await click(2); assert(input() === null, 'Unavailable comments remove the gesture');

    enabled = true; code.setModel(model); code.updateOptions({ lineNumbers: 'on', lineNumbersMinChars: 5 }); code.setScrollTop(0);
    const thread: LocalReviewThreadDto = { id: 'saved', taskId: 'task', taskVersion: 1, workspaceId: 'workspace', repo: 'owner/repo', number: 0,
      path: '/comments.txt', side: 'RIGHT', startLine: null, line: 4, baseSha: null, headSha: 'head', code: 'line 4', resolved: false, version: 1,
      createdAt: '', updatedAt: '', messages: [{ id: 'message', author: 'USER', body: 'Why is this implementation different? Please explain the tradeoff.',
        createdAt: '', turnId: null, queuedInputId: null, messageEventId: null, deliveryState: null, deliveryError: null }] };
    options.localReview.threads = [thread]; render(); await settle(); await settle();
    const card = element<HTMLElement>('.lrc', native, 'Saved private threads use the existing local review card');
    assert(card.getBoundingClientRect().width <= 780 && getComputedStyle(card).marginLeft === '0px', 'Inline cards use the shared width and alignment');
    const referenceHost = document.createElement('div'); document.body.append(referenceHost);
    const reference = createRoot(referenceHost);
    try {
      flushSync(() => reference.render(<LocalReviewThreadCard thread={thread} {...options.localReview} showCode={false} />));
      const themes = await getService(IWorkbenchThemeService);
      for (const mode of ['dark', 'light']) {
        const theme = (await themes.getColorThemes()).find(theme => theme.settingsId === (mode === 'dark' ? 'ByteQuay Darcula' : 'ByteQuay IDEA Light'));
        assert(theme !== undefined, 'Workbench theme must exist'); await themes.setColorTheme(theme.id, undefined);
        host.classList.toggle('is-dark', mode === 'dark'); document.documentElement.style.colorScheme = mode; await settle();
        const standard = element<HTMLElement>('.lrc', referenceHost, 'Reference card must render');
        const header = element('.lrc__head', card, 'Inline card header must render');
        const standardHeader = element('.lrc__head', standard, 'Reference card header must render');
        assert(getComputedStyle(card).backgroundColor === getComputedStyle(standard).backgroundColor
          && getComputedStyle(card).borderColor === getComputedStyle(standard).borderColor
          && getComputedStyle(header).backgroundColor === getComputedStyle(standardHeader).backgroundColor,
        'The IDE must reuse the standard local card palette in both themes');
        mouse('mousemove', 3, {}, true); await settle();
        Object.assign(window, { reviewCapture: mode });
        for (let attempt = 0; attempt < 100 && Reflect.get(window, 'reviewCaptured') !== mode; attempt++) await new Promise(resolve => setTimeout(resolve, 50));
        assert(Reflect.get(window, 'reviewCaptured') === mode, 'Capture the comment appearance');
      }
    } finally { reference.unmount(); referenceHost.remove(); }
    const checkPosition = () => {
      const line = code.getScrolledVisiblePosition({ lineNumber: 4, column: 1 }); assert(line !== null, 'Anchor line is visible');
      assert(Math.abs(card.getBoundingClientRect().top - (native.getBoundingClientRect().top + line.top + line.height + 5)) <= 1,
        'The card must stay immediately below its source line across native layout passes');
    };
    checkPosition();
    code.layout({ width: 550, height: 500 }); await settle(); await settle(); checkPosition();
    assert(card.getBoundingClientRect().right <= native.getBoundingClientRect().left + 550, 'The card shrinks with a narrow editor');
    const zone = card.closest<HTMLElement>('.ide-review-zone'); assert(zone !== null, 'Card overlay exists');
    code.setScrollTop(1400); await settle();
    assert(getComputedStyle(zone).visibility === 'hidden', 'Offscreen cards must not leave a blank overlay across source');
    code.setScrollTop(0); code.layout({ width: 1050, height: 650 }); await settle(); await settle(); checkPosition();
    code.setModel(other); render(); await settle();
    assert(native.querySelector('.ide-review-zone') === null, 'Switching to a file without comments removes the old overlays');
  } finally {
    flushSync(() => root.unmount()); code.dispose(); model.dispose(); other.dispose(); host.remove();
  }
}
