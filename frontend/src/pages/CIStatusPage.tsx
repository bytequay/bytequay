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
import type { ReactNode } from 'react';
import { BackBtn, Grow, Main, Shell, TopBar, TopBarButton, TopBarTitle, usePersistentToggle } from '../ui/shell';
import { CIPanel, FullPageConv } from '../ui/fullpage';
import type { CIIterationGroup, CICheck } from '../ui/fullpage';

/**
 * The CI Status full-page view (frame 8). Like Changes, the sidebar
 * collapses to its rail and the conversation stays visible with the
 * composer pinned. The middle column is the hideable CI panel (current
 * run + fix-iteration history folders); the right column is the colorized
 * log for the selected run/iteration (supplied as a slot). The
 * hide-panel preference persists per user.
 */
export function CIStatusPage({
  title = 'CI Status', sidebar, conversation, composer, current, iterationGroups,
  selectedIterationId, onSelectIteration, log, onBack, onOpenGitHub,
}: {
  title?: string;
  sidebar: ReactNode;
  conversation: ReactNode;
  composer: {
    value: string;
    onChange: (next: string) => void;
    onSubmit: () => void;
    busy?: boolean;
    modePill?: ReactNode;
    placeholder?: string;
  };
  current: { title: ReactNode; runId?: string; statusLine?: ReactNode; checks: CICheck[] };
  iterationGroups: CIIterationGroup[];
  selectedIterationId?: string;
  onSelectIteration?: (id: string) => void;
  /** The colorized log block for the selected run (the {@code <CILogView>}). */
  log: ReactNode;
  onBack?: () => void;
  onOpenGitHub?: () => void;
}) {
  const panelHidden = usePersistentToggle('v3.ci.panelHidden', false);

  const topBar = (
    <TopBar>
      <BackBtn onClick={onBack} />
      <TopBarTitle>{title}</TopBarTitle>
      <Grow />
      <TopBarButton onClick={panelHidden.toggle}>
        {panelHidden.value ? 'Show CI panel' : 'Hide CI panel'}
      </TopBarButton>
      {onOpenGitHub !== undefined && (
        <TopBarButton icon="⊕" chev onClick={onOpenGitHub}>Open on GitHub</TopBarButton>
      )}
    </TopBar>
  );

  return (
    <Shell collapsed>
      {sidebar}
      <Main topBar={topBar}>
        <div className={panelHidden.value ? 'ci-body no-panel' : 'ci-body'}>
          <FullPageConv conversation={conversation} composer={composer} />
          {!panelHidden.value && (
            <div className="fp-col">
              <CIPanel
                current={current}
                groups={iterationGroups}
                selectedIterationId={selectedIterationId}
                onSelectIteration={onSelectIteration}
              />
            </div>
          )}
          <div className="fp-col">
            <div className="ci-log-col">{log}</div>
          </div>
        </div>
      </Main>
    </Shell>
  );
}
