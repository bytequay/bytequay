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

/** Phase 6 commit 1 placeholder. KPI cards + spend chart +
 *  tasks-shipped per-repo land in commit 3 with placeholder data
 *  (zeros / static) — real aggregation queries are a follow-up. */
function WorkspaceInsightsPage() {
  return (
    <>
      <header className="workspace-pageheader">
        <div>
          <h1 className="workspace-pageheader__title">Insights</h1>
          <div className="workspace-pageheader__meta">
            workspace insights · placeholder until commit 3
          </div>
        </div>
      </header>
      <div className="workspace-placeholder">
        Insights page is the third Phase-6 commit — KPI cards (active threads,
        tasks in flight, repos, spend) plus a 7-day spend chart and the
        per-repo tasks-shipped breakdown. Numbers will be placeholder until
        backend aggregation queries land.
      </div>
    </>
  );
}

export default WorkspaceInsightsPage;
