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

import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Ban, ChevronDown, Maximize2, Minimize2, Pause, Play, Plus, RefreshCw, Search, X } from 'lucide-react';
import { ConfirmDialog } from '../ConfirmDialog';
import { GoalIcon, SourceIcon } from '../cards/Card';
import MarkdownBody from '../pulls/MarkdownBody';
import { relativeTime } from '../relativeTime';
import type {
  GoalCourseDraftDto, GoalDto, GoalExchangeDto, GoalGapDto, GoalItemDto, GoalKnowledgeNoteDto, GoalLessonDto, GoalProposalDto, GoalRecommendationDto, GoalTopicDto, TopicLevel, WorkItemDto, WorkItemDecision,
  CreateWorkItem, CompletionRule, WorkItemKind, WorkItemPriority,
} from './contracts';
import {
  GOAL_SOURCES, STARTED_LABELS, WORK_VIEWS, addable, goalDirections, inOrder, isOpen, openCourse, openProposal, progressWidths, recentActivity, refKey, ringDash,
  situation, sourceOf, sourceUrl, summarize, itemNeedsYou, unblocks, waitingOn, workIn,
  type WorkSource, type WorkView,
} from './model';
import { AgentModelControls, type ModelChoice } from '../settings/ModelsPage';
import type { GoalConversation, GoalModel } from './useGoals';
import type { ContextBuildViewDto } from '../repository-context/contracts';
import type { SpecCheckRequest } from '../repository-context/specChecks';
import { TOPIC_LEVELS } from './contracts';

/** How much of a goal is finished, as a ring. */
export function GoalRing({ goal, size = 20 }: { goal: GoalDto | null; size?: number }): React.JSX.Element {
  const dash = goal === null ? '0 44' : ringDash(summarize(goal, new Set()));
  return (
    <svg className="goal-ring" width={size} height={size} viewBox="0 0 20 20" aria-hidden="true">
      <circle cx="10" cy="10" r="7" fill="none" stroke="#e3e3e6" strokeWidth="3" />
      <circle cx="10" cy="10" r="7" fill="none" stroke="#1a7f37" strokeWidth="3" strokeDasharray={dash} />
    </svg>
  );
}

function fraction(goal: GoalDto): string {
  const summary = summarize(goal, new Set());
  return summary.total === 0 ? '' : `${summary.finished}/${summary.total}`;
}

export type GoalPanelView = 'guide' | 'work';

export type GoalPanelProps = {
  mode: 'window' | 'board';
  /** The goal's guidance, or its work items; the layer keeps the choice across window and board. */
  view: GoalPanelView;
  onView: (view: GoalPanelView) => void;
  /** The lesson open in the window, which takes the guidance's place. */
  lesson: string | null;
  onLesson: (lessonId: string | null) => void;
  conversation: GoalConversation;
  /** Starts a goal from the user's answer while the workspace shows none. */
  onStart: (operationId: string, message: string) => Promise<unknown>;
  /** "owner/repo", named where a search finds nothing. */
  repository: string;
  goals: GoalDto[];
  /** The goal shown: the pinned one, or null while the workspace has none. */
  goal: GoalDto | null;
  /** Items still showing in Needs attention, by kind and key. */
  needsYou: ReadonlySet<string>;
  now?: number;
  onClose: () => void;
  onExpand?: () => void;
  /** Shows another goal, which also makes it the one the pages follow. */
  onSwitch: (goalId: string) => void;
  /** Asks what the user wants to achieve; the answer starts another goal, and the current one stays until then. */
  onNew: () => void;
  /** Present while that question shows over the current goal; returns to it. */
  onBack?: () => void;
  onCreate: (title: string) => Promise<unknown>;
  onRename: (goalId: string, title: string) => Promise<unknown>;
  /** Pauses guidance, so the goal's agent hears no automatic events, or resumes it. */
  onPause: (goalId: string, paused: boolean) => Promise<unknown>;
  onDelete: (goalId: string) => Promise<unknown>;
  onAdd: (item: GoalItemDto) => Promise<unknown>;
  workItems: WorkItemDto[];
  onCreateWork: (request: CreateWorkItem) => Promise<WorkItemDto>;
  onDecide: (item: WorkItemDto, command: WorkItemDecision) => Promise<WorkItemDto>;
  /** Saves the order of a goal's work, which names each of its items once. */
  onOrder: (goalId: string, ids: string[]) => Promise<void>;
  /** Writes finished work's summary again, after its acceptance did not get one. */
  onSummarize: (item: WorkItemDto) => Promise<unknown>;
  onLink: (item: WorkItemDto, reference: GoalItemDto) => Promise<WorkItemDto>;
  onOpenItem: (item: GoalItemDto) => void;
  onOpenTask: (taskId: string) => void;
  /** Opens the issue's task composer with an instruction the user reviews and sends. */
  onStartIssue: (issue: number, instruction: string, replaces?: string) => void;
  /** The same for a Jira ticket, by its connection:cloud:issue reference. */
  onStartTicket: (ticket: string, instruction: string, replaces?: string) => void;
  /** Shows a module's spec check on the Memory page. */
  onSpecCheck: (spec: SpecCheckRequest) => void;
  /** Opens a gap's drafted issue on GitHub's form, for the user to file; answers whether the body was cut and copied instead. */
  onFileIssue: (draft: NonNullable<GoalGapDto['draft']>) => Promise<boolean>;
  search: (query: string) => Promise<GoalItemDto[]>;
};

/**
 * A goal's progress and items, listed by state or expanded into board columns. Every change here is local.
 */
export function GoalPanel(props: GoalPanelProps): React.JSX.Element {
  const { mode, goal, view, lesson, onClose, onExpand } = props;
  const opened = useRef<string | null>(null);
  // A lesson opens in the expanded window; restoring the window afterwards keeps it there.
  useEffect(() => {
    if (lesson !== null && lesson !== opened.current && mode === 'window') onExpand?.();
    opened.current = lesson;
  }, [lesson, mode, onExpand]);
  const lessonItem = lesson === null ? undefined : goal?.items.find((value) => value.id === lesson);
  const [selected, setSelected] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState<string | null>(null);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const run = (action: () => Promise<unknown>): void => {
    setActionError(null);
    void Promise.resolve().then(action).catch((failure: unknown) => setActionError(failure instanceof Error ? failure.message : 'The goal was not changed'));
  };
  const answering = props.conversation.exchanges.some((exchange) => exchange.state === 'PENDING');
  const item = props.workItems.find((value) => value.id === selected) ?? goal?.items.find((value) => value.id === selected);
  return (
    <div className={`goal-panel is-${mode}`} role="dialog" aria-label={goal === null ? 'Goal' : `Goal: ${goal.title}`}>
      <div className="goal-panel__head">
        <div className="goal-panel__bar">
          <GoalSwitcher {...props} />
          {goal?.pinned && <span className="goal-pinned">Pinned</span>}
          {goal?.paused && <span className="goal-paused">Paused</span>}
          <span className="goal-spacer" />
          {goal !== null && <>
            <button type="button" className="goal-headbtn" title="Update now: the agent reassesses the goal from its current facts" aria-label="Update now"
              disabled={answering} onClick={() => run(() => props.conversation.reassess(crypto.randomUUID()))}>
              <RefreshCw size={15} strokeWidth={1.8} aria-hidden="true" />
            </button>
            <button type="button" className="goal-headbtn" title={goal.paused ? 'Resume guidance' : 'Pause guidance: the agent hears nothing automatic'}
              aria-label={goal.paused ? 'Resume guidance' : 'Pause guidance'} onClick={() => run(() => props.onPause(goal.id, !goal.paused))}>
              {goal.paused ? <Play size={15} strokeWidth={1.8} aria-hidden="true" /> : <Pause size={15} strokeWidth={1.8} aria-hidden="true" />}
            </button>
          </>}
          {goal !== null && (
            <button type="button" className="goal-headbtn" title="Cancel goal" aria-label="Cancel goal" onClick={() => { setCancelling(''); setCancelError(null); }}>
              <Ban size={15} strokeWidth={1.8} aria-hidden="true" />
            </button>
          )}
          {onExpand !== undefined && (
            <button type="button" className="goal-headbtn" title={mode === 'board' ? 'Restore window size' : 'Expand goal'} aria-label={mode === 'board' ? 'Restore goal window' : 'Expand goal'} onClick={onExpand}>
              {mode === 'board' ? <Minimize2 size={15} strokeWidth={1.8} aria-hidden="true" /> : <Maximize2 size={15} strokeWidth={1.8} aria-hidden="true" />}
            </button>
          )}
          <button type="button" className="goal-headbtn" title="Close (Esc)" aria-label="Close the goal" onClick={onClose}>
            <X size={15} strokeWidth={1.9} aria-hidden="true" />
          </button>
        </div>
        {goal !== null && (
          <>
            <GoalTitle key={goal.id} goal={goal} onRename={props.onRename} />
            <div className="goal-panel__situation">{situation(goal, props.conversation.exchanges, props.conversation.gaps).text}</div>
            {actionError !== null && <p className="goal-guide__error" role="alert">{actionError}</p>}
            {goal.description !== '' && <div className="goal-panel__desc">{goal.description}</div>}
          </>
        )}
        {props.onBack === undefined && <div className="goal-panel__views">
          <div className="goal-tabs" role="tablist" aria-label="Goal views">
            {(['guide', 'work'] as const).map((value) => <button type="button" role="tab" aria-selected={view === value} key={value}
              className={`goal-tab${view === value ? ' is-active' : ''}`} onClick={() => { setSelected(null); props.onView(value); }}>{value === 'guide' ? 'Guide' : 'Work'}</button>)}
          </div>
          {goal !== null && props.conversation.model !== null && <GoalModelPicker model={props.conversation.model} onChoose={props.conversation.chooseModel} />}
        </div>}
      </div>
      {lessonItem !== undefined && goal !== null ? <LessonView key={lessonItem.id} goal={goal} item={lessonItem} repository={props.repository}
        conversation={props.conversation} onBack={() => props.onLesson(null)} />
        : item !== undefined ? <WorkDetails key={item.id} {...props} item={item} onBack={() => setSelected(null)} />
        : view === 'guide' ? <GoalGuide key={goal?.id ?? 'next'} goal={goal} repository={props.repository} conversation={props.conversation} now={props.now} onStart={props.onStart}
          onOpenTask={props.onOpenTask}
          onLesson={props.onLesson} onBack={props.onBack} onStartIssue={props.onStartIssue} onStartTicket={props.onStartTicket} onSpecCheck={props.onSpecCheck}
          onFileIssue={props.onFileIssue} onReview={setSelected} />
          : goal === null ? <><NoGoal onCreate={props.onCreate} />{props.workItems.map((saved) => <button type="button" className="goal-work-card" key={saved.id} onClick={() => setSelected(saved.id)}>{saved.title} · {saved.state.toLowerCase()}</button>)}</> : <GoalWork {...props} goal={goal} onSelect={setSelected} />}
      {cancelling !== null && goal !== null && (
        <ConfirmDialog
          title="Cancel this goal?"
          confirmLabel={busy ? 'Deleting…' : 'Confirm delete goal'}
          cancelLabel="Keep goal"
          confirmDisabled={cancelling !== 'cancel'}
          busy={busy}
          destructive
          onConfirm={() => {
            if (busy || cancelling !== 'cancel') return;
            setBusy(true);
            setCancelError(null);
            void props.onDelete(goal.id).then(() => setCancelling(null),
              (failure: unknown) => setCancelError(failure instanceof Error ? failure.message : 'The goal could not be deleted')).finally(() => setBusy(false));
          }}
          onCancel={() => setCancelling(null)}
        >
          <p>“{goal.title}” leaves your goals. Its conversation, work items and history stay saved.</p>
          <label className="bq-confirm-dialog__choice">
            <span>Type <strong>cancel</strong> to confirm.</span>
            <input type="text" value={cancelling} disabled={busy} autoComplete="off" spellCheck={false}
              onChange={(event) => setCancelling(event.target.value)} />
          </label>
          {cancelError !== null && <p className="goal-guide__error" role="alert">{cancelError}</p>}
        </ConfirmDialog>
      )}
    </div>
  );
}

