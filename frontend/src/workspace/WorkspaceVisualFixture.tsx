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
import { useEffect, useState, type ReactNode } from 'react';
import { WorkspaceNavShell } from '../pages/WorkspaceNavShell';
import { TrunkPage } from '../pages/TrunkPage';
import type { TaskNavRow, WsNavKey } from '../ui/workspace';
import type { InboxItem } from '../home/inboxItems';
import InboxCard, { type InboxHandlers } from '../home/InboxCard';
import WorkspacesLandingPage from './WorkspacesLandingPage';
import WorkspaceBacklogPage from './WorkspaceBacklogPage';
import WorkspaceCreationToasts from './WorkspaceCreationToasts';
import WorkspaceInsightsPage from './WorkspaceInsightsPage';
import WorkspaceMemoryPage from './WorkspaceMemoryPage';
import WorkspaceNotificationsPage from './WorkspaceNotificationsPage';
import PullRequestBoardList from './PullRequestBoardList';
import WorkspaceRepoPage from './WorkspaceRepoPage';
import WorkspaceSessionsPage from './WorkspaceSessionsPage';
import WorkspaceSettingsPage from './WorkspaceSettingsPage';
import WorkspaceShell, { type WorkspaceSection } from './WorkspaceShell';
import {
  VISUAL_BACKLOG_KEY,
  VISUAL_BRANCH_NAME,
  VISUAL_DETAIL_PR_NUMBER,
  VISUAL_ISSUE_NUMBER,
  VISUAL_SESSION_ID,
  VISUAL_TRUNK_ID,
  VISUAL_WORKSPACE_ID,
  visualDashboardPrs,
  visualCreationReady,
  visualPullRequests,
  visualTasks,
  visualWorkspaces,
} from './workspaceVisualFixtureData';

type Props = {
  frame: string;
};

const STUDY_WIDTH: Record<string, number> = {
  '3a': 1060,
  '3b': 1060,
  '3c': 1060,
  '3d': 1060,
  '3e': 1060,
  '3f': 1060,
  '3g': 1060,
  '3h': 1060,
  '3i': 1060,
  '3j': 1060,
  '4a': 1060,
  '4b': 1060,
  '4c': 1060,
  '4d': 1060,
  '4e': 1060,
  '4f': 900,
  '5b': 460,
  '5c': 1060,
  '5d': 900,
  '5e': 1240,
  '5f': 460,
  '6b': 1100,
  '6c': 1060,
  '6d': 460,
};

const STUDY_HEIGHT: Record<string, number> = {
  '3a': 523,
  '3b': 457,
  '3c': 628.046875,
  '3d': 390.5,
  '3e': 409.5,
  '3f': 215.5,
  '3g': 297.5,
  '3h': 334.9453125,
  '3i': 390.5,
  '3j': 333,
  '4a': 395.6875,
  '4b': 246.5,
  '4c': 370,
  '4d': 340.5,
  '4e': 483.9453125,
  '4f': 640,
  '5b': 355.09375,
  '5c': 363,
  '5d': 660,
  '5e': 849.5,
  '5f': 206,
  '6b': 460.5,
  '6c': 566.5,
  '6d': 183,
};

/**
 * Development-only rendering catalog used by the dependency-free Electron
 * visual gate. Every entry mounts the real production component with the
 * static DTOs from the matching design frame; the archived HTML is never
 * mounted on the production side of a comparison.
 */
export default function WorkspaceVisualFixture({ frame }: Props) {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    const timer = window.setTimeout(() => {
      document.documentElement.dataset.workspaceVisualReady = 'true';
      setReady(true);
    }, frame === '6d' ? 1_250 : 450);
    return () => window.clearTimeout(timer);
  }, [frame]);

  const content = renderFrame(frame);
  const studyWidth = STUDY_WIDTH[frame];
  return (
    <div className={`workspace-visual-canvas workspace-redesign workspace-visual-frame-${frame}`}>
      {studyWidth === undefined ? content : (
        <div
          className="workspace-visual-study"
          style={{ width: studyWidth, height: STUDY_HEIGHT[frame] }}
        >
          {content}
        </div>
      )}
      <span data-workspace-visual-ready={ready ? 'true' : undefined} />
    </div>
  );
}

