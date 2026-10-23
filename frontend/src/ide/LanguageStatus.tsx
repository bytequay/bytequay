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
import { progressOf, useBusyStatus, useSearchWaiting } from './statusEntries';

/** Background language service work, such as project import, and what a waiting search waits for. */
export default function LanguageStatus() {
  const status = useBusyStatus();
  const waiting = useSearchWaiting();
  if (!status && !waiting) return null;
  // Indexing is not reported, so a search can wait with no named work.
  const task = status?.replace(/\s*\d{1,3}%\s*/, ' ').trim();
  const text = !waiting ? status : task ? `Waiting for "${task}" to complete…` : 'Waiting for the language service…';
  return <span className="ide-language-status" title={text}>
    <span>{text}</span>
    <progress max={100} value={progressOf(status)} aria-label="Language service progress" />
  </span>;
}
