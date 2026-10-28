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
import { relativeTime } from '@bytequay/ui/relativeTime';
import { RefreshCw } from 'lucide-react';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import MarkdownBody from '../pulls/MarkdownBody';
import { remoteErrorMessage } from '../remoteError';
import type { HostingPullRequestSnapshotDto, HostingPullRequestSummaryDto, WorkspaceDto } from '../types';
import { useZoom, ZoomActions, ZoomScrim } from '../Zoom';

export default function HostingRequestOverview({ workspace, number, summary, onClose }: {
  workspace: WorkspaceDto;
  number: number;
  summary?: HostingPullRequestSummaryDto;
  onClose: () => void;
}) {
  const [snapshot, setSnapshot] = useState<HostingPullRequestSnapshotDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const layer = useRef<HTMLElement>(null);
  const zoom = useZoom(onClose);
  useEffect(() => {
    const previous = document.activeElement;
    layer.current?.focus();
    return () => { if (previous instanceof HTMLElement && previous.isConnected) previous.focus(); };
  }, []);
  const id = useId();
  const refresh = useCallback(async () => {
    setRefreshing(true); setError(null);
    try { setSnapshot(await window.bridge.refreshWorkspaceHostingRequest(workspace.id, number)); }
    catch (failure) { setError(remoteErrorMessage(failure)); }
    finally { setRefreshing(false); }
  }, [number, workspace.id]);

  useEffect(() => {
    void window.bridge.fetchWorkspaceHostingRequest(workspace.id, number).then(setSnapshot)
      .catch(failure => setError(remoteErrorMessage(failure)))
      .finally(() => { setLoading(false); void refresh(); });
  }, [number, refresh, workspace.id]);

  const request = snapshot?.pullRequest ?? summary;
  const detail = snapshot?.detail;
  const url = detail?.htmlUrl ?? request?.htmlUrl;
  const resolveWebLink = useCallback((href: string) => {
    // GitLab expands upload paths within the project and may require its browser session.
    if (!url || /^\/?uploads\//.test(href)) return null;
    try {
      const destination = new URL(href, url);
      return /^https?:$/.test(destination.protocol) ? destination.href : null;
    }
    catch { return null; }
  }, [url]);
  const open = () => {
    if (url) void window.bridge.openInAppBrowser(url).catch(failure => setError(remoteErrorMessage(failure)));
  };
  return <>
    {!zoom.full && <ZoomScrim onClose={zoom.close} />}
    <section ref={layer} className={`${zoom.cardClass} hosting-request-detail`} role="dialog" aria-modal="true" aria-labelledby={`${id}-title`} data-navigation-layer tabIndex={-1}>
    <header className="hosting-request-detail__header">
      <span>{workspace.monitoredOwner}/{workspace.monitoredRepo} !{number}</span>
      <div className="hosting-request-detail__actions">
        <button type="button" className="bq-button" disabled={loading || refreshing} aria-busy={refreshing} onClick={() => { void refresh(); }}>
          <RefreshCw size={14} aria-hidden="true" />{refreshing ? 'Refreshing…' : 'Refresh'}
        </button>
        <button type="button" className="bq-button" disabled={!url} onClick={open}>Open in GitLab</button>
        <ZoomActions {...zoom} label="merge request details" className="bq-button" />
      </div>
    </header>
    <h2 id={`${id}-title`}>{detail?.title ?? request?.title ?? `Merge request !${number}`}</h2>
    {request && <p className="hosting-request-detail__status">
      {request.state === 'merged' ? 'Merged' : request.state === 'closed' ? 'Closed' : request.draft ? 'Draft' : 'Open'}
      {' · '}{request.author}{' · Updated '}{relativeTime(request.updatedAt)}
    </p>}
    {error !== null && <p className="pulls-sync-error" role="alert">{error}{snapshot !== null && ' Showing saved details.'}</p>}
    {snapshot === null && <p role="status">{loading || refreshing ? 'Loading details…' : `Details are not available. Try Refresh${url ? ' or open the merge request in GitLab' : ''}.`}</p>}
    {snapshot !== null && detail && <>
      <dl className="hosting-request-detail__metadata">
        <dt>Branches</dt><dd>{detail.headRef ?? 'Unknown'} → {detail.baseRef ?? 'Unknown'}</dd>
        <dt>Reviewers</dt><dd>{detail.requestedReviewerCount < 0 ? 'Unavailable' : detail.requestedReviewers.join(', ') || 'None'}</dd>
        <dt>Assignees</dt><dd>{detail.assignees.join(', ') || 'None'}</dd>
        {detail.milestone && <><dt>Milestone</dt><dd>{detail.milestone}</dd></>}
        {detail.labels.length > 0 && <><dt>Labels</dt><dd>{detail.labels.join(', ')}</dd></>}
      </dl>
      <section aria-label="Description" className="hosting-request-detail__description">
        <MarkdownBody body={detail.body || 'No description provided.'} resolveWebLink={resolveWebLink} />
      </section>
      <p className="hosting-request-detail__saved">Last checked {relativeTime(snapshot.validatedAt)}</p>
    </>}
    <p className="hosting-request-note">Use GitLab for diffs, discussions, checks and review actions.</p>
    </section>
  </>;
}
