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

import React, { useCallback, useContext, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { CircleAlert, ListChecks } from 'lucide-react';
import { Card, Pill } from '../cards/Card';
import { zoomReference } from '../githubReference';
import { linkIssueNumbers } from '../pulls/MarkdownBody';
import type { SpecCheckRequest } from '../repository-context/specChecks';
import { LayerCovered } from '../Zoom';
import type { GoalItemDto, GoalRecommendationDto } from './contracts';
import { GoalPanel, GoalRing, type GoalPanelView } from './GoalPanel';
import { GoalWindow } from './GoalWindow';
import { foldSide, placePill, preparedRequest, situation, summarize, type PillPosition } from './model';
import { useGoalConversation, type Goals } from './useGoals';
import '../css/goals.css';

const DRAG_THRESHOLD = 4;
const PILL_MARGIN = 8;
/** Cards float under the pill when it sits this far above the page's bottom, and above it otherwise. */
const ROOM_BELOW = 220;
const MAX_CARDS = 3;
const CARD_KINDS: Record<GoalRecommendationDto['card']['kind'], string> = { RESEARCH: 'Research', LESSON: 'Lesson', WORK: 'Practice', SPEC_CHECK: 'Spec check',
  INVESTIGATE: 'Investigation' };

function pillKey(page: string): string {
  return `bq.goal.pill.${page}`;
}

function loadPosition(page: string): PillPosition | null {
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(pillKey(page)) ?? 'null');
    if (typeof parsed !== 'object' || parsed === null) return null;
    const { right, bottom, fold } = parsed as Record<string, unknown>;
    if (typeof right !== 'number' || typeof bottom !== 'number') return null;
    return fold === 'left' || fold === 'right' ? { right, bottom, fold } : { right, bottom };
  }
  catch {
    return null;
  }
}

function saveLocal(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  }
  catch { /* A full or disabled store only means the default comes back next time. */ }
}

function editable(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && (target.isContentEditable || target.matches('input, textarea, select'));
}

/**
 * Opens a Jira ticket's development composer over the page, with an instruction the user reviews and sends; `replaces` starts
 * another attempt after that ended task.
 */
export function startTicket(reference: string, instruction: string, replaces?: string): void {
  const [connectionId, cloudId, issueId] = reference.split(':');
  zoomReference({ kind: 'jira', connectionId, cloudId, issueId, start: true, draft: instruction, replaces });
}

/** Opens a goal item where the app shows it: a pull request, issue or ticket over the page, a task as its screen. */
export function openGoalItem(item: GoalItemDto, workspaceId: string, onOpenTask: (taskId: string) => void): void {
  if (item.kind === 'TASK') {
    onOpenTask(item.key);
    return;
  }
  if (item.kind === 'JIRA') {
    const [connectionId, cloudId, issueId] = item.key.split(':');
    zoomReference({ kind: 'jira', connectionId, cloudId, issueId });
    return;
  }
  const hash = item.key.lastIndexOf('#');
  const repo = item.key.slice(0, hash);
  const number = Number(item.key.slice(hash + 1));
  zoomReference(item.kind === 'PULL_REQUEST' ? { kind: 'pull', repo, number, workspaceId } : { kind: 'issue', repo, number, workspaceId });
}

type Drag = { x: number; y: number; from: PillPosition; moved: boolean };

/**
 * The workspace's pinned goal around one of its pages: a pill the user can drag anywhere, which
 * zooms into a floating, draggable and resizable window. ⌥G opens and closes it.
 */