function renderFrame(frame: string): ReactNode {
  switch (frame) {
    case '1c':
      return <FullWorkspaceFrame section="trunks" activeNav="trunks" />;
    case '2a':
      return (
        <GlobalFrame activeNav="workspaces">
          <WorkspacesLandingPage
            currentWorkspaceId={VISUAL_WORKSPACE_ID}
            onEnterWorkspace={() => {}}
          />
        </GlobalFrame>
      );
    case '2b':
      return <FullWorkspaceFrame section="today" activeNav="today" />;
    case '3a':
      return (
        <PullRequestBoardList
          title="Pull requests"
          rows={visualPullRequests.slice(0, 6)}
          loading={false}
          error={null}
          showRepository={false}
          countOverride={{ review: 3, mine: 2, open: 7 }}
          onOpen={() => {}}
          onRefresh={() => {}}
        />
      );
    case '3b':
      return <RepoFrame section="issues" />;
    case '3c':
      return <RepoFrame section="issues" selectedNumber={VISUAL_ISSUE_NUMBER} />;
    case '3d':
      return (
        <WorkspaceSessionsPage
          workspaceId={VISUAL_WORKSPACE_ID}
          listPresentation="status"
          dailySpendOverride={1_400}
          dailyTokensOverride={96_000}
        />
      );
    case '4b':
      return (
        <WorkspaceSessionsPage
          workspaceId={VISUAL_WORKSPACE_ID}
          listPresentation="provider"
          featuredSessionIds={[
            VISUAL_SESSION_ID,
            'session-plan-done',
            'session-ci-error',
          ]}
          dailySpendOverride={1_400}
          showFilters={false}
        />
      );
    case '3e':
      return (
        <WorkspaceBacklogPage
          workspaceId={VISUAL_WORKSPACE_ID}
          threadNames={new Map([
            [VISUAL_TRUNK_ID, 'Codex v2'],
            ['trunk-clean-code', 'Clean code v2'],
          ])}
        />
      );
    case '3f':
      return <RepoFrame section="branches" />;
    case '3g':
    case '4a':
      return <RepoFrame section="commits" />;
    case '3h':
    case '4e':
    case '4f':
      return <WorkspaceMemoryPage workspaceId={VISUAL_WORKSPACE_ID} />;
    case '3i':
      return <div className="surface"><WorkspaceInsightsPage workspaceId={VISUAL_WORKSPACE_ID} /></div>;
    case '5f':
      return (
        <div className="surface">
          <WorkspaceInsightsPage workspaceId={VISUAL_WORKSPACE_ID} presentation="provider-card" />
        </div>
      );
    case '3j':
      return <WorkspaceNotificationsPage workspaceId={VISUAL_WORKSPACE_ID} />;
    case '4c':
      return (
        <PullRequestBoardList
          title="Pull requests"
          rows={visualPullRequests.slice(6)}
          loading={false}
          error={null}
          showRepository={false}
          initialView="list"
          initialFilter="mine"
          initialIncludeClosed
          onOpen={() => {}}
          onRefresh={() => {}}
        />
      );
    case '4d':
      return <RepoFrame section="branches" selectedBranch={VISUAL_BRANCH_NAME} />;
    case '5a':
      return (
        <GlobalFrame
          activeNav="trunks"
          activeWorkspace
          selectedThreadId={VISUAL_TRUNK_ID}
        >
          <TrunkPage
            threadId={VISUAL_TRUNK_ID}
            thread={{
              title: 'Codex v2',
              status: 'RUNNING',
              branch: VISUAL_BRANCH_NAME,
            }}
            conversation={<VisualTrunkConversation />}
            conversationFooter={<VisualTrunkComposer />}
            hideConversationPrompts
            composer={{
              value: '',
              onChange: () => {},
              onSubmit: () => {},
            }}
            tasks={{ active: [], closed: [] }}
            onOpenTask={() => {}}
          />
        </GlobalFrame>
      );
    case '5b':
      return <RepoFrame section="issues" selectedNumber={VISUAL_ISSUE_NUMBER} />;
    case '5c':
      return (
        <WorkspaceSessionsPage
          workspaceId={VISUAL_WORKSPACE_ID}
          selectedSessionId={VISUAL_SESSION_ID}
        />
      );
    case '5d':
      return (
        <WorkspaceBacklogPage
          workspaceId={VISUAL_WORKSPACE_ID}
          selectedKey={VISUAL_BACKLOG_KEY}
          threadNames={new Map([[VISUAL_TRUNK_ID, 'Codex v2']])}
        />
      );
    case '5e':
      return <RepoFrame section="pull-requests" selectedNumber={VISUAL_DETAIL_PR_NUMBER} />;
    case '6a':
      return <FullWorkspaceFrame section="today" activeNav="today" />;
    case '6b':
      return <InboxStudy />;
    case '6c':
      return (
        <WorkspaceSettingsPage
          workspace={visualWorkspaces[0]}
          workspaceId={VISUAL_WORKSPACE_ID}
          section="agents"
        />
      );
    case '6d':
      return <CreationToastStudy />;
    default:
      return <div className="wu-body-message">Unknown workspace visual frame {frame}</div>;
  }
}

