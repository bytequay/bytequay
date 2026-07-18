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
import { usePR } from '../pr/usePR';
import { derivePRCapabilities } from '../pr/prCapabilities';
import { CommentBubbleIcon, PrMergedIcon, PrOpenIcon, RobotIcon } from './atoms';
import { buildHeader } from './detailModel';
import type { PullRow } from './model';
import PullChanges from './PullChanges';
import PullOverview from './PullOverview';

/**
 * The unified PR detail pane (header + Overview tab) from the redesign
 * prototype — the pane container, drag handle, and width state stay in
 * PullsScreen; this renders the content column. Mount with `key={row.id}`
 * so switching PRs resets the sub-tab and composer draft.
 */

const branchChipStyle = { fontFamily: "'SF Mono',ui-monospace,Menlo,monospace", fontSize: 12, color: '#0969da', background: '#ddf4ff', borderRadius: 6, padding: '3px 10px' } as const;
const statePillStyle = { display: 'inline-flex', alignItems: 'center', gap: 6, color: '#fff', fontSize: 12.5, fontWeight: 600, borderRadius: 999, padding: '5px 13px' } as const;
const tabBtnStyle = { display: 'inline-flex', alignItems: 'center', gap: 7, padding: '4px 4px 7px', border: 0, background: 'transparent', fontSize: 13, cursor: 'pointer' } as const;

type AgentActions = {
  /** Opens the agent-review column for an agent-assigned PR. */
  onWorkWithAgent?: () => void;
  /** Jumps to the repo's workspace PR surface. */
  onOpenInWorkspace?: () => void;
  /** Starts an agent review for a PR with no agent assigned yet. */
  onAssignAgent?: () => void;
  /** True when the host resolved the repo and found no workspace — the
   *  workspace-bound buttons stay rendered but inert with a hint title. */
  noWorkspace?: boolean;
};

const NO_WORKSPACE_TITLE = 'No workspace for this repo yet';

function AgentButtons({ det, repo, actions }: { det: ReturnType<typeof buildHeader>; repo: string; actions: AgentActions }) {
  const noop = () => {};
  if (!det.agentAssigned) {
    return (
      <button className="pl-hov-btn" onClick={actions.onAssignAgent ?? noop} title="Assign an agent" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '4px 9px', marginBottom: 4, border: '1px solid #d5dbe1', background: '#fff', borderRadius: 8, color: '#454c54', cursor: 'pointer', flexShrink: 0 }}>
        <span style={{ color: '#8b5cf6', display: 'inline-flex' }}><RobotIcon size={14} /></span>
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 5v14" /><path d="M5 12h14" /></svg>
      </button>
    );
  }
  return (
    <>
      <button className="pl-hov-agent" onClick={actions.onWorkWithAgent ?? noop} title={actions.noWorkspace === true ? NO_WORKSPACE_TITLE : det.agentTitle} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, padding: '4px 9px', marginBottom: 4, border: '1px solid rgba(139,92,246,0.35)', background: 'rgba(139,92,246,0.08)', borderRadius: 8, color: '#7c3aed', cursor: 'pointer', flexShrink: 0 }}>
        <RobotIcon size={14} />
        {det.agentRunning
          ? <span style={{ width: 7, height: 7, borderRadius: '50%', background: '#2da44e', animation: 'pl-pulse 1.4s ease-in-out infinite' }} />
          : <span style={{ width: 7, height: 7, borderRadius: '50%', background: '#b6bcc2' }} />}
      </button>
      <button className="pl-hov-btn" onClick={actions.onOpenInWorkspace ?? noop} title={actions.noWorkspace === true ? NO_WORKSPACE_TITLE : `Open in workspace — locate this review task in the ${repo.split('/')[1] ?? repo} trunk`} style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '4px 9px', marginBottom: 4, border: '1px solid #d5dbe1', background: '#fff', borderRadius: 8, color: '#454c54', cursor: 'pointer', flexShrink: 0 }}>
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="7" height="7" rx="1.6" /><rect x="14" y="3" width="7" height="7" rx="1.6" /><rect x="3" y="14" width="7" height="7" rx="1.6" /><rect x="14" y="14" width="7" height="7" rx="1.6" /></svg>
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M7 17 17 7" /><path d="M9 7h8v8" /></svg>
      </button>
    </>
  );
}

