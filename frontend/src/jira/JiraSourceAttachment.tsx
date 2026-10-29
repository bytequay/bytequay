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
import { ExternalLink } from 'lucide-react';
import { zoomReference } from '../githubReference';
import type { JiraTaskSource } from './contracts';
import { JiraIcon } from '@bytequay/jira/renderer';

type Source = Pick<JiraTaskSource, 'connectionId' | 'cloudId' | 'issueId' | 'key' | 'title'>;
export function JiraSourceAttachment({ source }: { source: Source }) {
  const open = () => zoomReference({ kind: 'jira', connectionId: source.connectionId, cloudId: source.cloudId, issueId: source.issueId });
  return <div className="agt-issue-attachment">
    <button type="button" className="agt-issue-attachment__source" aria-label={`View ticket ${source.key}`} onClick={open}>
      <JiraIcon size={15} aria-hidden="true" /><strong>{source.key}</strong><span>{source.title}</span>
    </button>
    <button type="button" className="agt-headbtn" title="View ticket" aria-label={`View ticket ${source.key}`} onClick={open}><ExternalLink size={14} /></button>
  </div>;
}
export function JiraRequestCard({ source, attachments }: { source: JiraTaskSource; attachments?: ReactNode }) {
  return <div className="agt-issue-request"><div className="agt-user__request">
    <JiraIcon aria-hidden="true" /><span className="agt-user__request-action">Start development</span>
    <span className="agt-user__request-head" title={source.title}>{source.key}</span>
  </div>{attachments}</div>;
}