/** The model the goal's agent answers and writes lessons with: the workspace's Development model until the user picks one for this goal. */
function GoalModelPicker({ model, onChoose }: { model: GoalModel; onChoose: (choice: ModelChoice | null) => Promise<void> }): React.JSX.Element {
  const [error, setError] = useState<string | null>(null);
  const choose = (choice: ModelChoice | null): void => {
    setError(null);
    void onChoose(choice).catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'The model was not changed'));
  };
  return (
    <div className="goal-model">
      {error !== null && <span className="goal-guide__error" role="alert">{error}</span>}
      <AgentModelControls agent={model.setting} settings={model.settings} onChoose={choose} onInherit={() => choose(null)}
        inheritLabel={model.setting.explicit ? 'Workspace default' : `${model.setting.modelLabel} · workspace default`} />
    </div>
  );
}

/** The goal's name, renamed in place: a click edits it, Enter or leaving the field saves, and Escape keeps the old name. */
function GoalTitle({ goal, onRename }: { goal: GoalDto; onRename: GoalPanelProps['onRename'] }): React.JSX.Element {
  const [draft, setDraft] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // The field can lose focus as it closes; that blur must not save again or undo an Escape.
  const closed = useRef(false);
  if (draft === null) {
    return <button type="button" className="goal-panel__title" title="Rename" onClick={() => { closed.current = false; setError(null); setDraft(goal.title); }}>{goal.title}</button>;
  }
  const save = (): void => {
    const title = draft.trim();
    if (closed.current || busy) return;
    if (title === '' || title === goal.title) {
      closed.current = true;
      setDraft(null);
      return;
    }
    setBusy(true); setError(null);
    void onRename(goal.id, title).then(() => { closed.current = true; setDraft(null); },
      (failure: unknown) => setError(failure instanceof Error ? failure.message : 'The name was not saved')).finally(() => setBusy(false));
  };
  return (
    <>
      <input className="goal-panel__title" aria-label="Goal name" autoFocus maxLength={500} value={draft} readOnly={busy}
        onChange={(event) => setDraft(event.target.value)} onBlur={save}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
            event.preventDefault();
            save();
          }
          else if (event.key === 'Escape') {
            // Handled here, so the window stays open.
            event.preventDefault();
            closed.current = true;
            setDraft(null);
          }
        }} />
      {error !== null && <p className="goal-guide__error" role="alert">{error}</p>}
    </>
  );
}

/**
 * The agent's side of the goal: "What do you want to achieve?" while there is nothing to go on, then the conversation, the
 * question's suggested answers and a definition to accept. The opening is written locally; only a message asks the model.
 */