function VisualTrunkConversation() {
  return (
    <div className="wu-trunk-reference-conversation">
      <div className="wu-trunk-reference-conversation__carryover">
        Conversation unchanged — carried over as-is
      </div>
      <div className="wu-trunk-reference-conversation__user">
        Let&apos;s replace the hand-rolled bounds checks with Math.clamp, and keep NaN behavior identical.
      </div>
      <div className="wu-trunk-reference-conversation__agent">
        <VisualAgentAvatar />
        <div className="wu-trunk-reference-conversation__bubble">
          Agreed — I scanned <code>functions/math</code> and found 4 call sites. Plan: swap to Math.clamp,
          add boundary tests, wire the suite into CI. Cutting task #14 for the first two steps.
        </div>
      </div>
      <div className="wu-trunk-reference-conversation__agent">
        <VisualAgentAvatar />
        <div className="wu-trunk-reference-conversation__bubble is-question">
          <span>
            Question before I touch serialization: keep the legacy field order in <code>toMessage</code>?
            Downstream consumers may rely on it.
          </span>
          <div className="wu-trunk-reference-conversation__answers">
            <button type="button">Keep order</button>
            <button type="button">Reorder freely</button>
          </div>
        </div>
      </div>
    </div>
  );
}

function VisualAgentAvatar() {
  return (
    <span className="wu-trunk-reference-conversation__avatar" aria-hidden>
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <rect x="5" y="9" width="14" height="10" rx="2" />
        <path d="M12 5v4" />
        <circle cx="12" cy="4" r="1" />
        <path d="M9 13.5h.01" />
        <path d="M15 13.5h.01" />
      </svg>
    </span>
  );
}

function VisualTrunkComposer() {
  return (
    <div className="wu-trunk-reference-composer">
      <div>Message the thread…</div>
      <button type="button">Send</button>
    </div>
  );
}

