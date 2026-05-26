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
import ComingSoon from '../shared/ComingSoon';

// decision pending: model/provider selection moves to the config
// cascade. The Credentials and Skills inner tabs used to live here;
// each moved to its own top-level Settings section. Active-provider/
// active-model selection belongs to a later config-cascade page and
// isn't rebuilt on this surface.

/**
 * The "AI" settings surface. Reduced to Usage now that Credentials
 * and Skills each have their own sections in the sidebar; left in
 * place so existing deep-links keep landing somewhere and the Usage
 * placeholder has a home.
 */
function AiReviewPage() {
  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">AI</h2>
          <div className="settings-shell-page__subtitle">
            Provider keys live under Settings → Credentials. Reusable
            skills live under Settings → Skills. This page holds the
            spend / call ledger view (placeholder for now).
          </div>
        </div>
      </div>

      <ComingSoon
        title="Usage"
        description="Calls used this month, per-provider breakdown, and a configurable cap."
      />
    </>
  );
}

export default AiReviewPage;