function GoalGuide({ goal, repository, conversation, now, onStart, onOpenTask, onLesson, onBack, onStartIssue, onStartTicket, onSpecCheck, onFileIssue, onReview }:
  Pick<GoalPanelProps, 'goal' | 'repository' | 'conversation' | 'now' | 'onStart' | 'onOpenTask' | 'onLesson' | 'onBack' | 'onStartIssue' | 'onStartTicket'
    | 'onSpecCheck' | 'onFileIssue'> & { onReview: (workItemId: string) => void }): React.JSX.Element {
  const { exchanges } = conversation;
  const [error, setError] = useState<string | null>(null);
  const last = exchanges.at(-1);
  const answering = last?.state === 'PENDING';
  const question = last?.state === 'ANSWERED' ? last.reply?.question ?? null : null;
  const open = openProposal(goal, exchanges);
  const course = openCourse(goal, exchanges);
  const send = (operationId: string, message: string): Promise<unknown> => (goal === null ? onStart(operationId, message) : conversation.ask(operationId, message));
  const say = (message: string): void => {
    setError(null);
    void send(crypto.randomUUID(), message).catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'The message was not sent'));
  };
  // Until a goal's conversation is read, neither the opening nor the thread shows, so a goal that has begun never flashes its opening question.
  const ready = goal === null || conversation.loaded;
  const opening = goal === null || (ready && exchanges.length === 0 && goal.definition === null);
  // The answers picked belong to the question on screen; a new question starts with none.
  const [picked, setPicked] = useState<{ question: string; answers: string[] }>({ question: '', answers: [] });
  // Adjust puts its words in the reply field; each press is a new seed, so pressing again refills it.
  const [seed, setSeed] = useState<{ text: string } | null>(null);
  const adjust = (): void => setSeed({ text: ADJUST_PROPOSAL });
  const skip = (): void => say(SKIP_PROPOSAL);
  // The proposed goal's title as the user edits it; Accept goal saves it.
  const [titled, setTitled] = useState<{ exchangeId: string; title: string } | null>(null);
  const title = open === null ? '' : titled?.exchangeId === open.exchangeId ? titled.title : open.proposal.title;
  const shown = opening ? 'opening' : last?.id ?? '';
  const answers = picked.question === shown ? picked.answers : NONE_PICKED;
  const toggle = (answer: string): void => setPicked({ question: shown, answers: answers.includes(answer) ? answers.filter((value) => value !== answer) : [...answers, answer] });
  const composer = <Composer answering={answering} focus={opening} picked={answers} seed={seed} onSent={() => setPicked({ question: '', answers: [] })}
    placeholder={answers.length > 0 ? 'Add your own words, or just send' : opening ? 'I want to…' : question === null ? 'Ask about this goal' : 'Answer in your own words'}
    onSend={send} />;
  const errorText = error ?? conversation.error;
  const alert = errorText !== null && <p className="goal-guide__error" role="alert">{errorText}</p>;
  // Whatever waits on the user sits above the reply field, below the scrolling conversation, where the eye ends up.
  const docked = !opening && ready;
  const scroller = useRef<HTMLDivElement>(null);
  const latest = useRef<HTMLLIElement>(null);
  const turn = `${last?.id ?? ''} ${last?.state ?? ''}`;
  // A new message or answer scrolls to the top of its turn, where reading it starts.
  useLayoutEffect(() => {
    const box = scroller.current;
    const row = latest.current;
    if (box === null || row === null) return;
    box.scrollTop += row.getBoundingClientRect().top - box.getBoundingClientRect().top - TURN_MARGIN;
  }, [turn]);
  return (
    <div className="goal-panel__body goal-guide">
      <div className="goal-guide__scroll" ref={scroller}>
        {goal?.definition != null && <Definition definition={goal.definition} />}
        {goal?.course != null && <Course goal={goal} onLesson={onLesson} />}
        {conversation.notes.length > 0 && <Notes notes={conversation.notes} />}
        {goal !== null && goal.topics.length > 0 && <Known topics={goal.topics} onCorrect={conversation.correctTopic} />}
        {conversation.gaps.length > 0 && <Gaps gaps={conversation.gaps} onAdd={conversation.addGap} onFile={onFileIssue} onReview={onReview} />}
        {opening ? (
          <div className="goal-guide__opening">
            {onBack !== undefined && <button type="button" className="goal-link goal-guide__back" onClick={onBack}>Back to the current goal</button>}
            {/* The goal's own title is never the question: a name alone says little about what the user wants. */}
            <div className="goal-guide__ask">What do you want to achieve?</div>
            <div className="goal-guide__hint">Say it in your own words, pick any directions below, or both, then send. The agent asks what it needs, then proposes a goal
              for you to accept.</div>
            {composer}
            <Choices choices={goalDirections(repository)} picked={answers} disabled={answering} onToggle={toggle} />
          </div>
        ) : (
          <ol className="goal-thread" aria-label="Conversation with the goal's agent">
            {exchanges.map((exchange) => <li key={exchange.id} ref={exchange === last ? latest : undefined} className="goal-thread__turn">
              {/* An event can list everything a run saved; two lines say what happened, and the rest shows on hover. */}
              {exchange.automatic ? <div className="goal-thread__event" title={exchange.message}>{exchange.message}</div>
                : <div className="goal-thread__user">{exchange.message}</div>}
              {exchange.state === 'PENDING' && <Pending exchange={exchange} now={now} onStop={conversation.stop} />}
              {exchange.state === 'FAILED' && <div className="goal-thread__agent is-failed">
                <span>{exchange.failure}</span>
                {exchange === last && <button type="button" className="goal-link" onClick={() => {
                  setError(null);
                  void conversation.retry(exchange.id).catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'Could not ask again'));
                }}>Ask again</button>}
              </div>}
              {exchange.reply !== null && <div className="goal-thread__agent">
                <p>{exchange.reply.assessment}</p>
                {/* The question on screen is asked above the reply field. */}
                {exchange.reply.question !== null && exchange !== last && <p className="goal-thread__question">{exchange.reply.question.text}</p>}
              </div>}
            </li>)}
          </ol>
        )}
        {goal?.recommendations.filter((card) => card.state === 'STARTED').map((card) => <Started key={card.id} goal={goal} card={card} research={conversation}
          onOpenTask={onOpenTask}
          onLesson={onLesson} onStartIssue={onStartIssue} onStartTicket={onStartTicket} onSpecCheck={onSpecCheck} onReview={onReview} />)}
        {open !== null && <Proposal key={open.exchangeId} proposal={open.proposal} title={title}
          onTitle={(value) => setTitled({ exchangeId: open.exchangeId, title: value })} />}
        {course !== null && <CourseProposal key={course.exchangeId} goal={goal} course={course.course} />}
        {!docked && alert}
      </div>
      {docked && <div className="goal-guide__dock">
        {question !== null && <div className="goal-guide__question">{question.text}</div>}
        {question !== null && question.suggestions.length > 0 && <Choices choices={question.suggestions} picked={answers} disabled={answering} onToggle={toggle}>
          <button type="button" className="goal-link goal-guide__other" disabled={answering} onClick={() => say(OTHER_OPTIONS)}>Other options</button>
        </Choices>}
        {open !== null && <Decision key={open.exchangeId} label="Accept goal" ready={title.trim() !== ''} answering={answering} failure="The goal was not saved"
          onAccept={() => conversation.accept(open.exchangeId, title.trim())} onAdjust={adjust} onSkip={skip} />}
        {course !== null && <Decision key={course.exchangeId} label={goal?.course == null ? 'Accept course' : 'Accept revision'} ready answering={answering}
          failure="The course was not saved" onAccept={() => conversation.acceptCourse(course.exchangeId)} onAdjust={adjust} onSkip={skip} />}
        {alert}
        {composer}
      </div>}
    </div>
  );
}

/** How far above a scrolled-to turn the conversation stops, so its first line does not touch the edge. */
const TURN_MARGIN = 12;

/**
 * The gaps the goal's last spec check reproduced with a failing test. The user files each drafted issue on GitHub's own form and adds the gap
 * to the goal as work; until every gap is done, dropped or dismissed, the agent recommends no other spec check.
 */
function Gaps({ gaps, onAdd, onFile, onReview }: {
  gaps: GoalGapDto[];
  onAdd: (candidateId: string) => Promise<void>;
  onFile: (draft: NonNullable<GoalGapDto['draft']>) => Promise<boolean>;
  onReview: (workItemId: string) => void;
}): React.JSX.Element {
  const [note, setNote] = useState<string | null>(null);
  const failed = (fallback: string) => (failure: unknown): void => setNote(failure instanceof Error ? failure.message : fallback);
  return (
    <section className="goal-gaps" aria-label="Reproduced gaps">
      <div className="goal-group">Gaps the spec check reproduced · {gaps.length}</div>
      {gaps.map((gap) => <div key={gap.candidateId} className="goal-gap">
        <span className="goal-gap__title" title={gap.rule}>{gap.title}</span>
        {gap.test !== null && <span className="goal-muted">{gap.test.path}</span>}
        {gap.draft !== null && <button type="button" className="goal-link" onClick={() => {
          setNote(null);
          void onFile(gap.draft).then((cut) => setNote(cut ? 'The issue form opened with a shortened body; the full report is on the clipboard.'
            : 'The issue form opened on GitHub; file it there.'), failed('The issue form did not open'));
        }}>File issue</button>}
        {gap.workItemId === null
          ? <button type="button" className="goal-link" onClick={() => { setNote(null); void onAdd(gap.candidateId).catch(failed('The gap was not added')); }}>
            {gap.filed === null ? 'Add to goal' : `Add to goal with #${gap.filed}`}</button>
          : <button type="button" className="goal-link" onClick={() => onReview(gap.workItemId)}>Work: {gap.state?.toLowerCase() ?? 'open'}</button>}
        {/* Adding again links an issue the user filed since, so a task started from it joins the gap's work. */}
        {gap.workItemId !== null && gap.filed !== null && <button type="button" className="goal-link" onClick={() => {
          setNote(null); void onAdd(gap.candidateId).catch(failed('The issue was not linked'));
        }}>Link #{gap.filed} to its work</button>}
      </div>)}
      {note !== null && <p className="goal-muted" role="status">{note}</p>}
    </section>
  );
}

/**
 * A card the user started, while it lasts: research while its run goes, a lesson while it is open, and practice until its work is done or
 * dropped. Practice leads to its task once the user sent it, back to the prepared composer until then, and to its result once its pull request
 * merged, which the user accepts.
 */
function Started({ goal, card, research, onOpenTask, onLesson, onStartIssue, onStartTicket, onSpecCheck, onReview }: Pick<GoalPanelProps, 'onOpenTask' | 'onLesson'
  | 'onStartIssue' | 'onStartTicket' | 'onSpecCheck'> & {
  goal: GoalDto;
  card: GoalRecommendationDto;
  research: Pick<GoalConversation, 'researchRun' | 'stopResearch'>;
  onReview: (workItemId: string) => void;
}): React.JSX.Element {
  const work = card.workItemId === null ? undefined : goal.items.find((item) => item.id === card.workItemId);
  const task = work?.links.findLast((link) => link.kind === 'TASK');
  const pull = work?.links.findLast((link) => link.kind === 'PULL_REQUEST');
  return (
    <div className="goal-guide__running" role="status">
      <span>{STARTED_LABELS[card.card.kind]}: {card.card.title}</span>
      {card.card.kind === 'SPEC_CHECK' && card.card.module !== null && <button type="button" className="goal-link"
        onClick={() => onSpecCheck({ directory: card.card.module })}>Open check</button>}
      {card.card.kind === 'RESEARCH' && card.taskId !== null && <ResearchProgress taskId={card.taskId} read={research.researchRun} stop={research.stopResearch} />}
      {card.card.kind === 'LESSON' && card.card.workItemId !== null && <button type="button" className="goal-link"
        onClick={() => onLesson(card.card.workItemId)}>Open lesson</button>}
      {card.card.kind === 'INVESTIGATE' && work !== undefined && <button type="button" className="goal-link" onClick={() => onReview(work.id)}>Open work</button>}
      {pull !== undefined && <span className="goal-muted">{pull.label} {pull.status.toLowerCase()}</span>}
      {work !== undefined && pull?.state === 'MERGED' && <button type="button" className="goal-link" onClick={() => onReview(work.id)}>Review result</button>}
      {card.card.kind === 'WORK' && (task !== undefined
        ? <button type="button" className="goal-link" onClick={() => onOpenTask(task.key)}>Open task</button>
        : <button type="button" className="goal-link" onClick={() => {
          if (card.card.issue !== null) onStartIssue(card.card.issue, card.card.scope);
          else if (card.card.ticket !== null) onStartTicket(card.card.ticket, card.card.scope);
        }}>Open the composer</button>)}
    </div>
  );
}

const RUN_POLL_MS = 2_000;

/**
 * A research run as it goes: the notes it saved so far, the newest, and Stop. A research run is a workspace's restricted run, not a
 * conversation, so it is read from the run while the row shows; once the run ends, its card settles and the notes list what it saved.
 */
function ResearchProgress({ taskId, read, stop }: {
  taskId: string;
  read: () => Promise<ContextBuildViewDto>;
  stop: (taskId: string) => Promise<ContextBuildViewDto>;
}): React.JSX.Element | null {
  const [run, setRun] = useState<ContextBuildViewDto['build']>(null);
  const [stopping, setStopping] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    const load = (): void => {
      void Promise.resolve().then(read).then((view) => {
        if (active) setRun(view.build !== null && view.build.taskId === taskId ? view.build : null);
      }, () => {});
    };
    load();
    const timer = window.setInterval(load, RUN_POLL_MS);
    return () => { active = false; window.clearInterval(timer); };
  }, [read, taskId]);
  if (run === null) return null;
  const saved = run.activity.filter((line) => line.tone === 'DONE');
  return <>
    <span className="goal-muted" title={saved.at(-1)?.text}>{saved.length === 0 ? 'Reading…' : `${saved.length} ${saved.length === 1 ? 'note' : 'notes'} saved`}</span>
    {run.pendingApprovals > 0 && <span className="goal-muted">Waiting for approval</span>}
    {run.outcome === 'RUNNING' && <button type="button" className="goal-link" disabled={stopping || run.activeTurn?.cancellationRequestedAt != null} onClick={() => {
      setStopping(true); setError(null);
      void stop(taskId).then((view) => setRun(view.build !== null && view.build.taskId === taskId ? view.build : null),
        (failure: unknown) => setError(failure instanceof Error ? failure.message : 'The run was not stopped')).finally(() => setStopping(false));
    }}>{stopping ? 'Stopping…' : 'Stop'}</button>}
    {error !== null && <span className="goal-guide__error" role="alert">{error}</span>}
  </>;
}

