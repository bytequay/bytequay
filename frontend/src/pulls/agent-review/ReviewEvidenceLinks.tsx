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
import type { ReactNode } from 'react';

export const evidenceTarget = (runId: string, reference: string) => `review-evidence-${runId}-${reference}`;

export function ReviewEvidenceLink({ target, children }: { target: string; children: ReactNode }) {
  return <a className="agr-context__link" href={`#${encodeURIComponent(target)}`} onClick={event => {
    event.preventDefault();
    const element = document.getElementById(target);
    if (element === null) return;
    if (element instanceof HTMLDetailsElement) element.open = true;
    // Finding cards contain their own disclosure; evidence items live inside folded groups.
    element.querySelectorAll('details').forEach(details => { details.open = true; });
    for (let parent = element.parentElement; parent !== null; parent = parent.parentElement) {
      if (parent instanceof HTMLDetailsElement) parent.open = true;
    }
    element.tabIndex = -1;
    element.focus({ preventScroll: true });
    element.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }}>{children}</a>;
}
