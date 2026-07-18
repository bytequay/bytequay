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
import PullOverview from './PullOverview';

/**
 * The unified PR detail pane (header + Overview tab) from the redesign
 * prototype — the pane container, drag handle, and width state stay in
 * PullsScreen; this renders the content column. Mount with `key={row.id}`
 * so switching PRs resets the sub-tab and composer draft.
 */

const branchChipStyle = { fontFamily: "'SF Mono',ui-monospace,Menlo,monospace", fontSize: 12, color: '#0969da', background: '#ddf4ff', borderRadius: 6, padding: '3px 10px' } as const;
const statePillStyle = { display: 'inline-flex', alignItems: 'center', gap: 6, color: '#fff', fontSize: 12.5, fontWeight: 600, borderRadius: 999, padding: '5px 13px' } as const;
const tabBtnStyle = { display: 'inline-flex', alignItems: 'center', gap: 7, padding: '6px 4px 10px', border: 0, background: 'transparent', fontSize: 13, cursor: 'pointer' } as const;

function AgentButtons({ det, repo }: { det: ReturnType<typeof buildHeader>; repo: string }) {
  // agent actions wire up with the agent-column work
  const noop = () => {};
  if (!det.agentAssigned) {
    return (
      <button className="pl-hov-btn" onClick={noop} title="Assign an agent" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '4px 9px', marginBottom: 4, border: '1px solid #d5dbe1', background: '#fff', borderRadius: 8, color: '#454c54', cursor: 'pointer', flexShrink: 0 }}>
        <span style={{ color: '#8b5cf6', display: 'inline-flex' }}><RobotIcon size={14} /></span>
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 5v14" /><path d="M5 12h14" /></svg>
      </button>
    );
  }
  return (
    <>
      <button className="pl-hov-agent" onClick={noop} title={det.agentTitle} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, padding: '4px 9px', marginBottom: 4, border: '1px solid rgba(139,92,246,0.35)', background: 'rgba(139,92,246,0.08)', borderRadius: 8, color: '#7c3aed', cursor: 'pointer', flexShrink: 0 }}>
        <RobotIcon size={14} />
        {det.agentRunning
          ? <span style={{ width: 7, height: 7, borderRadius: '50%', background: '#2da44e', animation: 'pl-pulse 1.4s ease-in-out infinite' }} />
          : <span style={{ width: 7, height: 7, borderRadius: '50%', background: '#b6bcc2' }} />}
      </button>
      <button className="pl-hov-btn" onClick={noop} title={`Open in workspace — locate this review task in the ${repo.split('/')[1] ?? repo} trunk`} style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '4px 9px', marginBottom: 4, border: '1px solid #d5dbe1', background: '#fff', borderRadius: 8, color: '#454c54', cursor: 'pointer', flexShrink: 0 }}>
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="7" height="7" rx="1.6" /><rect x="14" y="3" width="7" height="7" rx="1.6" /><rect x="3" y="14" width="7" height="7" rx="1.6" /><rect x="14" y="14" width="7" height="7" rx="1.6" /></svg>
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M7 17 17 7" /><path d="M9 7h8v8" /></svg>
      </button>
    </>
  );
}

export default function PullDetailPane({ row }: { row: PullRow }) {
  const { bundle, refresh } = usePR(row.dto.id);
  const [subTab, setSubTab] = useState<'overview' | 'changes'>('overview');
  const det = buildHeader(row, bundle);
  const isOverview = subTab === 'overview';

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
        <div style={{ maxWidth: 880, margin: '0 auto', padding: '18px 36px 0' }}>
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
            <span style={{ fontSize: 21, fontWeight: 600, lineHeight: 1.3, letterSpacing: '-0.01em', color: '#17191c', minWidth: 0, flex: 1 }}>
              {det.title} <span style={{ fontWeight: 300, color: '#8b949e' }}>{det.numS}</span>
            </span>
            <span
              className="pl-hov-ic"
              title="Copy title"
              onClick={() => { void navigator.clipboard.writeText(`${det.title} #${row.num}`); }}
              style={{ width: 28, height: 28, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', borderRadius: 7, color: '#8b949e', flexShrink: 0, marginTop: 2 }}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="9" y="9" width="12" height="12" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" /></svg>
            </span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9, flexWrap: 'wrap', marginTop: 12 }}>
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
          <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginTop: 6 }}>
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
            <span style={{ flex: 1 }} />
            <AgentButtons det={det} repo={row.repo} />
          </div>
        </div>
      </div>

      {isOverview ? (
        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
          <div style={{ maxWidth: 880, margin: '0 auto', padding: '20px 36px 60px' }}>
            <PullOverview row={row} bundle={bundle} isMerged={det.isMerged} onComment={onComment} />
          </div>
        </div>
      ) : (
        <div style={{ flex: 1, minHeight: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 13, color: '#8b949e' }}>
          Changes view coming with the diff work
        </div>
      )}
    </div>
  );
}
