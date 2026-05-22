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
import ReviewSkillsTab from '../../ReviewSkillsTab';
import ComingSoon from '../shared/ComingSoon';
import AutomationTab from './AutomationTab';

type Tab = 'credentials' | 'skills' | 'automation' | 'usage';

function AiReviewPage() {
  const [tab, setTab] = useState<Tab>('credentials');

  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">AI review</h2>
          <div className="settings-shell-page__subtitle">
            Connect a model provider and configure what the AI does when it reviews a PR.
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
          Review skills
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'automation'}
          className={`settings-page-tab${tab === 'automation' ? ' settings-page-tab--active' : ''}`}
          onClick={() => setTab('automation')}
        >
          Automation
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
      {tab === 'skills' && <ReviewSkillsTab />}
      {tab === 'automation' && <AutomationTab />}
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
