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
import type { WorkspaceSection } from './WorkspaceShell';

type Props = {
  /** Lets the "View all →" / "Open →" affordances on the placeholder
   *  jump straight to the matching section. */
  onSelectSection: (section: WorkspaceSection) => void;
};

/** Phase 6 commit 1 placeholder. The cards-and-summary layout
 *  (Active threads / Tasks in flight / Memory excerpt + topline)
 *  lands in commit 2. */
function WorkspaceHomePage({ onSelectSection }: Props) {
  return (
    <>
      <header className="workspace-pageheader">
        <div>
          <h1 className="workspace-pageheader__title">ByteQuay</h1>
          <div className="workspace-pageheader__meta">
            workspace overview · home page lands in the next commit
          </div>
        </div>
        <button type="button" className="workspace-pageheader__action" disabled>
          + New thread
        </button>
      </header>
      <div className="workspace-placeholder">
        Home page is the next commit — Active threads card, Tasks in flight,
        and a Memory excerpt with the budget bar. For now, jump straight to{' '}
        <button
          type="button"
          className="workspace-card__link"
          onClick={() => onSelectSection('memory')}
        >
          Memory →
        </button>{' '}
        or{' '}
        <button
          type="button"
          className="workspace-card__link"
          onClick={() => onSelectSection('threads')}
        >
          Threads →
        </button>
        .
      </div>
    </>
  );
}

export default WorkspaceHomePage;