/** A reply the agent is writing: how long it has worked, and Stop, after which the message can be asked again. */
function Pending({ exchange, now, onStop }: { exchange: GoalExchangeDto; now?: number; onStop: (exchangeId: string) => Promise<void> }): React.JSX.Element {
  const clock = useNow(now === undefined);
  const [stopping, setStopping] = useState(false);
  const seconds = Math.max(0, Math.floor(((now ?? clock) - Date.parse(exchange.askedAt)) / 1_000));
  return (
    <div className="goal-thread__agent is-pending">
      <span role="status">Thinking…</span>
      <span className="goal-muted">{Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, '0')}</span>
      <button type="button" className="goal-link" disabled={stopping} onClick={() => {
        setStopping(true);
        void onStop(exchange.id).catch(() => setStopping(false));
      }}>Stop</button>
    </div>
  );
}

/** The current time, read again every second while {@code ticking}. */
export function useNow(ticking: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!ticking) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [ticking]);
  return now;
}

/**
 * One lesson in the Goal window: written once from the goal's notes, its explanation, exercise and check, and questions about it that the
 * agent answers with the lesson in view. Done is the user's decision.
 */
function LessonView({ goal, item, repository, conversation, onBack }: {
  goal: GoalDto;
  item: WorkItemDto;
  repository: string;
  conversation: GoalConversation;
  onBack: () => void;
}): React.JSX.Element {
  const { openLesson, readLesson, finishLesson } = conversation;
  const [lesson, setLesson] = useState<GoalLessonDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [operation] = useState(() => crypto.randomUUID());
  const [busy, setBusy] = useState(false);
  const part = goal.course?.parts.find((candidate) => candidate.lessons.includes(item.id))?.title ?? '';
  const open = useCallback((): void => {
    setError(null);
    void openLesson(item.id).then(setLesson, (failure: unknown) => setError(failure instanceof Error ? failure.message : 'The lesson could not be opened'));
  }, [item.id, openLesson]);
  useEffect(open, [open]);
  const writing = lesson?.state === 'WRITING';
  useEffect(() => {
    if (!writing) return;
    const timer = window.setInterval(() => { void readLesson(item.id).then(setLesson, () => {}); }, 1_500);
    return () => window.clearInterval(timer);
  }, [item.id, readLesson, writing]);
  const questions = conversation.exchanges.filter((exchange) => exchange.lessonId === item.id);
  const answering = conversation.exchanges.at(-1)?.state === 'PENDING';
  return (
    <div className="goal-panel__body goal-lesson">
      <button type="button" className="goal-link goal-lesson__back" onClick={onBack}>Back to guidance</button>
      <div className="goal-lesson__part">{part}</div>
      <div className="goal-lesson__title">{item.title}</div>
      <div className="goal-lesson__outcome">After this lesson you can: {item.acceptanceCriteria}</div>
      {lesson === null && error === null && <p className="goal-muted" role="status">Opening the lesson…</p>}
      {writing && <p className="goal-muted" role="status">Writing the lesson from the goal’s notes…</p>}
      {lesson?.state === 'FAILED' && <p className="goal-guide__error" role="alert">{lesson.failure}{' '}
        <button type="button" className="goal-link" onClick={open}>Write again</button></p>}
      {lesson?.content != null && <>
        <MarkdownBody className="goal-lesson__explanation" body={lesson.content.explanation} repository={repository} />
        <section className="goal-lesson__section"><div className="goal-group">Exercise</div><MarkdownBody body={lesson.content.exercise} repository={repository} /></section>
        <section className="goal-lesson__section"><div className="goal-group">What to observe</div><p>{lesson.content.observe}</p></section>
        <section className="goal-lesson__section"><div className="goal-group">Check</div><p>{lesson.content.check}</p></section>
      </>}
      {error !== null && <p className="goal-guide__error" role="alert">{error}</p>}
      {item.state === 'DONE' ? <p className="goal-muted">Done</p> : <button type="button" className="goal-add goal-lesson__done" disabled={busy || lesson?.content == null}
        onClick={() => {
          setBusy(true); setError(null);
          void finishLesson(item.id, operation).catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'The lesson was not marked done'))
            .finally(() => setBusy(false));
        }}>{busy ? 'Saving…' : 'Mark lesson done'}</button>}
      {questions.length > 0 && <ol className="goal-thread" aria-label="Questions about this lesson">{questions.map((exchange) => <li key={exchange.id} className="goal-thread__turn">
        <div className="goal-thread__user">{exchange.message}</div>
        {exchange.state === 'PENDING' && <Pending exchange={exchange} onStop={conversation.stop} />}
        {exchange.state === 'FAILED' && <div className="goal-thread__agent is-failed">{exchange.failure}</div>}
        {exchange.reply !== null && <div className="goal-thread__agent"><p>{exchange.reply.assessment}</p></div>}
      </li>)}</ol>}
      <Composer answering={answering} placeholder="Ask about this lesson" onSend={(operationId, message) => conversation.ask(operationId, message, item.id)} />
    </div>
  );
}

/**
 * A proposed course, or a revision of the accepted one: its kept lessons as they stand, its new ones marked, and the unfinished ones it drops.
 * Accepting it saves each lesson as a Learn work item and starts nothing.
 */
function CourseProposal({ goal, course }: { goal: GoalDto | null; course: GoalCourseDraftDto }): React.JSX.Element {
  const accepted = goal?.course ?? null;
  const lessons = new Map((goal?.items ?? []).map((item) => [item.id, item]));
  const kept = new Set(course.parts.flatMap((part) => part.lessons.flatMap((lesson) => (lesson.lessonId === null ? [] : [lesson.lessonId]))));
  const leaving = (accepted?.parts ?? []).flatMap((part) => part.lessons).filter((id) => !kept.has(id)).map((id) => lessons.get(id))
    .filter((item) => item !== undefined && item.state !== 'DONE' && item.state !== 'DROPPED');
  const label = accepted === null ? 'Proposed course' : 'Revised course';
  return (
    <section className="goal-proposal" aria-label={label}>
      <div className="goal-proposal__label">{label}</div>
      {course.reason !== null && <p className="goal-course__reason">{course.reason}</p>}
      {course.parts.map((part, index) => <div key={part.title} className="goal-course__part">
        <div className="goal-course__title">{index + 1}. {part.title}</div>
        <ol className="goal-course__lessons">{part.lessons.map((lesson) => {
          const existing = lesson.lessonId === null ? undefined : lessons.get(lesson.lessonId);
          return <li key={lesson.lessonId ?? lesson.title} className={existing?.state === 'DONE' ? 'is-done' : undefined}>
            <span>{existing?.title ?? lesson.title}{accepted !== null && existing === undefined && <span className="goal-course__new">New</span>}</span>
            <span className="goal-course__outcome">{existing === undefined ? lesson.outcome : existing.state === 'DONE' ? 'Done' : existing.acceptanceCriteria}</span>
          </li>;
        })}</ol>
      </div>)}
      {leaving.length > 0 && <div className="goal-course__part">
        <div className="goal-course__title">Leaves the course</div>
        <ul className="goal-course__lessons goal-course__leaving">{leaving.map((item) => <li key={item.id}>{item.title}</li>)}</ul>
      </div>}
    </section>
  );
}

