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
import { assert, settle } from './harness';
import '../css/base.css';
import '../css/window-drag.css';

export async function run(): Promise<boolean> {
  document.body.innerHTML = `<div class="app-shell"><aside class="app-sidebar"></aside><main class="app-content">
    <section style="height:100%; overflow:auto"><header style="padding:4px 12px">
      <button>Action</button><input aria-label="Search"><a href="#">Link</a><span role="button" tabindex="0">More</span>
    </header><div style="height:2000px">Scrollable page</div></section>
  </main></div>`;
  const content = document.querySelector<HTMLElement>('.app-content');
  const page = document.querySelector<HTMLElement>('section');
  const sidebar = document.querySelector<HTMLElement>('.app-sidebar');
  assert(content !== null && page !== null && sidebar !== null, 'Missing app shell');
  await settle();

  // A page with no known header class must get the same handle as existing pages.
  const handle = () => getComputedStyle(content, '::before');
  assert(handle().getPropertyValue('-webkit-app-region') === 'drag', 'Every page needs the shell window handle');
  assert(handle().height === '40px' && handle().top === '0px', 'The handle must cover the top window area');
  assert(Math.abs(Number.parseFloat(handle().width) - content.clientWidth) < 1, 'The handle must span the content width');
  assert(page.getBoundingClientRect().top === 0, 'The handle must not add a blank row to page layout');
  assert(handle().pointerEvents === 'none', 'The handle must not cover header controls');
  for (const control of page.querySelectorAll<HTMLElement>('button, input, a, [role="button"]')) {
    assert(getComputedStyle(control).getPropertyValue('-webkit-app-region') === 'no-drag', 'Header controls must remain interactive');
    const rect = control.getBoundingClientRect();
    assert(control.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2)), 'The handle must not intercept control hit testing');
  }

  page.scrollTop = 500;
  sidebar.classList.add('is-collapsed');
  await settle();
  assert(handle().top === '0px' && content.getBoundingClientRect().top === 0, 'Page scrolling must not move the handle');
  assert(Math.abs(Number.parseFloat(handle().width) - content.clientWidth) < 1, 'The handle must follow sidebar resizing');
  sidebar.remove();
  assert(handle().getPropertyValue('-webkit-app-region') === 'drag', 'Onboarding must retain its window handle');

  const dialog = document.createElement('dialog');
  dialog.style.cssText = 'margin:0; top:4px; left:12px';
  dialog.innerHTML = '<button>Save</button><input aria-label="Name">';
  document.body.append(dialog);
  dialog.showModal();
  assert(getComputedStyle(dialog).getPropertyValue('-webkit-app-region') === 'no-drag', 'Native modal content must exclude the underlying handle');
  assert(getComputedStyle(dialog, '::backdrop').getPropertyValue('-webkit-app-region') === 'no-drag', 'Native modal backdrops must exclude the underlying handle');
  const button = dialog.querySelector('button');
  assert(button !== null && getComputedStyle(button).getPropertyValue('-webkit-app-region') === 'no-drag', 'Modal controls must remain interactive');
  const rect = button.getBoundingClientRect();
  assert(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2) === button, 'The handle must not cover modal controls');
  dialog.close();
  dialog.remove();

  const overlay = document.createElement('div');
  overlay.innerHTML = '<div class="zoom-scrim"></div><section class="inapp-browser"><header class="inapp-browser__toolbar"><button>Back</button></header></section>';
  document.body.append(overlay);
  for (const element of overlay.querySelectorAll('.zoom-scrim, .inapp-browser, button')) {
    assert(getComputedStyle(element).getPropertyValue('-webkit-app-region') === 'no-drag', 'Overlaid surfaces and their controls must exclude the underlying handle');
  }
  const toolbar = overlay.querySelector('header');
  assert(toolbar !== null && getComputedStyle(toolbar).getPropertyValue('-webkit-app-region') === 'drag', 'The browser toolbar must retain its own handle');
  return true;
}
