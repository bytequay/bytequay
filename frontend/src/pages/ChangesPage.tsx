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
import {
  BackBtn, Grow, Main, Shell, TopBar, TopBarButton, TopBarTitle, usePersistentToggle,
} from '../ui/shell';
import { CommitsDropdown, FullPageConv } from '../ui/fullpage';
import type { CommitOption } from '../ui/fullpage';

/**
 * The Changes full-page view (frame 4). The sidebar auto-collapses to its
 * rail to give the diff room; the body is three columns — the kept-visible
 * conversation (composer pinned), a hideable file tree, and the diff. The
 * commit scope is a top-bar dropdown rather than a column, and the
 * file-tree-hidden preference persists per user. The file tree + diff are
 * supplied as slots (the existing diff renderer, wired to real data).
 */
export function ChangesPage({
  title = 'Changes', sidebar, conversation, composer, fileTree, diff,
  commits = [], selectedCommit = null, onSelectCommit, fileCount,
  onBack, onOpenGitHub,
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
  fileTree: ReactNode;
  diff: ReactNode;
  commits?: CommitOption[];
  selectedCommit?: string | null;
  onSelectCommit?: (sha: string | null) => void;
  /** Count shown in the Files column header. */
  fileCount?: number;
  onBack?: () => void;
  onOpenGitHub?: () => void;
}) {
  const filesHidden = usePersistentToggle('v3.changes.filesHidden', false);

  const topBar = (
    <TopBar>
      <BackBtn onClick={onBack} />
      <TopBarTitle>{title}</TopBarTitle>
      <CommitsDropdown commits={commits} selected={selectedCommit} onSelect={onSelectCommit ?? (() => {})} />
      <Grow />
      <TopBarButton onClick={filesHidden.toggle}>
        {filesHidden.value ? 'Show file tree' : 'Hide file tree'}
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
        <div className={filesHidden.value ? 'changes-body no-files' : 'changes-body'}>
          <FullPageConv conversation={conversation} composer={composer} />
          {!filesHidden.value && (
            <div className="fp-col">
              <div className="fp-col-h">
                Files
                {fileCount !== undefined && <span className="count">{fileCount}</span>}
              </div>
              <div className="fp-col-body">{fileTree}</div>
            </div>
          )}
          <div className="fp-col">
            <div className="fp-col-body fp-diff-body">{diff}</div>
          </div>
        </div>
      </Main>
    </Shell>
  );
}