/** The accepted course, each lesson where it stands; a lesson opens in the Goal window. */
function Course({ goal, onLesson }: { goal: GoalDto; onLesson: (lessonId: string) => void }): React.JSX.Element {
  const lessons = new Map(goal.items.map((item) => [item.id, item]));
  return (
    <section className="goal-course" aria-label="Course">
      {goal.course.parts.map((part, index) => <div key={part.title} className="goal-course__part">
        <div className="goal-course__title">{index + 1}. {part.title}</div>
        <ol className="goal-course__lessons">{part.lessons.map((id) => lessons.get(id)).filter((lesson) => lesson !== undefined).map((lesson) =>
          <li key={lesson.id} className={`is-${lesson.state.toLowerCase()}`}>
            <button type="button" className="goal-course__open" onClick={() => onLesson(lesson.id)}>{lesson.title}</button>
            <span className="goal-course__outcome">{lesson.state === 'DONE' ? 'Done' : lesson.acceptanceCriteria}</span>
          </li>)}</ol>
      </div>)}
    </section>
  );
}

/** What the agent believes the user knows, topic by topic; the user's own level wins and the agent leaves it alone. */
function Known({ topics, onCorrect }: { topics: GoalTopicDto[]; onCorrect: (topic: string, level: TopicLevel) => Promise<unknown> }): React.JSX.Element {
  const [error, setError] = useState<string | null>(null);
  return (
    <details className="goal-known goal-fold" aria-label="What you know">
      <summary>What you know · {topics.length}</summary>
      {topics.map((topic) => <label key={topic.topic} className="goal-known__row" title={topic.evidence.join(', ')}>
        <span className="goal-known__topic">{topic.topic}</span>
        <select value={topic.level} aria-label={`Your level in ${topic.topic}`} onChange={(event) => {
          setError(null);
          void onCorrect(topic.topic, event.target.value as TopicLevel).catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'The level was not saved'));
        }}>
          {TOPIC_LEVELS.map((level) => <option key={level} value={level}>{LEVEL_NAMES[level]}</option>)}
        </select>
        {topic.userSet && <span className="goal-muted">set by you</span>}
      </label>)}
      {error !== null && <p className="goal-guide__error" role="alert">{error}</p>}
    </details>
  );
}

const LEVEL_NAMES: Record<TopicLevel, string> = { NEW: 'New', LEARNING: 'Learning', PRACTICED: 'Practiced', STRONG: 'Strong' };

/** What the goal's research saved, folded under its count, each note with the pages and files it rests on. */
function Notes({ notes }: { notes: GoalKnowledgeNoteDto[] }): React.JSX.Element {
  return (
    <details className="goal-notes goal-fold" aria-label="Knowledge notes">
      <summary>What the agent learned · {notes.length}</summary>
      {notes.map((note) => <details key={note.id} className="goal-note">
        <summary>{note.topic}{note.stale && <span className="goal-note__stale" title="A file it cites changed since the research read it">Stale</span>}</summary>
        <p>{note.body}</p>
        <ul>{note.sources.map((source) => <li key={source.reference + (source.commit ?? '')}>
          <a href={sourceUrl(source)} target="_blank" rel="noreferrer">{source.commit === null ? source.reference : `${source.reference} @ ${source.commit.slice(0, 7)}`}</a>
        </li>)}</ul>
      </details>)}
    </details>
  );
}

const NONE_PICKED: string[] = [];
/** What Other options sends: the agent asks the same question again with answers the user has not passed over. */
const OTHER_OPTIONS = 'None of these fit. Show me other options.';
const SKIP_PROPOSAL = 'Skip this proposal for now.';
const ADJUST_PROPOSAL = 'Adjust it: ';

/** Suggested answers, any number of which the user picks; Send sends them with the user's own words. */
function Choices({ choices, picked, disabled, onToggle, children }: {
  choices: string[]; picked: string[]; disabled: boolean; onToggle: (choice: string) => void; children?: React.ReactNode;
}): React.JSX.Element {
  return <div className="goal-choices">{choices.map((choice) => <button type="button" className="goal-choice" key={choice} aria-pressed={picked.includes(choice)}
    disabled={disabled} onClick={() => onToggle(choice)}>{choice}</button>)}{children}</div>;
}

function Definition({ definition }: { definition: NonNullable<GoalDto['definition']> }): React.JSX.Element {
  return (
    <dl className="goal-definition" aria-label="Agreed goal">
      <dt>Success means</dt><dd>{definition.successDefinition}</dd>
      {definition.startingKnowledge !== '' && <><dt>You already know</dt><dd>{definition.startingKnowledge}</dd></>}
      {definition.constraints !== '' && <><dt>Constraints</dt><dd>{definition.constraints}</dd></>}
    </dl>
  );
}

/**
 * The open proposal's decision, above the reply field: accept it, or tell the agent what to change or that the user passes on it; the next
 * answer replaces it either way.
 */
function Decision({ label, ready, answering, failure, onAccept, onAdjust, onSkip }: {
  label: string; ready: boolean; answering: boolean; failure: string; onAccept: () => Promise<unknown>; onAdjust: () => void; onSkip: () => void;
}): React.JSX.Element {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <div className="goal-guide__decision">
      <button type="button" className="goal-add is-primary" disabled={busy || !ready} onClick={() => {
        setBusy(true); setError(null);
        void onAccept().catch((cause: unknown) => setError(cause instanceof Error ? cause.message : failure)).finally(() => setBusy(false));
      }}>{busy ? 'Saving…' : label}</button>
      <button type="button" className="goal-link" disabled={busy || answering} onClick={onAdjust}>Adjust</button>
      <button type="button" className="goal-link" disabled={busy || answering} onClick={onSkip}>Skip</button>
      {error !== null && <p className="goal-guide__error" role="alert">{error}</p>}
    </div>
  );
}

function Proposal({ proposal, title, onTitle }: { proposal: GoalProposalDto; title: string; onTitle: (title: string) => void }): React.JSX.Element {
  return (
    <section className="goal-proposal" aria-label="Proposed goal">
      <div className="goal-proposal__label">Proposed goal</div>
      <input aria-label="Goal title" maxLength={500} value={title} onChange={(event) => onTitle(event.target.value)} />
      <dl className="goal-definition">
        <dt>Objective</dt><dd>{proposal.objective}</dd>
        <dt>Success means</dt><dd>{proposal.successDefinition}</dd>
        {proposal.startingKnowledge !== '' && <><dt>You already know</dt><dd>{proposal.startingKnowledge}</dd></>}
        {proposal.constraints !== '' && <><dt>Constraints</dt><dd>{proposal.constraints}</dd></>}
      </dl>
    </section>
  );
}

/**
 * The user's own words to the agent, after the suggested answers they picked, one per line. Resending the same message after a lost answer
 * keeps its operation, so the agent is asked once.
 *
 * @param focus whether the field takes focus as it appears and when the window opens, as for the opening question
 */
function Composer({ answering, focus = false, picked = NONE_PICKED, seed = null, placeholder, onSend, onSent }: {
  answering: boolean;
  focus?: boolean;
  picked?: string[];
  /** Words to start the reply with, such as Adjust's, which take the field and its focus. */
  seed?: { text: string } | null;
  placeholder: string;
  onSend: (operationId: string, message: string) => Promise<unknown>;
  onSent?: () => void;
}): React.JSX.Element {
  const [draft, setDraft] = useState('');
  const field = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    if (seed === null) return;
    setDraft(seed.text);
    field.current?.focus();
  }, [seed]);
  const attempt = useRef<{ message: string; operation: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = (): void => {
    const message = [...picked, draft.trim()].filter((part) => part !== '').join('\n');
    if (message === '' || busy || answering) return;
    const operation = attempt.current?.message === message ? attempt.current.operation : crypto.randomUUID();
    attempt.current = { message, operation };
    setBusy(true); setError(null);
    void onSend(operation, message).then(() => { setDraft(''); attempt.current = null; onSent?.(); },
      (failure: unknown) => setError(failure instanceof Error ? failure.message : 'The message was not sent')).finally(() => setBusy(false));
  };
  return (
    <form className="goal-composer" onSubmit={(event) => { event.preventDefault(); submit(); }}>
      <textarea ref={field} aria-label="Message the goal's agent" rows={2} maxLength={4000} value={draft} placeholder={placeholder} autoFocus={focus}
        data-goal-focus={focus || undefined}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); submit(); } }} />
      {error !== null && <p className="goal-guide__error" role="alert">{error}</p>}
      <button type="submit" className="goal-add" disabled={busy || answering || (draft.trim() === '' && picked.length === 0)}>{busy ? 'Sending…' : 'Send'}</button>
    </form>
  );
}

