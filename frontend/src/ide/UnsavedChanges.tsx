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
import type { IdeUnsavedChanges } from './contracts';

export default function UnsavedChanges({ files, drafts }: IdeUnsavedChanges) {
  return <>
    {files.length > 0 && <>
      <p>These files have unsaved edits in Tears of time:</p>
      <ul>{files.map(path => <li key={path}><code>{path}</code></li>)}</ul>
    </>}
    {drafts > 0 && <p>{drafts} unsent text {drafts === 1 ? 'draft remains' : 'drafts remain'} in Tears of time.</p>}
  </>;
}
