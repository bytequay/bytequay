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
import CredentialsTab from '../../CredentialsTab';
import AiSkillsTab from '../../AiSkillsTab';
import ComingSoon from '../shared/ComingSoon';

type Tab = 'credentials' | 'skills' | 'usage';

/**
 * The "AI" settings surface per docs/mockups/design/tasks/settings-ai-skills.png.
 * One page in the sidebar hosts three inner tabs:
 *  • Credentials — provider connections + defaults (the existing CredentialsTab)
 *  • Skills — reusable system-prompt fragments, scoped global / per-repo /
 *    per-domain (the focus of the redesign)
 *  • Usage — placeholder for the spending-cap + per-provider call ledger view
 *
 * The old "Automation" tab from the previous AI-review surface was rolled
 * into other places (per-thread settings, workspace defaults) and is gone
 * from the mockup, so it's gone here too.
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
          </div>
        </div>
      </div>

      <div className="settings-page-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'credentials'}
          className={`settings-page-tab${tab === 'credentials' ? ' settings-page-tab--active' : ''}`}
          onClick={() => setTab('credentials')}
        >
          Credentials
        </button>
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

      {tab === 'credentials' && <CredentialsTab filterType="AI" />}
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
