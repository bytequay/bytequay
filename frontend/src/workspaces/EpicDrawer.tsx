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

import React, { useCallback, useContext, useEffect, useId, useMemo, useRef, useState } from 'react';
import { ListTree, Plus, X } from 'lucide-react';
import { Card, CardAge, IconButton, LinkKey, LocalBadge, Pill } from '../cards/Card';
import type { WorkItemDto } from '../workitems/contracts';
import { progressWidths, summarize } from '../workitems/model';
import { statusPill } from '../home/attention';
import type { JiraIssue } from '../jira/contracts';
import { LayerCovered } from '../Zoom';
import { epicIdentity, epicWork, type EpicItem } from './epics';
import '../css/epic-drawer.css';

const OPEN_DELAY_MS = 150;
const GRACE_MS = 300;
/** The retract's length; coming back while it runs reverses it with no second delay. */
const CLOSE_MS = 180;
const MAX_TICKS = 4;

/**
 * Workspace Home's Epics in a drawer tucked under the sidebar's right hairline: one tick per Epic peeks out
 * collapsed, and hover, click or the keyboard slides it out over the page without moving anything. A
 * workspace without Epics shows one hollow tick that offers Create Epic instead.
 */
export default function EpicDrawer({ items, concluded, work, onOpen, onCreate }: {
  items: EpicItem[];
  /** Concluded Epics, which leave the drawer. */
  concluded: ReadonlySet<string>;
  /** Every work item of the workspace; each card draws its Epic's progress from them. */
  work: WorkItemDto[];
  /** Opens the Epic's view, where its work is added and decided. */
  onOpen: (epic: EpicItem) => void;
  onCreate: () => void;
}): React.JSX.Element {
  const epics = useMemo(() => items.filter(item => item.isEpic === true && !concluded.has(epicIdentity(item))), [concluded, items]);
  // Unmounting at zero closes it, so a first Epic marked later fades its tick in closed.
  return epics.length === 0 ? <EmptyCue onCreate={onCreate} /> : <Drawer epics={epics} work={work} onOpen={onOpen} onCreate={onCreate} />;
}

/** No Epic yet: the pointer coming near the hollow tick shows Create Epic beside it, joined to the tick so reaching it never closes it. */
function EmptyCue({ onCreate }: { onCreate: () => void }): React.JSX.Element {
  return (
    <div className="epic-drawer">
      <button type="button" className="epic-cue is-empty" aria-label="Create Epic" onClick={onCreate}>
        <span className="epic-cue__ticks" aria-hidden="true"><span className="epic-cue__tick" /></span>
        <span className="epic-create" aria-hidden="true"><span className="epic-create__label"><Plus size={13} />Create Epic</span></span>
      </button>
    </div>
  );
}

function Drawer({ epics, work, onOpen, onCreate }: { epics: EpicItem[]; work: WorkItemDto[]; onOpen: (epic: EpicItem) => void; onCreate: () => void }): React.JSX.Element {
  const covered = useContext(LayerCovered);
  const panelId = useId();
  const layer = useRef<HTMLDivElement>(null);
  const cue = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  // Timers and listeners read these, so they live outside render state.
  const live = useRef({ open: false, pinned: false, hover: false, focus: false, closedAt: 0, openTimer: 0, closeTimer: 0 });

  const show = useCallback((pin: boolean) => {
    const state = live.current;
    window.clearTimeout(state.openTimer);
    window.clearTimeout(state.closeTimer);
    state.open = true;
    state.pinned ||= pin;
    setOpen(true);
  }, []);
  const hide = useCallback(() => {
    const state = live.current;
    window.clearTimeout(state.openTimer);
    window.clearTimeout(state.closeTimer);
    if (state.open) state.closedAt = Date.now();
    state.open = false;
    state.pinned = false;
    setOpen(false);
  }, []);
  const hideSoon = useCallback(() => {
    const state = live.current;
    window.clearTimeout(state.closeTimer);
    state.closeTimer = window.setTimeout(() => { if (!state.hover && !state.focus && !state.pinned) hide(); }, GRACE_MS);
  }, [hide]);
  const dismiss = (): void => {
    hide();
    cue.current?.focus();
  };

  // An issue or ticket opened from here, or any other layer over the page, puts the drawer away.
  useEffect(() => { if (covered) hide(); }, [covered, hide]);
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent): void => { if (!(event.target instanceof Node && layer.current?.contains(event.target))) hide(); };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [hide, open]);
  useEffect(() => {
    const state = live.current;
    return () => {
      window.clearTimeout(state.openTimer);
      window.clearTimeout(state.closeTimer);
    };
  }, []);

  const label = `Epics · ${epics.length}`;
  return (
    <div
      ref={layer}
      className={`epic-drawer${open ? ' is-open' : ''}`}
      onMouseEnter={() => {
        const state = live.current;
        window.clearTimeout(state.closeTimer);
        state.hover = true;
        if (state.open) return;
        window.clearTimeout(state.openTimer);
        if (Date.now() - state.closedAt < CLOSE_MS) show(false);
        else state.openTimer = window.setTimeout(() => show(false), OPEN_DELAY_MS);
      }}
      onMouseLeave={() => {
        const state = live.current;
        // A pointer sweeping past the edge never opens it.
        window.clearTimeout(state.openTimer);
        state.hover = false;
        if (state.open && !state.pinned) hideSoon();
      }}
      onFocus={() => {
        window.clearTimeout(live.current.closeTimer);
        live.current.focus = true;
      }}
      onBlur={(event) => {
        const state = live.current;
        if (event.relatedTarget instanceof Node && layer.current?.contains(event.relatedTarget)) return;
        state.focus = false;
        if (state.open && !state.hover && !state.pinned) hideSoon();
      }}
      onKeyDown={(event) => {
        if (event.key !== 'Escape' || !live.current.open) return;
        event.stopPropagation();
        dismiss();
      }}
    >
      {/* Painted under the panel, so the trigger stays hoverable until the sliding panel covers it: no gap can close it. */}
      <button
        ref={cue}
        type="button"
        className="epic-cue"
        aria-label={label}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => {
          const state = live.current;
          if (!state.open) show(true);
          else if (!state.pinned) state.pinned = true;
          else hide();
        }}
      >
        <span className="epic-cue__ticks" aria-hidden="true">
          {epics.slice(0, MAX_TICKS).map(item => <span key={'acceptanceCriteria' in item ? epicIdentity(item) : item.key} className="epic-cue__tick" />)}
        </span>
      </button>
      <span className="epic-tip" aria-hidden="true">{label}</span>
      <section id={panelId} className="epic-drawer__panel" aria-label={label}>
        <div className="epic-drawer__head">
          <EpicGlyph />
          <span>Epics</span>
          <Pill>{epics.length}</Pill>
          <IconButton label="Create Epic" title="Create Epic" onClick={() => { dismiss(); onCreate(); }}><Plus size={14} aria-hidden="true" /></IconButton>
          <IconButton label="Close Epics" title="Close (Esc)" onClick={dismiss}><X size={14} aria-hidden="true" /></IconButton>
        </div>
        <div className="epic-drawer__list">
          {epics.map(item => <EpicCard key={'acceptanceCriteria' in item ? epicIdentity(item) : item.key} item={item} work={work} onOpen={() => onOpen(item)} />)}
        </div>
      </section>
    </div>
  );
}