export default function PullDetailPane({ row, ...actions }: { row: PullRow } & AgentActions) {
  const { bundle, refresh } = usePR(row.dto.id);
  const [subTab, setSubTab] = useState<'overview' | 'changes'>('overview');
  const det = buildHeader(row, bundle);
  const isOverview = subTab === 'overview';
  const githubUrl = row.dto.htmlUrl
    || bundle?.pr.remotePrUrl
    || (bundle?.pr.remotePrNumber == null ? null : `https://github.com/${row.repo}/pull/${bundle.pr.remotePrNumber}`);

  // The same bridge decision PRView's hosts make (useExternalPrActions.
  // submitLocalComment): remote-capable PRs post straight to GitHub,
  // otherwise the comment is drafted locally.
  const onComment = bundle === null || bundle === undefined ? undefined : async (body: string) => {
    if (derivePRCapabilities(bundle.pr, 'details').postRemoteComment) {
      await window.bridge.postRemotePrComment(bundle.pr.id, body);
    }
    else {
      await window.bridge.addLocalPrComment(bundle.pr.id, { scope: 'pr', body });
    }
    refresh();
  };

  return (
    <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <div style={{ flexShrink: 0, borderBottom: '1px solid #e7e9ec', background: '#fff' }}>
        <div style={{ maxWidth: 1040, margin: '0 auto', padding: '10px 36px 0' }}>
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
            <span style={{ fontSize: 20, fontWeight: 500, lineHeight: 1.3, letterSpacing: '-0.01em', color: '#17191c', minWidth: 0, flex: 1 }}>
              {det.title}{' '}
              <span style={{ whiteSpace: 'nowrap' }}>
                <span style={{ fontWeight: 300, color: '#8b949e' }}>{det.numS}</span>
                <span
                  className="pl-hov-ic"
                  title="Copy title"
                  onClick={() => { void navigator.clipboard.writeText(`${det.title} #${row.num}`); }}
                  style={{ width: 24, height: 24, display: 'inline-flex', verticalAlign: 'middle', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', borderRadius: 7, color: '#8b949e', marginLeft: 4 }}
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="9" y="9" width="12" height="12" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" /></svg>
                </span>
              </span>
            </span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9, flexWrap: 'wrap', marginTop: 7 }}>
            {det.isMerged
              ? <span style={{ ...statePillStyle, background: '#8250df' }}><PrMergedIcon size={13} strokeWidth={2.2} />Merged</span>
              : <span style={{ ...statePillStyle, background: '#1f883d' }}><PrOpenIcon size={13} strokeWidth={2.2} />Open</span>}
            {det.base !== null && det.branch !== null && (
              <>
                <span style={branchChipStyle}>{det.base}</span>
                <span style={{ color: '#8b949e' }}>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M19 12H5" /><path d="m12 19-7-7 7-7" /></svg>
                </span>
                <span style={{ ...branchChipStyle, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 420 }}>{det.branch}</span>
              </>
            )}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginTop: 2 }}>
            <button onClick={() => setSubTab('overview')} style={{ ...tabBtnStyle, borderBottom: `2px solid ${isOverview ? '#c2632a' : 'transparent'}`, fontWeight: isOverview ? 600 : 500, color: isOverview ? '#17191c' : '#6e7781' }}>
              <CommentBubbleIcon size={14} />
              Overview
              <span style={{ fontSize: 10.5, fontWeight: 700, background: isOverview ? 'rgba(194,99,42,0.12)' : '#eceef0', color: isOverview ? '#c2632a' : '#59636e', borderRadius: 999, padding: '1px 7px' }}>{det.ovCount}</span>
            </button>
            <button onClick={() => setSubTab('changes')} style={{ ...tabBtnStyle, borderBottom: `2px solid ${!isOverview ? '#c2632a' : 'transparent'}`, fontWeight: !isOverview ? 600 : 500, color: !isOverview ? '#17191c' : '#6e7781' }}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M8 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h3" /><path d="M16 3h3a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-3" /><path d="M12 8v8" /><path d="M8 12h8" /></svg>
              Changes
              <span style={{ fontSize: 10.5, fontWeight: 700, background: '#dafbe1', color: '#1a7f37', borderRadius: 999, padding: '1px 7px' }}>{det.addP}</span>
              <span style={{ fontSize: 10.5, fontWeight: 700, background: '#ffebe9', color: '#cf222e', borderRadius: 999, padding: '1px 7px' }}>{det.delP}</span>
            </button>
            {githubUrl !== null && (
              <button
                type="button"
                className="pl-github-link"
                title="Open on GitHub"
                aria-label="Open pull request on GitHub"
                onClick={() => { void window.bridge.openInAppBrowser(githubUrl); }}
              >
                <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                  <path d="M8 0C3.58 0 0 3.64 0 8.13c0 3.59 2.29 6.63 5.47 7.71.4.08.55-.18.55-.39 0-.19-.01-.83-.01-1.51-2.01.38-2.53-.5-2.69-.96-.09-.23-.48-.96-.82-1.15-.28-.15-.68-.53-.01-.54.63-.01 1.08.59 1.23.83.72 1.23 1.87.88 2.33.67.07-.53.28-.88.51-1.08-1.78-.21-3.64-.91-3.64-4.02 0-.89.31-1.62.82-2.19-.08-.21-.36-1.04.08-2.16 0 0 .67-.22 2.2.84A7.4 7.4 0 0 1 8 3.91c.68 0 1.36.09 2 .27 1.53-1.06 2.2-.84 2.2-.84.44 1.12.16 1.95.08 2.16.51.57.82 1.3.82 2.19 0 3.12-1.87 3.81-3.65 4.02.29.25.54.74.54 1.51 0 1.09-.01 1.97-.01 2.24 0 .22.15.47.55.39A8.16 8.16 0 0 0 16 8.13C16 3.64 12.42 0 8 0Z" />
                </svg>
              </button>
            )}
            <span style={{ flex: 1 }} />
            <AgentButtons det={det} repo={row.repo} actions={actions} />
          </div>
        </div>
      </div>

      {isOverview ? (
        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
          <div style={{ maxWidth: 1040, margin: '0 auto', padding: '14px 36px 60px' }}>
            <PullOverview
              row={row}
              bundle={bundle}
              isMerged={det.isMerged}
              onComment={onComment}
              onDescriptionSaved={refresh}
            />
          </div>
        </div>
      ) : (
        <PullChanges row={row} bundle={bundle} refresh={refresh} onComment={onComment} />
      )}
    </div>
  );
}
