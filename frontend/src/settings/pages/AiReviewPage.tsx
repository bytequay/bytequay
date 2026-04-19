import { useState } from 'react';
import CredentialsTab from '../../CredentialsTab';
import ComingSoon from '../shared/ComingSoon';

type Tab = 'credentials' | 'skills' | 'usage';

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
          aria-selected={tab === 'usage'}
          className={`settings-page-tab${tab === 'usage' ? ' settings-page-tab--active' : ''}`}
          onClick={() => setTab('usage')}
        >
          Usage
        </button>
      </div>

      {tab === 'credentials' && <CredentialsTab filterType="AI" />}
      {tab === 'skills' && (
        <ComingSoon
          title="Review skills"
          description="Always-on and opt-in skills that shape what the AI looks for. Lands with the AI revamp."
        />
      )}
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
