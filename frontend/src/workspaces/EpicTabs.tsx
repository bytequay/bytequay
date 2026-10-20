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

import React from 'react';
import type { WorkItemDto } from '../workitems/contracts';
import { summarize } from '../workitems/model';
import { EpicGlyph } from './EpicDrawer';
import { epicWork } from './epics';
import '../css/epic-view.css';

/** The Work tab's done/total, as the drawer card counts it; null before the Epic has work. */
export function workProgress(work: WorkItemDto[], epicId: string): string | null {
  const summary = summarize({ items: epicWork(work, epicId) }, new Set());
  return summary.total === 0 ? null : `${summary.finished}/${summary.total}`;
}

/** An Epic's issue or ticket and its Work, switched in place the way a pull request's tabs are. */
export function EpicTabs({ source, progress, work, onWork }: {
  source: React.ReactNode;
  progress: string | null;
  work: boolean;
  onWork: (work: boolean) => void;
}): React.JSX.Element {
  return (
    <nav className="pull-detail__tabs epic-tabs" aria-label="Epic views">
      <button type="button" className={`pull-detail__pr-tab is-open${work ? '' : ' is-active'}`} aria-pressed={!work} onClick={() => onWork(false)}>{source}</button>
      <button type="button" className={work ? 'is-active' : ''} aria-pressed={work} onClick={() => onWork(true)}>
        <EpicGlyph /> Work{progress !== null && <span className="epic-tabs__progress">{progress}</span>}
      </button>
    </nav>
  );
}