function GlobalFrame({
  children,
  activeNav,
  activeWorkspace = false,
  selectedThreadId,
}: {
  children: ReactNode;
  activeNav: WsNavKey;
  activeWorkspace?: boolean;
  selectedThreadId?: string;
}) {
  return (
    <div className="app-shell workspace-redesign workspace-visual-full-shell">
      <WorkspaceNavShell
        activeWorkspaceId={activeWorkspace ? VISUAL_WORKSPACE_ID : null}
        activeNav={activeNav}
        selectedThreadId={selectedThreadId}
        notificationCount={activeNav === 'workspaces' ? 28 : 8}
        tasks={selectedThreadId === VISUAL_TRUNK_ID ? visualTaskNavRows() : []}
        onNavigate={() => {}}
        onOpenThread={() => {}}
        onOpenTask={() => {}}
        onSwitchWorkspace={() => {}}
        onNewThread={() => {}}
      />
      <div className="app-content">{children}</div>
    </div>
  );
}

function visualTaskNavRows(): TaskNavRow[] {
  return visualTasks
    .filter(task => task.threadId === VISUAL_TRUNK_ID)
    .map(task => ({
      id: task.id,
      label: task.name ?? task.branchName,
      ...(task.prState === 'MERGED'
        ? { pr: 'merged' as const }
        : { dot: task.status === 'RUNNING' ? 'active' as const : 'sleep' as const }),
    }));
}

function FullWorkspaceFrame({
  section,
  activeNav,
}: {
  section: WorkspaceSection;
  activeNav: WsNavKey;
}) {
  return (
    <GlobalFrame activeNav={activeNav} activeWorkspace>
      <WorkspaceShell
        section={section}
        workspaceId={VISUAL_WORKSPACE_ID}
        onSelectSection={() => {}}
        threadsFilter="ALL"
        threadsProvider={null}
        threadsGroupId={null}
        threadsRepo={null}
        onThreadsFilterChange={() => {}}
        onThreadsProviderChange={() => {}}
        onThreadsGroupChange={() => {}}
        onThreadsRepoChange={() => {}}
        onOpenPr={() => {}}
        onOpenIssue={() => {}}
        onOpenBranch={() => {}}
        onOpenSession={() => {}}
        onOpenBacklog={() => {}}
        onOpenSettings={() => {}}
        immersive={false}
        onChangeImmersive={() => {}}
        hideRail
      />
    </GlobalFrame>
  );
}

function RepoFrame({
  section,
  selectedNumber,
  selectedBranch,
}: {
  section: 'pull-requests' | 'issues' | 'branches' | 'commits';
  selectedNumber?: number;
  selectedBranch?: string;
}) {
  return (
    <WorkspaceRepoPage
      workspaceId={VISUAL_WORKSPACE_ID}
      section={section}
      selectedNumber={selectedNumber}
      selectedBranch={selectedBranch}
      onOpenPr={() => {}}
      onOpenIssue={() => {}}
      onOpenBranch={() => {}}
      onOpenTrunk={() => {}}
      onBackToList={() => {}}
    />
  );
}