function GoalSwitcher({ goals, goal, repository, onSwitch, onNew }: GoalPanelProps): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const position = goal === null ? 0 : goals.findIndex((candidate) => candidate.id === goal.id) + 1;
  return (
    <>
      <button type="button" className="goal-switch" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
        <GoalIcon />
        <span className="goal-switch__label">GOAL</span>
        {position > 0 && <span className="goal-switch__pos">{position} of {goals.length}</span>}
        <ChevronDown size={11} strokeWidth={2.4} aria-hidden="true" />
      </button>
      {open && (
        <div className="goal-menu" role="menu">
          <div className="goal-menu__label">GOALS IN {repository.toUpperCase()}</div>
          {goals.map((candidate) => (
            <button type="button" role="menuitem" key={candidate.id} className={`goal-menu__row${candidate.id === goal?.id ? ' is-current' : ''}`}
              onClick={() => { setOpen(false); onSwitch(candidate.id); }}>
              <GoalRing goal={candidate} size={18} />
              <span className="goal-menu__title">{candidate.title}</span>
              <span className="goal-mono">{fraction(candidate)}</span>
              {candidate.pinned && <span className="goal-menu__pinned">PINNED</span>}
            </button>
          ))}
          {goals.length > 0 && <div className="goal-menu__rule" />}
          <button type="button" role="menuitem" className="goal-menu__action" onClick={() => { setOpen(false); onNew(); }}>
            <Plus size={14} strokeWidth={2} aria-hidden="true" />New goal
          </button>
        </div>
      )}
    </>
  );
}

function NameInput({ busy, onSubmit, onCancel }: { busy: boolean; onSubmit: (title: string) => void; onCancel: () => void }): React.JSX.Element {
  const [title, setTitle] = useState('');
  return (
    <form className="goal-name" onSubmit={(event) => { event.preventDefault(); if (title.trim() !== '') onSubmit(title.trim()); }}>
      <input autoFocus value={title} disabled={busy} placeholder="Goal name" aria-label="Goal name"
        onChange={(event) => setTitle(event.target.value)} onKeyDown={(event) => { if (event.key === 'Escape') onCancel(); }} />
      <button type="submit" className="goal-add" disabled={busy || title.trim() === ''}>Create</button>
    </form>
  );
}

function NoGoal({ onCreate }: { onCreate: (title: string) => Promise<unknown> }): React.JSX.Element {
  const [busy, setBusy] = useState(false);
  return (
    <div className="goal-panel__body goal-empty">
      <div className="goal-empty__title">No goal yet</div>
      <div className="goal-empty__text">A Goal guides an outcome through learning, research and accepted work. Each work item can link issues, tickets, tasks and pull requests. Epic markers organize delivery work independently.</div>
      <NameInput busy={busy} onCancel={() => {}} onSubmit={(title) => { setBusy(true); void onCreate(title).finally(() => setBusy(false)); }} />
    </div>
  );
}

/** Stored pull requests, issues, tickets and tasks to add, searched as the user types. */
export function useCandidates(goal: Pick<GoalDto, 'items'>, search: GoalPanelProps['search'], open: boolean): { query: string; setQuery: (query: string) => void; results: GoalItemDto[] } {
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<GoalItemDto[]>([]);
  useEffect(() => {
    if (!open) return;
    let active = true;
    const timer = window.setTimeout(() => {
      void search(query).then((results) => { if (active) setFound(results); }, () => { if (active) setFound([]); });
    }, 150);
    return () => { active = false; window.clearTimeout(timer); };
  }, [open, query, search]);
  return { query, setQuery, results: addable(found, goal) };
}

export function Candidate({ item, onAdd, board }: { item: GoalItemDto; onAdd: (item: GoalItemDto) => Promise<unknown>; board?: boolean }): React.JSX.Element {
  return (
    <div className="goal-result">
      <SourceIcon source={GOAL_SOURCES[item.kind]} tinted />
      <span className="card-key">{item.label}</span>
      <span className="goal-result__title">{item.title}</span>
      {board && <span className="goal-result__sub">{item.status}</span>}
      <button type="button" className="goal-link" onClick={() => { void onAdd(item).catch(() => {}); }}>Add</button>
    </div>
  );
}

function Stats({ goal, needsYou, onNeeds, onBlocked }: { goal: GoalDto; needsYou: ReadonlySet<string>; onNeeds: () => void; onBlocked: () => void }): React.JSX.Element {
  const summary = summarize(goal, needsYou);
  const widths = progressWidths(summary);
  return (
    <>
      <div>
        <div className="goal-progress__head">
          <span className="goal-progress__count">{summary.finished}</span>
          <span className="goal-muted">of {summary.total} done</span>
          <span className="goal-spacer" />
          <span className="goal-mono">{summary.total === 0 ? '—' : `${Math.round((summary.finished / Math.max(1, summary.total)) * 100)}%`}</span>
        </div>
        <div className="goal-progress"><span className="is-finished" style={{ width: widths.finished }} /><span className="is-working" style={{ width: widths.working }} /><span className="is-idle" /></div>
        <div className="goal-legend">
          <span><i className="is-finished" />Done {summary.finished}</span>
          <span><i className="is-working" />Working {summary.working}</span>
          <span><i className="is-idle" />Planned {summary.idle}</span>
          {summary.dropped > 0 && <span>Dropped {summary.dropped}</span>}
        </div>
      </div>
      <div className="goal-tiles">
        <div className="goal-tile">
          <div className="goal-tile__label">Pull requests</div>
          <div className="goal-tile__value">{summary.prsMerged} <span>merged</span></div>
          <div className="goal-tile__sub">{summary.prsOpen} open</div>
        </div>
        <button type="button" className="goal-tile" onClick={onNeeds}>
          <div className="goal-tile__label">Needs you</div>
          <div className="goal-tile__value is-needs">{summary.needs}</div>
          <div className="goal-tile__sub">in attention</div>
        </button>
        <button type="button" className="goal-tile" onClick={onBlocked}>
          <div className="goal-tile__label">Blocked</div>
          <div className="goal-tile__value is-blocked">{summary.blocked}</div>
          <div className="goal-tile__sub">on others</div>
        </button>
      </div>
    </>
  );
}