/** One Epic; it opens the Epic's view and never runs. */
function EpicCard({ item, work, onOpen }: { item: EpicItem; work: WorkItemDto[]; onOpen: () => void }): React.JSX.Element {
  const source = 'acceptanceCriteria' in item ? null : item;
  const jira = source?.jira;
  // The workspace's issue cache holds open issues only.
  const status = jira ? statusPill(jira.status) : { name: 'Open', tone: 'green' as const };
  const name = source === null ? 'Local' : jira ? jira.key : `#${source.number}`;
  const summary = summarize({ items: epicWork(work, epicIdentity(item)) }, new Set());
  const widths = progressWidths(summary);
  return (
    <Card compact className="epic-card" label={`Open the Epic ${name}: ${item.title}`} title={item.title} onOpen={onOpen}>
      <span className="epic-card__title">{item.title}</span>
      {summary.total > 0 && (
        <span className="epic-card__progress" aria-hidden="true">
          <span className="is-done" style={{ width: widths.finished }} />
          <span className="is-working" style={{ width: widths.working }} />
        </span>
      )}
      <span className="epic-card__meta">
        {source === null ? <LocalBadge /> : <LinkKey source={jira ? 'jira' : 'issue'} name={name} size={13} />}
        <Pill tone={status.tone}>{status.name}</Pill>
        {jira?.hasChildren && <ChildCount ticket={jira} />}
        {summary.total > 0 && <span className="epic-card__done" title={`${summary.finished} of ${summary.total} done`}>{summary.finished}/{summary.total}</span>}
        <CardAge at={item.updatedAt} />
      </span>
    </Card>
  );
}

/** How many children a Jira Epic had when its detail was last read; the saved detail is read, never Jira. */
function ChildCount({ ticket }: { ticket: JiraIssue }): React.JSX.Element | null {
  const [count, setCount] = useState<string | null>(null);
  const { connectionId, cloudId, id, updatedAt } = ticket;
  useEffect(() => {
    let active = true;
    // A bridge without this read leaves the count out instead of breaking Home.
    void Promise.resolve().then(() => window.bridge.getJiraIssue({ connectionId, cloudId, issueId: id }))
      .then(detail => {
        if (active) setCount(detail.fetchedAt === null || detail.children.length === 0 ? null : `${detail.children.length}${detail.childrenTruncated ? '+' : ''}`);
      })
      .catch(() => {});
    return () => { active = false; };
  }, [cloudId, connectionId, id, updatedAt]);
  if (count === null) return null;
  return <span className="epic-card__children" title={`${count} child ${count === '1' ? 'item' : 'items'}`}><ListTree size={12} aria-hidden="true" />{count}</span>;
}

/** Stacked bars: the drawer's cards seen end-on, the same shape the edge ticks draw. */
export function EpicGlyph(): React.JSX.Element {
  return (
    <svg className="epic-drawer__glyph" width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <rect x="3" y="3" width="18" height="4.5" rx="1.5" />
      <rect x="5" y="9.75" width="14" height="4.5" rx="1.5" />
      <rect x="7" y="16.5" width="10" height="4.5" rx="1.5" />
    </svg>
  );
}