export function GoalLayer({ page, workspaceId, repository, goals, needsYou, onOpenTask, onStartIssue, onSpecCheck, children }: {
  /** Which workspace page this is; the pill remembers a place per page. */
  page: string;
  workspaceId: string;
  repository: string;
  goals: Goals;
  needsYou: ReadonlySet<string>;
  onOpenTask: (taskId: string) => void;
  /** Opens the issue's task composer with an instruction the user reviews and sends. */
  onStartIssue: (issue: number, instruction: string, replaces?: string) => void;
  /** Shows a module's spec check on the Memory page, a new one filled with a card's choices. */
  onSpecCheck: (spec: SpecCheckRequest) => void;
  children: React.ReactNode;
}): React.JSX.Element {
  const covered = useContext(LayerCovered);
  const pageRef = useRef<HTMLDivElement>(null);
  const pillRef = useRef<HTMLButtonElement>(null);
  const [opened, setOpened] = useState(false);
  const [visited, setVisited] = useState(false);
  const [saved, setSaved] = useState<PillPosition | null>(() => loadPosition(page));
  const [position, setPosition] = useState<PillPosition>({ right: 24, bottom: 24 });
  const [drag, setDrag] = useState<Drag | null>(null);
  const [view, setView] = useState<GoalPanelView>('guide');
  // The lesson open in the Goal window, if any.
  const [lesson, setLesson] = useState<string | null>(null);
  // New goal asks what the user wants to achieve over the current goal, which stays pinned until an answer starts the next.
  const [drafting, setDrafting] = useState(false);
  const dragged = useRef(false);
  // The last spot a drag reached, which a release can report before the move that set it renders.
  const dropped = useRef<PillPosition | null>(null);
  const dragging = drag !== null;

  useEffect(() => {
    setSaved(loadPosition(page));
    setOpened(false);
    setVisited(false);
    setLesson(null);
    setDrafting(false);
  }, [page, workspaceId]);

  // Placed again whenever the page or its composer changes size, so a growing draft never slides under the pill.
  useLayoutEffect(() => {
    const element = pageRef.current;
    if (element === null || dragging) return;
    const composer = element.querySelector('.agt-dock__inner');
    const place = (): void => {
      const pill = pillRef.current;
      if (pill === null) return;
      const bounds = element.getBoundingClientRect();
      const edges = composer?.getBoundingClientRect() ?? null;
      setPosition(placePill(saved, { width: pill.offsetWidth, height: pill.offsetHeight }, { width: bounds.width, height: bounds.height },
        edges === null ? null : { left: edges.left - bounds.left, top: edges.top - bounds.top, right: edges.right - bounds.left, bottom: edges.bottom - bounds.top }));
    };
    place();
    const observer = new ResizeObserver(place);
    observer.observe(element);
    if (composer !== null) observer.observe(composer);
    return () => observer.disconnect();
  }, [dragging, page, saved, goals.pinned?.id]);

  const open = useCallback(() => { setVisited(true); setOpened(true); }, []);
  const close = useCallback(() => setOpened(false), []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (covered || event.defaultPrevented || !event.altKey || event.metaKey || event.ctrlKey || event.code !== 'KeyG' || editable(event.target)) return;
      event.preventDefault();
      if (!opened) open();
      else close();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [close, covered, opened, open]);

  const goal = goals.pinned;
  const shownGoal = drafting ? null : goal;
  const cards = goal?.recommendations ?? [];
  const conversation = useGoalConversation(goals, shownGoal?.id ?? null, visited, cards.map((card) => `${card.id}:${card.state}`).join(','));
  const shown = cards.filter((card) => card.state === 'SHOWN').slice(0, MAX_CARDS);
  const panel = (full: boolean, toggle: () => void, onClose: () => void) => (
    <GoalPanel
      mode={full ? 'board' : 'window'}
      view={view}
      onView={setView}
      lesson={lesson}
      onLesson={setLesson}
      conversation={conversation}
      onStart={(operationId, message) => goals.start(operationId, message).then(() => setDrafting(false))}
      repository={repository}
      goals={goals.goals}
      goal={shownGoal}
      needsYou={needsYou}
      onClose={onClose}
      onExpand={toggle}
      onSwitch={(goalId) => { setDrafting(false); void goals.pin(goalId).catch(() => {}); }}
      onNew={() => { setLesson(null); setView('guide'); setDrafting(true); }}
      onBack={drafting && goal !== null ? () => setDrafting(false) : undefined}
      onCreate={(title) => goals.create(title).then((created) => goals.pin(created.id))}
      onRename={goals.rename}
      onSummarize={(item) => (item.goalId === null ? Promise.resolve() : goals.summarize(item.goalId, item.id))}
      onPause={goals.pause}
      onDelete={(goalId) => goals.remove(goalId)}
      onAdd={(item) => (goal === null ? Promise.resolve() : goals.add(goal.id, item.kind, item.key))}
      workItems={goals.workItems}
      onCreateWork={goals.createWork}
      onDecide={goals.decideWork}
      onOrder={goals.orderWork}
      onLink={goals.linkWork}
      onOpenItem={(item) => openGoalItem(item, workspaceId, onOpenTask)}
      onOpenTask={onOpenTask}
      onStartIssue={onStartIssue}
      onStartTicket={startTicket}
      onSpecCheck={onSpecCheck}
      onFileIssue={(draft) => goals.fileIssue(repository, draft)}
      search={goals.search}
    />
  );
  const summary = goal === null ? null : summarize(goal, needsYou);
  // The pinned goal's own conversation, unless the window shows another goal's question; its decisions only count once it was read.
  const decisions = goal === null || drafting ? 0 : situation(goal, conversation.exchanges, conversation.gaps).decisions;
  const needs = summary?.needs ?? 0;
  const folded = position.fold !== undefined;
  // A folded tab hides the cards, so its count holds them too.
  const hidden = folded && goal !== null ? shown.length : 0;
  const attention = needs + decisions + hidden;

  return (
    <div className="goal-layer">
      <div className="goal-layer__page" ref={pageRef}>
        {children}
        <button
          ref={pillRef}
          type="button"
          className={`goal-pill${drag?.moved ? ' is-dragging' : ''}${folded ? ` is-folded is-${position.fold}` : ''}`}
          style={folded ? { bottom: position.bottom } : { right: position.right, bottom: position.bottom }}
          title={folded ? 'Drag out to unfold · click to open · ⌥G' : 'Drag to pin anywhere, or into a side to fold · click to open · ⌥G'}
          aria-label={goal === null ? 'Open goals' : `Open goal: ${goal.title}`}
          aria-expanded={opened}
          aria-haspopup="dialog"
          onPointerDown={(event) => {
            if (event.button !== 0) return;
            dragged.current = false;
            event.currentTarget.setPointerCapture(event.pointerId);
            setDrag({ x: event.clientX, y: event.clientY, from: position, moved: false });
          }}
          onPointerMove={(event) => {
            const element = pageRef.current;
            if (drag === null || element === null) return;
            const dx = event.clientX - drag.x;
            const dy = event.clientY - drag.y;
            if (!drag.moved && Math.abs(dx) + Math.abs(dy) < DRAG_THRESHOLD) return;
            const pill = event.currentTarget;
            const clamp = (value: number, max: number) => Math.round(Math.max(PILL_MARGIN, Math.min(max, value)));
            if (!drag.moved) setDrag({ ...drag, moved: true });
            dropped.current = {
              right: clamp(drag.from.right - dx, element.clientWidth - pill.offsetWidth - PILL_MARGIN),
              bottom: clamp(drag.from.bottom - dy, element.clientHeight - pill.offsetHeight - PILL_MARGIN),
            };
            setPosition(dropped.current);
          }}
          onPointerUp={(event) => {
            const spot = dropped.current;
            const element = pageRef.current;
            dropped.current = null;
            if (drag?.moved && spot !== null && element !== null) {
              // The click that follows a drop is not an open. A drop against a side leaves the pointer off the pill, where no click follows,
              // so the guard lasts only until the events of this release are done.
              dragged.current = true;
              window.setTimeout(() => { dragged.current = false; }, 0);
              // Dropped against a side of the page, the pill folds into it there, and unfolds at that side when dragged out.
              const side = foldSide(event.clientX, element.getBoundingClientRect());
              const kept: PillPosition = side === null ? spot
                : { right: side === 'left' ? element.clientWidth - event.currentTarget.offsetWidth - PILL_MARGIN : PILL_MARGIN, bottom: spot.bottom, fold: side };
              saveLocal(pillKey(page), JSON.stringify(kept));
              setSaved(kept);
              setPosition(kept);
            }
            setDrag(null);
          }}
          onPointerCancel={() => setDrag(null)}
          onClick={() => {
            if (dragged.current) {
              dragged.current = false;
              return;
            }
            if (opened) close();
            else open();
          }}
        >
          {!folded && <svg className="goal-pill__grip" width="8" height="14" viewBox="0 0 8 14" aria-hidden="true">
            {[3, 7, 11].flatMap((y) => [2, 6].map((x) => <circle key={`${x}.${y}`} cx={x} cy={y} r="1.1" />))}
          </svg>}
          <GoalRing goal={goal} />
          {!folded && <span className="goal-pill__title">{goal === null ? 'Goal' : goal.title}</span>}
          {!folded && summary !== null && summary.total > 0 && <span className="goal-mono">{summary.finished}/{summary.total}</span>}
          {/* One count asks for attention: the work needing the user and the decisions waiting, both named on hover. */}
          {attention > 0 && <span className="goal-pill__attention" title={[needs === 0 ? null : needs === 1 ? 'One item needs you' : `${needs} items need you`,
            decisions === 0 ? null : decisions === 1 ? 'A decision waits for you' : `${decisions} decisions wait for you`,
            hidden === 0 ? null : hidden === 1 ? 'A recommendation waits' : `${hidden} recommendations wait`].filter((part) => part !== null).join(' · ')}>
            {attention}</span>}
        </button>
        {goal !== null && shown.length > 0 && !folded && (
          <div className="goal-cards" role="region" aria-label="Recommended next steps"
            style={{ ...({ '--goal-pill-right': `${position.right}px` } as React.CSSProperties),
              ...(position.bottom > ROOM_BELOW ? { top: `calc(100% - ${position.bottom - 6}px)` } : { bottom: position.bottom + 44 }) }}>
            {shown.map((card) => <RecommendationCard key={card.id} card={card} repository={repository} onOpen={() => { setDrafting(false); open(); }}
              onStart={() => (card.card.workItemId !== null ? goals.openLesson(goal.id, card.card.workItemId).then(() => {
                // Start on a lesson card opens the lesson in the Goal window.
                setLesson(card.card.workItemId);
                setView('lessons');
                setDrafting(false);
                open();
                goals.refresh();
              }) : goals.startCard(goal.id, card.id).then((started) => {
                // A work card's task starts only when the user sends the composer it opens, prepared from the work the card became; an
                // investigation shows in the Goal window.
                const request = preparedRequest(goal, goal.items.find((item) => item.id === started.workItemId) ?? null, card.card);
                if (card.card.issue !== null) onStartIssue(card.card.issue, request);
                else if (card.card.ticket !== null) startTicket(card.card.ticket, request);
                else if (card.card.kind === 'INVESTIGATE') {
                  setView('guide');
                  setDrafting(false);
                  open();
                }
              }))}
              onAdjust={card.card.module === null ? undefined
                : () => onSpecCheck({ directory: card.card.module, prefill: { specs: card.card.pages, reproductions: card.card.reproductions ?? 3 } })}
              onDismiss={() => goals.dismissCard(goal.id, card.id)} />)}
          </div>
        )}
        {visited && <GoalWindow anchor={pillRef} open={opened} onClose={close}>
          {({ full, toggle, close: closeWindow }) => panel(full, toggle, closeWindow)}
        </GoalWindow>}
      </div>
    </div>
  );
}