function GoalWork(props: GoalPanelProps & { goal: GoalDto; onSelect: (id: string) => void }): React.JSX.Element {
  const { goal, needsYou, mode, onSelect } = props;
  // It opens on the first view with work, Next when there is any.
  const [view, setView] = useState<WorkView>(() => WORK_VIEWS.find((entry) => workIn(goal.items, entry.view).length > 0)?.view ?? 'next');
  const [needsOnly, setNeedsOnly] = useState(false);
  const [priority, setPriority] = useState<WorkItemPriority | 'ANY'>('ANY');
  const [source, setSource] = useState<WorkSource | 'ANY'>('ANY');
  const [adding, setAdding] = useState(false);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const candidates = useCandidates(goal, props.search, adding);
  const shown = goal.items.filter((item) => (!needsOnly || itemNeedsYou(item, needsYou)) && (priority === 'ANY' || item.priority === priority)
    && (source === 'ANY' || sourceOf(item) === source));
  const views = WORK_VIEWS.map((entry) => ({ ...entry, rows: workIn(shown, entry.view, goal.items) }));
  const empty = goal.items.length === 0;
  const activity = recentActivity(goal);
  return <div className={`goal-panel__body${mode === 'board' ? ' is-board' : ''}`}>
    {!empty && <Stats goal={goal} needsYou={needsYou} onNeeds={() => setNeedsOnly((value) => !value)} onBlocked={() => setView('blocked')} />}
    {empty && <div><div className="goal-guide__ask">No work yet</div>
      <div className="goal-guide__hint">Ask the agent in Guide what to work on first, create a work item, or add an issue, ticket or task you already have. Each
        outcome counts once.</div></div>}
    <div className="goal-tabsrow">
      {!empty && mode !== 'board' && <div className="goal-tabs" role="tablist" aria-label="Goal work">
        {views.map((entry) => <button type="button" role="tab" aria-selected={view === entry.view} key={entry.view} title={entry.hint}
          className={`goal-tab${view === entry.view ? ' is-active' : ''}`} onClick={() => setView(entry.view)}>{`${entry.label} ${entry.rows.length}`}</button>)}
      </div>}
      <button type="button" className="goal-add" onClick={() => setCreating((value) => !value)}>New work item</button>
      <button type="button" className="goal-add" onClick={() => setAdding((value) => !value)}>Add existing</button>
    </div>
    {!empty && <div className="goal-filters">
      <select aria-label="Priority" value={priority} onChange={(event) => setPriority(event.target.value as WorkItemPriority | 'ANY')}>
        <option value="ANY">Any priority</option><option value="HIGH">High</option><option value="NORMAL">Normal</option><option value="LOW">Low</option>
      </select>
      <select aria-label="Source" value={source} onChange={(event) => setSource(event.target.value as WorkSource | 'ANY')}>
        <option value="ANY">Any source</option><option value="issue">Issues</option><option value="jira">Jira tickets</option><option value="none">No source</option>
      </select>
      {needsOnly && <button type="button" className="goal-link" onClick={() => setNeedsOnly(false)}>Show all, not only what needs you</button>}
    </div>}
    {error !== null && <p role="alert">{error}</p>}
    {creating && <NewWork goal={goal} onCreate={props.onCreateWork} onDone={(item) => { setCreating(false); onSelect(item.id); }} />}
    {adding && <div className="goal-search">
      <label className="goal-search__field"><Search size={14} aria-hidden="true" /><input aria-label="Search items to add" value={candidates.query}
        placeholder="Search issues, tickets, tasks and PRs" onChange={(event) => candidates.setQuery(event.target.value)} /></label>
      {candidates.results.map((item) => <Candidate key={refKey(item)} item={item} onAdd={(reference) => props.onAdd(reference).catch((failure: unknown) => {
        setError(failure instanceof Error ? failure.message : 'Could not add work');
      })} />)}
      {props.workItems.filter((item) => item.goalId !== goal.id).map((item) => <div className="goal-result" key={item.id}>
        <button type="button" className="goal-link goal-result__title" onClick={() => onSelect(item.id)}>{item.title}</button><button type="button" className="goal-link" onClick={() => {
          void props.onDecide(item, { operationId: crypto.randomUUID(), expectedRevision: item.revision, action: 'MOVE', goalId: goal.id, detail: '' })
            .catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'Could not move work'));
        }}>{item.goalId === null ? 'Add work item' : 'Move here'}</button>
      </div>)}
    </div>}
    {!empty && <div className={mode === 'board' ? 'goal-columns' : 'goal-work-list'}>
      {views.filter((entry) => mode === 'board' || entry.view === view).map((entry) => <div className={mode === 'board' ? 'goal-column' : 'goal-work-group'} key={entry.view}>
        {mode === 'board' && <div className="goal-group" title={entry.hint}><span className="goal-group__label">{entry.label}</span>
          <span className="goal-group__count">{entry.rows.length}</span></div>}
        {entry.rows.map((item) => <WorkCard key={item.id} item={item} all={goal.items} needsYou={needsYou} onSelect={onSelect} />)}
      </div>)}
    </div>}
    {activity.length > 0 && <div><div className="goal-group">Recent decisions</div>{activity.map(({ item, text, at }, index) =>
      <div className="goal-activity__row" key={`${item.id}-${index}`}><span className="goal-activity__text">{text}</span><span>{relativeTime(at, { now: props.now })}</span></div>)}</div>}
  </div>;
}

/** A work card says why it stands where it does: the user's priority, a source's own priority, the work waiting on it and what it waits on. */
function WorkCard({ item, all, needsYou, onSelect }: { item: WorkItemDto; all: WorkItemDto[]; needsYou: ReadonlySet<string>; onSelect: (id: string) => void }):
  React.JSX.Element {
  const waiting = unblocks(item, all);
  const waits = isOpen(item) ? waitingOn(item, all) : [];
  return <button type="button" className="goal-work-card" onClick={() => onSelect(item.id)}>
    <span className="goal-work-card__title">{item.title}</span>
    <span className="goal-muted">{item.kind.toLowerCase()} · {item.links.length} linked references{isOpen(item) ? '' : ` · ${item.state.toLowerCase()}`}</span>
    {(item.priority !== 'NORMAL' || waiting.length > 0 || item.links.some((link) => link.priority)) && <span className="goal-work-card__why">
      {item.priority !== 'NORMAL' && <span className={`goal-chip is-${item.priority.toLowerCase()}`} title="Your priority">{item.priority === 'HIGH' ? 'High' : 'Low'}</span>}
      {item.links.filter((link) => link.priority).map((link) => <span className="goal-chip" key={refKey(link)} title={`${link.label}'s own priority, not mapped to yours`}>
        {`${link.label} ${link.priority}`}</span>)}
      {waiting.length > 0 && <span className="goal-chip" title={`Waiting on it: ${waiting.map((other) => other.title).join(', ')}`}>{`Unblocks ${waiting.length}`}</span>}
    </span>}
    {waits.length > 0 && <span>{`Waits on ${waits.join(', ')}`}</span>}
    {isOpen(item) && item.links.some((link) => link.status === 'Failed') && <span>Attempt failed — outcome still open</span>}
    {item.links.some((link) => link.state === 'UNKNOWN') && <span>Some evidence is unavailable</span>}
    {itemNeedsYou(item, needsYou) && <span>Needs you</span>}
  </button>;
}

function NewWork({ goal, onCreate, onDone }: { goal: GoalDto; onCreate: GoalPanelProps['onCreateWork']; onDone: (item: WorkItemDto) => void }): React.JSX.Element {
  const [id] = useState(() => crypto.randomUUID());
  const [title, setTitle] = useState('');
  const [criteria, setCriteria] = useState('');
  const [kind, setKind] = useState<WorkItemKind>('DEVELOP');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return <form className="goal-work-form" onSubmit={(event) => {
    event.preventDefault(); setBusy(true); setError(null);
    void onCreate({ id, goalId: goal.id, title: title.trim(), kind, acceptanceCriteria: criteria.trim(),
      completionRule: kind === 'DEVELOP' ? 'MERGED_DELIVERY' : 'ACCEPTED_RESULT' }).then(onDone)
      .catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'Could not save work')).finally(() => setBusy(false));
  }}>
    <label>Work item<input required maxLength={500} value={title} onChange={(event) => setTitle(event.target.value)} /></label>
    <label>Kind<select value={kind} onChange={(event) => setKind(event.target.value as WorkItemKind)}>
      <option value="DEVELOP">Develop</option><option value="LEARN">Learn</option><option value="INVESTIGATE">Investigate</option>
    </select></label>
    <label>What will count as success?<textarea required maxLength={10000} value={criteria} onChange={(event) => setCriteria(event.target.value)} /></label>
    <p className="goal-muted">{kind === 'DEVELOP' ? 'Completion requires merged PR evidence and your acceptance of the covered scope.' : 'Completion requires your acceptance of a recorded result.'}</p>
    {error !== null && <p role="alert">{error}</p>}
    <button type="submit" className="goal-add" disabled={busy || title.trim() === '' || criteria.trim() === ''}>{busy ? 'Saving…' : 'Save work item'}</button>
  </form>;
}

