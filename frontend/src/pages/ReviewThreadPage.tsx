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
import type { ReactNode } from 'react';
import { IconBtn, Pill } from '../ui/primitives';
import { Composer, Grow, Main, NavArrows, Shell, TopBar, TopBarButton, TopBarTitle } from '../ui/shell';
import { RightPane } from '../ui/pane';

/**
 * The standalone PR-review thread surface (frame 5) — used when reviewing
 * someone else's PR. The sidebar starts collapsed to give the review prose
 * room; the top bar carries the Copilot-green Submit-review CTA as the
 * terminal action. The conversation (the agent's Thought / Callout review
 * notes) and the PR tab are supplied as slots.
 */
export function ReviewThreadPage({
  thread, sidebar, conversation, prTab, collapsed = true, composer,
  onBack, onSubmitReview, submitting = false,
}: {
  thread: { title: string };
  sidebar: ReactNode;
  conversation: ReactNode;
  prTab: ReactNode;
  collapsed?: boolean;
  composer: {
    value: string;
    onChange: (next: string) => void;
    onSubmit: () => void;
    busy?: boolean;
    modePill?: ReactNode;
    placeholder?: string;
  };
  onBack?: () => void;
  onSubmitReview?: () => void;
  submitting?: boolean;
}) {
  const [paneOpen, setPaneOpen] = useState(true);

  const topBar = (
    <TopBar>
      <NavArrows onBack={onBack} />
      <Pill kind="thread" icon="💭">REVIEW</Pill>
      <TopBarTitle>{thread.title}</TopBarTitle>
      <Grow />
      {onSubmitReview !== undefined && (
        <TopBarButton variant="submit" icon="✓" onClick={submitting ? undefined : onSubmitReview}>
          {submitting ? 'Submitting…' : 'Submit review'}
        </TopBarButton>
      )}
      <IconBtn active={paneOpen} ariaLabel="Toggle right pane" onClick={() => setPaneOpen(o => !o)}>◧</IconBtn>
    </TopBar>
  );

  return (
    <Shell collapsed={collapsed}>
      {sidebar}
      <Main topBar={topBar}>
        <div className={paneOpen ? 'body with-pane' : 'body'}>
          <div className="conv-col">
            {conversation}
            <Composer
              value={composer.value}
              onChange={composer.onChange}
              onSubmit={composer.onSubmit}
              busy={composer.busy}
              modePill={composer.modePill}
              placeholder={composer.placeholder}
            />
          </div>
          {paneOpen && (
            <RightPane>
              <RightPane.Tabs<'pr'> tabs={[{ key: 'pr', label: 'PR' }]} active="pr" onSelect={() => {}} />
              <RightPane.Content>{prTab}</RightPane.Content>
            </RightPane>
          )}
        </div>
      </Main>
    </Shell>
  );
}
