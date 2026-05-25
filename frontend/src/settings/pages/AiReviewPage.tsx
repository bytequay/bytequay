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
import { useState } from 'react';
import AiSkillsTab from '../../AiSkillsTab';
import ComingSoon from '../shared/ComingSoon';

type Tab = 'skills' | 'usage';

// decision pending: model/provider selection moves to the config
// cascade. The Credentials inner tab and its inline provider/model
// pickers used to live here; that surface moved to Settings →
// Credentials. Active-provider/active-model selection belongs to a
// later config-cascade page and isn't rebuilt on this surface.

/**
 * The "AI" settings surface. Hosts Skills + Usage now that Credentials
 * has its own kind-navigated vault under Settings → Credentials.
 *
 * <p>Skills are reusable system-prompt fragments (global / per-repo /
 * per-domain). Usage is still a placeholder until the call-ledger view
 * lands.
 */
function AiReviewPage() {
  const [tab, setTab] = useState<Tab>('skills');

  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">AI</h2>
          <div className="settings-shell-page__subtitle">
            Skills are reusable instructions the AI loads alongside every prompt.
            Skills can be <strong>global</strong> (apply everywhere),{' '}
            <strong>per-repo</strong> (loaded when working in that repo's worktree),
            or <strong>per-domain</strong> (loaded when the AI is operating in a
            specific role — reviewer, reviewee, task scheduler, GitHub events, etc.).
            Provider keys live under Settings → Credentials.
          </div>
        </div>
      </div>

      <div className="settings-page-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'skills'}
          className={`settings-page-tab${tab === 'skills' ? ' settings-page-tab--active' : ''}`}
          onClick={() => setTab('skills')}
        >
          Skills
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'usage'}
          className={`settings-page-tab${tab === 'usage' ? ' settings-page-tab--active' : ''}`}
          onClick={() => setTab('usage')}
        >
          Usage
        </button>
      </div>

      {tab === 'skills' && <AiSkillsTab />}
      {tab === 'usage' && (
        <ComingSoon
          title="Usage"
          description="Calls used this month, per-provider breakdown, and a configurable cap."
        />
      )}
    </>
  );
}

export default AiReviewPage;