/**
 * A next step the agent recommends; Start begins it and Not now dismisses it. Opening the card shows the goal's guidance. A spec check
 * can also be adjusted in the Spec check drawer before it starts.
 */
function RecommendationCard({ card, repository, onOpen, onStart, onAdjust, onDismiss }: {
  card: GoalRecommendationDto;
  repository: string;
  onOpen: () => void;
  onStart: () => Promise<unknown>;
  onAdjust?: () => void;
  onDismiss: () => Promise<unknown>;
}): React.JSX.Element {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const act = (event: React.MouseEvent, action: () => Promise<unknown>): void => {
    event.stopPropagation();
    setBusy(true);
    setError(null);
    void action().catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'The card could not be updated')).finally(() => setBusy(false));
  };
  const kind = CARD_KINDS[card.card.kind];
  return (
    <Card compact className="goal-rec" label={`${kind}: ${card.card.title}`}
      title={card.card.criteria ? `${card.card.scope}\n\nDone when: ${card.card.criteria}` : card.card.scope} onOpen={onOpen}>
      <Pill>{kind}</Pill>
      <div className="goal-rec__title">{linkIssueNumbers(card.card.title, repository)}</div>
      {/* Both run long; each keeps a few lines and the whole text on hover. */}
      <div className="goal-rec__reason" title={card.card.reason}>{linkIssueNumbers(card.card.reason, repository)}</div>
      {card.card.uncertainty && <div className="goal-rec__doubt" title={card.card.uncertainty}>
        <CircleAlert size={13} aria-hidden="true" /><span>{linkIssueNumbers(card.card.uncertainty, repository)}</span></div>}
      {card.card.prerequisites && <div className="goal-rec__first" title={`First: ${card.card.prerequisites}`}>
        <ListChecks size={13} aria-hidden="true" /><span>{linkIssueNumbers(card.card.prerequisites, repository)}</span></div>}
      {error !== null && <div className="goal-rec__error" role="alert">{error}</div>}
      <div className="goal-rec__actions">
        <button type="button" className="goal-add" disabled={busy} onClick={(event) => act(event, onStart)}>Start</button>
        {onAdjust !== undefined && <button type="button" className="goal-link" disabled={busy} onClick={(event) => { event.stopPropagation(); onAdjust(); }}>Adjust</button>}
        <button type="button" className="goal-link is-muted" disabled={busy} onClick={(event) => act(event, onDismiss)}>Not now</button>
      </div>
    </Card>
  );
}