function WorkDetails(props: GoalPanelProps & { item: WorkItemDto; onBack: () => void }): React.JSX.Element {
  const { item } = props;
  const [title, setTitle] = useState(item.title);
  const [criteria, setCriteria] = useState(item.acceptanceCriteria);
  const [rule, setRule] = useState<CompletionRule>(item.completionRule);
  const [draftRevision, setDraftRevision] = useState(item.revision);
  const [detail, setDetail] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [targetGoal, setTargetGoal] = useState(item.goalId ?? '');
  const requests = useRef(new Map<string, string>());
  const candidates = useCandidates({ items: [item] }, props.search, adding);
  const concluded = item.state === 'DONE' || item.state === 'DROPPED';
  // Finished Goal work other than a lesson keeps a summary of its latest acceptance, written once from its history.
  const accepted = item.history.filter((event) => event.action === 'ACCEPT').at(-1);
  const summary = accepted === undefined ? undefined : item.history.find((event) => event.operationId === `summary:${accepted.operationId}`);
  const summarized = item.state === 'DONE' && item.kind !== 'LEARN' && item.goalId !== null && accepted !== undefined;
  const [summarizing, setSummarizing] = useState(false);
  const issue = item.links.find((link) => link.kind === 'ISSUE');
  const issueNumber = issue === undefined ? undefined : Number(issue.key.slice(issue.key.lastIndexOf('#') + 1));
  const ticket = item.links.find((link) => link.kind === 'JIRA');
  const lastTask = item.links.filter((link) => link.kind === 'TASK').at(-1);
  // The same Goal's work, in its order: what this item waits on, what waits on it and where it stands.
  const siblings = item.goalId === null ? [] : inOrder(props.workItems.filter((other) => other.goalId === item.goalId));
  const openSiblings = siblings.filter(isOpen);
  const place = openSiblings.findIndex((other) => other.id === item.id);
  const waitsOn = siblings.filter((other) => item.blockedBy.includes(other.id));
  const waiting = siblings.filter((other) => other.blockedBy.includes(item.id));
  const openWaiting = waiting.filter(isOpen);
  const blockable = siblings.filter((other) => other.id !== item.id && other.state !== 'DROPPED' && !item.blockedBy.includes(other.id));
  const failed = (failure: unknown): void => setError(failure instanceof Error ? failure.message : 'Decision was not saved');
  const arrange = (decision: Pick<WorkItemDecision, 'action' | 'priority' | 'blocker'>): void => {
    setBusy(true); setError(null);
    void props.onDecide(item, { operationId: crypto.randomUUID(), expectedRevision: item.revision, detail: '', ...decision }).catch(failed).finally(() => setBusy(false));
  };
  const move = (by: -1 | 1): void => {
    const neighbor = openSiblings[place + by];
    const ids = siblings.map((other) => other.id === item.id ? neighbor.id : other.id === neighbor.id ? item.id : other.id);
    setBusy(true); setError(null);
    void props.onOrder(item.goalId, ids).catch(failed).finally(() => setBusy(false));
  };
  const change = (action: WorkItemDecision['action']): void => {
    const payload = { expectedRevision: action === 'EDIT' ? draftRevision : item.revision, action, detail,
      ...(action === 'EDIT' ? { title, acceptanceCriteria: criteria, completionRule: rule } : {}),
      ...(action === 'MOVE' && targetGoal !== '' ? { goalId: targetGoal } : {}),
      // Dropped work is not done: the button names the work that stops waiting on it.
      ...(action === 'DROP' && openWaiting.length > 0 ? { releaseDependents: true } : {}) };
    const key = JSON.stringify(payload);
    const operationId = requests.current.get(key) ?? crypto.randomUUID();
    requests.current.set(key, operationId);
    setBusy(true); setError(null);
    void props.onDecide(item, { ...payload, operationId }).then((updated) => {
      if (action === 'EDIT') setDraftRevision(updated.revision);
      setDetail('');
      if (action === 'MOVE') props.onBack();
    }).catch(failed).finally(() => setBusy(false));
  };
  return <div className="goal-panel__body goal-work-form">
    <button type="button" className="goal-link" onClick={props.onBack}>Back to work</button>
    <strong>{item.title}</strong><span>{item.state.toLowerCase()} · {item.kind.toLowerCase()}</span>
    {error !== null && <p role="alert">{error}</p>}
    <label>Title<input maxLength={500} disabled={concluded || busy} value={title} onChange={(event) => setTitle(event.target.value)} /></label>
    <label>Acceptance criteria<textarea maxLength={10000} disabled={concluded || busy} value={criteria} onChange={(event) => setCriteria(event.target.value)} /></label>
    <label>Completion rule<select disabled={concluded || busy} value={rule} onChange={(event) => setRule(event.target.value as CompletionRule)}>
      <option value="MERGED_DELIVERY">Merged delivery</option><option value="ACCEPTED_RESULT">User accepted result</option>
    </select></label>
    {!concluded && <div className="goal-work-actions"><button type="button" className="goal-add" disabled={busy} onClick={() => change('EDIT')}>Save scope</button>
      {draftRevision !== item.revision && <button type="button" className="goal-link" disabled={busy} onClick={() => {
        setTitle(item.title); setCriteria(item.acceptanceCriteria); setRule(item.completionRule); setDraftRevision(item.revision);
      }}>Reload saved scope</button>}</div>}
    {summarized && <section className="goal-work-summary" aria-label="Summary">
      <div className="goal-group">Summary</div>
      {summary !== undefined ? <MarkdownBody body={summary.detail} repository={props.repository} />
        : <p className="goal-muted">{summarizing ? 'Writing the summary…' : 'Summary pending.'}{!summarizing && <> <button type="button" className="goal-link" onClick={() => {
          setSummarizing(true); setError(null);
          void props.onSummarize(item).catch((failure: unknown) => { setSummarizing(false); setError(failure instanceof Error ? failure.message : 'The summary was not asked for'); });
        }}>Summarize</button></>}</p>}
    </section>}
    {item.goalId !== null && <section className="goal-work-order" aria-label="Order">
      <div className="goal-group">Order</div>
      <label className="goal-work-order__priority">Priority<select disabled={busy} value={item.priority}
        onChange={(event) => arrange({ action: 'PRIORITIZE', priority: event.target.value as WorkItemPriority })}>
        <option value="HIGH">High</option><option value="NORMAL">Normal</option><option value="LOW">Low</option>
      </select></label>
      {place >= 0 && <div className="goal-work-actions"><span className="goal-muted">{place + 1} of {openSiblings.length} open</span>
        <button type="button" className="goal-link" disabled={busy || place === 0} onClick={() => move(-1)}>Move earlier</button>
        <button type="button" className="goal-link" disabled={busy || place === openSiblings.length - 1} onClick={() => move(1)}>Move later</button></div>}
      {waitsOn.map((other) => <div className="goal-result" key={other.id}><span>Waits on</span><span className="goal-result__title">{other.title}</span>
        <span>{other.state.toLowerCase()}</span>
        {!concluded && <button type="button" className="goal-link" disabled={busy} onClick={() => arrange({ action: 'UNBLOCK', blocker: other.id })}>Stop waiting</button>}
      </div>)}
      {waiting.map((other) => <div className="goal-result" key={other.id}><span>Blocks</span><span className="goal-result__title">{other.title}</span>
        <span>{other.state.toLowerCase()}</span></div>)}
      {!concluded && blockable.length > 0 && <label>Waits on<select disabled={busy} value="" onChange={(event) => arrange({ action: 'BLOCK', blocker: event.target.value })}>
        <option value="">Choose work…</option>{blockable.map((other) => <option key={other.id} value={other.id}>{other.title}</option>)}
      </select></label>}
    </section>}
    <div className="goal-group">Sources, attempts and delivery</div>
    {item.links.map((link) => <button type="button" className="goal-result goal-work-link" key={refKey(link)} onClick={() => props.onOpenItem(link)}>
      <SourceIcon source={GOAL_SOURCES[link.kind]} /><span>{link.label}</span><span className="goal-result__title">{link.title}</span><span>{link.status}</span>
    </button>)}
    {/* An ended task no longer blocks its issue or ticket: another attempt becomes the source's task, and this work keeps both. */}
    {!concluded && (issueNumber !== undefined || ticket !== undefined) && lastTask?.state === 'ENDED' && <button type="button" className="goal-link"
      onClick={() => issueNumber !== undefined ? props.onStartIssue(issueNumber, item.acceptanceCriteria, lastTask.key)
        : props.onStartTicket(ticket.key, item.acceptanceCriteria, lastTask.key)}>Start another attempt</button>}
    {!concluded && <button type="button" className="goal-link" onClick={() => setAdding((value) => !value)}>Link source, task or PR</button>}
    {adding && !concluded && <div><input aria-label="Search supporting work" value={candidates.query} onChange={(event) => candidates.setQuery(event.target.value)} />
      {candidates.results.map((reference) => <Candidate key={refKey(reference)} item={reference} onAdd={(link) => props.onLink(item, link).catch((failure: unknown) => {
        setError(failure instanceof Error ? failure.message : 'Could not link work');
      })} />)}</div>}
    <label>{concluded ? 'Reason to reopen' : 'Result, evidence and scope covered, or reason to drop'}
      <textarea maxLength={10000} value={detail} onChange={(event) => setDetail(event.target.value)} /></label>
    <div className="goal-work-actions">
      {item.state === 'PLANNED' && <button type="button" className="goal-add" disabled={busy} onClick={() => change('BEGIN')}>Mark working</button>}
      {!concluded && <button type="button" className="goal-add" disabled={busy || detail.trim() === '' || item.acceptanceCriteria.trim() === ''}
        onClick={() => change('ACCEPT')}>Accept result</button>}
      {!concluded && <button type="button" className="goal-link" disabled={busy || detail.trim() === ''} onClick={() => change('DROP')}
        title={openWaiting.length === 0 ? undefined : `${openWaiting.map((other) => other.title).join(', ')} will stop waiting on it`}>
        {openWaiting.length === 0 ? 'Drop from scope' : `Drop and unblock ${openWaiting.length}`}</button>}
      {concluded && <button type="button" className="goal-add" disabled={busy || detail.trim() === ''} onClick={() => change('REOPEN')}>Reopen</button>}
    </div>
    <p className="goal-muted">Accept result confirms all saved criteria are covered. Task completion and ticket closure alone do not complete this item.</p>
    <label>Goal<select value={targetGoal} onChange={(event) => setTargetGoal(event.target.value)}>
      <option value="">No Goal</option>
      {item.goalId !== null && !props.goals.some((goal) => goal.id === item.goalId) && <option value={item.goalId}>Archived Goal</option>}
      {props.goals.map((goal) => <option key={goal.id} value={goal.id}>{goal.title}</option>)}
    </select></label>
    <button type="button" className="goal-link" disabled={busy || targetGoal === (item.goalId ?? '')} onClick={() => change('MOVE')}>Move work item</button>
    <div className="goal-group">History</div>
    {[...item.history].reverse().filter((event) => event.action !== 'SUMMARY').map((event) => <div className="goal-work-history" key={event.operationId}>
      <span className="goal-muted">{relativeTime(event.at, { now: props.now })}</span><p>{event.detail}</p>
    </div>)}
  </div>;
}
