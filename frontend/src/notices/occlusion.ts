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

/** Native notice views sit above DOM dialogs, so CSS z-index cannot protect their controls. */
export function observeNoticeOcclusion(report: (obscured: boolean) => void): () => void {
  let previous: boolean | undefined;
  const update = () => {
    const obscured = [...document.querySelectorAll('dialog[open], [aria-modal="true"]')].some(dialog => dialog.checkVisibility());
    if (obscured !== previous) { previous = obscured; report(obscured); }
  };
  const observer = new MutationObserver(update);
  observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'hidden', 'open', 'style', 'aria-modal'] });
  window.addEventListener('resize', update);
  update();
  return () => { observer.disconnect(); window.removeEventListener('resize', update); };
}