function InboxStudy() {
  const upstreamReview = visualDashboardPrs.find(value => value.number === 29586)!;
  const remoteReview = {
    ...upstreamReview,
    id: 'acme/widget#4062',
    repo: 'acme/widget',
    number: 4062,
    title: '[Cherry Pick] Skip Iceberg Glue column comment caching when content is invalid',
    updatedAt: new Date(Date.now() - 60 * 60_000).toISOString(),
  };
  const mention = {
    ...remoteReview,
    id: 'tuannvm/mcp-trino#88',
    repo: 'tuannvm/mcp-trino',
    number: 88,
  };
  const merged = {
    ...remoteReview,
    id: 'apache/gateway#30948',
    repo: 'apache/gateway',
    number: 30948,
  };
  const bytequay = {
    ...remoteReview,
    id: 'chenjian2664/ByteQuay#148',
    repo: 'chenjian2664/ByteQuay',
    number: 148,
  };
  const items: InboxItem[] = [
    {
      id: 'visual-review-trino',
      type: 'review',
      title: 'Review requested on #29586',
      sub: 'Fix Scan failure due to dropped column used in an equality delete',
      time: new Date(Date.now() - 17 * 60 * 60_000).toISOString(),
      read: false,
      source: { kind: 'pr', pr: upstreamReview },
    },
    {
      id: 'visual-review-remote',
      type: 'review',
      title: 'Review requested on #4062',
      sub: '[Cherry Pick] Skip Iceberg Glue column comment caching when content is invalid · acme/widget',
      time: remoteReview.updatedAt!,
      read: false,
      source: { kind: 'pr', pr: remoteReview },
    },
    {
      id: 'visual-question',
      type: 'info',
      title: 'Agent question in Codex v2',
      sub: '"Keep legacy field order in toMessage?" — session paused, waiting on you',
      time: new Date(Date.now() - 60 * 60_000).toISOString(),
      read: false,
      source: { kind: 'pr', pr: bytequay },
    },
    {
      id: 'visual-ci',
      type: 'blocked',
      title: `CI failed on ${VISUAL_BRANCH_NAME}`,
      sub: 'clamp boundary suite · 2 failures · task #14',
      time: new Date(Date.now() - 3 * 60 * 60_000).toISOString(),
      read: false,
      source: { kind: 'pr', pr: bytequay },
    },
    {
      id: 'visual-mention',
      type: 'mention',
      title: '@ebyhr mentioned you',
      sub: 'tuannvm/mcp-trino #88 — "curious how ByteQuay handles this"',
      time: new Date(Date.now() - 16 * 60 * 60_000).toISOString(),
      read: true,
      source: { kind: 'pr', pr: mention },
    },
    {
      id: 'visual-merged',
      type: 'done',
      title: '#30948 merged',
      sub: 'Add shared-credentials shared component',
      time: new Date(Date.now() - 6 * 60 * 60_000).toISOString(),
      read: true,
      source: { kind: 'pr', pr: merged },
    },
  ];
  const handlers: InboxHandlers = {
    openPr: () => {},
    openWorkspacePr: () => {},
    openRemoteReview: () => {},
    workspaceForRepo: (owner, repo) => {
      const fullName = `${owner}/${repo}`.toLowerCase();
      if (fullName === 'trinodb/trino') {
        return { workspaceId: 'workspace-trino', name: 'trino' };
      }
      if (fullName === 'chenjian2664/bytequay') {
        return { workspaceId: VISUAL_WORKSPACE_ID, name: 'bytequay' };
      }
      if (fullName === 'apache/gateway') {
        return { workspaceId: 'workspace-gateway', name: 'gateway' };
      }
      return null;
    },
    dismiss: () => {},
    approve: async () => {},
    resolved: () => {},
  };
  return (
    <div className="workspace-visual-inbox-study">
      <div className="workspace-visual-inbox-note">
        Home · contribution graph and review banner above, unchanged — this is the Inbox section
      </div>
      <div className="home-inbox">
        <div className="home-inbox__header">
          <div className="home-inbox__heading">
            <span className="home-inbox__title">Inbox</span>
            <span className="home-inbox__badge">56</span>
          </div>
          <div className="home-inbox__controls">
            <div role="button" tabIndex={0} className="home-inbox__filter home-inbox__filter--on">
              <span className="home-inbox__filter-dot" aria-hidden />
              Unread only
            </div>
            <a role="button" tabIndex={0} className="home-inbox__seeall">See all</a>
          </div>
        </div>
        <div className="home-inbox__list">
          {items.map(item => <InboxCard key={item.id} item={item} handlers={handlers} />)}
        </div>
      </div>
    </div>
  );
}

function CreationToastStudy() {
  useEffect(() => {
    const timer = window.setTimeout(() => {
      window.dispatchEvent(new CustomEvent('bytequay:workspace-creation-started', {
        detail: visualCreationReady,
      }));
    }, 50);
    return () => window.clearTimeout(timer);
  }, []);
  return (
    <div className="workspace-visual-toast-study">
      <WorkspaceCreationToasts onOpenWorkspace={() => {}} />
    </div>
  );
}
