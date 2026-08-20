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
import { useEffect, useState } from 'react';
import AppSidebar, { type AppPage } from './AppSidebar';
import CherryPickRangeScreen from './cherrypick/CherryPickRangeScreen';
import HomePage from './home/HomePage';
import InAppBrowser from './InAppBrowser';
import LogoLoading from './LogoLoading';
import OnboardingScreen from './OnboardingScreen';
import PullsScreen from './pulls/PullsScreen';
import SettingsShell from './settings/SettingsShell';

type Status = 'checking' | 'needs-pat' | 'ready';

export default function App() {
  const [status, setStatus] = useState<Status>('checking');
  const [page, setPage] = useState<AppPage>('home');
  const [initialPr, setInitialPr] = useState<{ repo: string; number: number } | undefined>();
  const [browserUrl, setBrowserUrl] = useState<string | null>(null);
  const [fullScreen, setFullScreen] = useState(false);

  useEffect(() => {
    void window.bridge.hasPat().then(hasPat => setStatus(hasPat ? 'ready' : 'needs-pat'));
    const stopBrowser = window.bridge.onInAppOpenRequest(({ url }) => setBrowserUrl(url));
    const stopFullScreen = window.bridge.onFullScreenChange(setFullScreen);
    void window.bridge.getFullScreen().then(setFullScreen);
    return () => {
      stopBrowser();
      stopFullScreen();
    };
  }, []);

  if (status === 'checking') return <LogoLoading />;
  if (status === 'needs-pat') {
    return <OnboardingScreen onSaved={() => setStatus('ready')} />;
  }

  const openPr = (owner: string, repo: string, number?: number) => {
    setInitialPr(number === undefined ? undefined : { repo: `${owner}/${repo}`, number });
    setPage('pulls');
  };

  return (
    <div className="app-shell">
      <AppSidebar page={page} onNavigate={setPage} />
      <main className="app-content">
        {page === 'home' && (
          <HomePage
            onSelectRepo={openPr}
            onGoToMyPrs={() => {
              setInitialPr(undefined);
              setPage('pulls');
            }}
          />
        )}
        {page === 'pulls' && <PullsScreen initialPr={initialPr} />}
        {page === 'cherry-pick' && <CherryPickRangeScreen />}
        {page === 'settings' && <SettingsShell />}
      </main>
      {browserUrl !== null && (
        <InAppBrowser url={browserUrl} fullScreen={fullScreen} onClose={() => setBrowserUrl(null)} />
      )}
    </div>
  );
}
