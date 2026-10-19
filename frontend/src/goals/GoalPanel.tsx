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

import { Ban, ChevronDown, FileText, Maximize2, MessageCircle, Minimize2, Pause, Play, Plus, RefreshCw, Search, X } from 'lucide-react';
import React, { createContext, useCallback, useContext, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { GoalIcon, SourceIcon } from '../cards/Card';
import Composer from '../Composer';
import { ConfirmDialog } from '../ConfirmDialog';
import MarkdownBody, { linkIssueNumbers } from '../pulls/MarkdownBody';
import { relativeTime } from '@bytequay/ui/relativeTime';
import type { ContextBuildViewDto } from '../repository-context/contracts';
import type { SpecCheckRequest } from '../repository-context/specChecks';
import { AgentModelControls, type ModelChoice } from '../settings/ModelsPage';
import { useNow } from '../useNow';
import { startEpicWork } from '../workspaces/epics';
import type {
  CompletionRule,
  CreateWorkItem,
  GoalCourseDraftDto,GoalCoverageDto,GoalDto,GoalExchangeDto,GoalGapDto,GoalItemDto,GoalKnowledgeNoteDto,GoalLessonDto,GoalProposalDto,GoalRecommendationDto,GoalTopicDto,TopicLevel,
  WorkItemDecision,
  WorkItemDto,
  WorkItemKind,WorkItemPriority,
} from './contracts';
import { TOPIC_LEVELS } from './contracts';
import { BookContents, ChapterBody, CourseOverview, courseLessons } from './LessonBook';
import {
  GOAL_SOURCES,STARTED_LABELS,WORK_VIEWS,addable,goalDirections,
  goalHistory,
  inOrder,isOpen,
  itemNeedsYou,
  openCourse,openPlan,openProposal,
  outcomeOf,
  preparedRequest,
  progressWidths,
  refKey,ringDash,
  situation,sourceOf,sourceUrl,summarize,
  unblocks,waitingOn,workIn,
  type WorkSource,type WorkView,
} from './model';
import type { GoalConversation, GoalModel } from './useGoals';

const GoalRepository = createContext<string | null>(null);

/** Goal references resolve titles using the workspace repository. */
function GoalText({ children }: { children: string }): React.JSX.Element {
  return <>{linkIssueNumbers(children, useContext(GoalRepository))}</>;
}

/** How much of a goal is finished, as a ring; an arc circles it while something runs for the goal. */
export function GoalRing({ goal, size = 20, running = false }: { goal: GoalDto | null; size?: number; running?: boolean }): React.JSX.Element {
  const dash = goal === null ? '0 44' : ringDash(summarize(goal, new Set()));
  return (
    <svg className="goal-ring" width={size} height={size} viewBox="0 0 20 20" aria-hidden="true">
      <circle cx="10" cy="10" r="7" fill="none" stroke="var(--line-subtle)" strokeWidth="3" />
      <circle cx="10" cy="10" r="7" fill="none" stroke="var(--fg-success)" strokeWidth="3" strokeDasharray={dash} />
      {running && <circle className="goal-ring__run" cx="10" cy="10" r="7" fill="none" strokeWidth="3" strokeLinecap="round" strokeDasharray="9 35" />}
    </svg>
  );
}

function fraction(goal: GoalDto): string {
  const summary = summarize(goal, new Set());
  return summary.total === 0 ? '' : `${summary.finished}/${summary.total}`;
}

export type GoalPanelView = 'guide' | 'lessons' | 'work';

const VIEW_NAMES: Record<GoalPanelView, string> = { guide: 'Guide', lessons: 'Lessons', work: 'Work' };

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
  /** Opens the local work item retaining a spec check's complete finding. */
  onOpenGap: (gap: GoalGapDto) => Promise<void>;
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
  // A chapter opened anywhere in the panel reads in the Lessons view.
  const openLesson = (lessonId: string | null): void => {
    props.onLesson(lessonId);
    if (lessonId !== null && view !== 'lessons') props.onView('lessons');
  };
  const reading = view === 'lessons' && goal !== null && goal.course != null;
  // A goal without a course has no lessons to read, so its Lessons view is the guide.
  const shown: GoalPanelView = view === 'lessons' && !reading ? 'guide' : view;
  const views: GoalPanelView[] = goal?.course != null ? ['guide', 'lessons', 'work'] : ['guide', 'work'];
  const guide = (lessonId?: string): React.JSX.Element => <GoalGuide key={goal?.id ?? 'next'} goal={goal} repository={props.repository} conversation={props.conversation}
    now={props.now} onStart={props.onStart} onOpenTask={props.onOpenTask} onLesson={openLesson} onBack={props.onBack} onStartIssue={props.onStartIssue}
    onStartTicket={props.onStartTicket} onSpecCheck={props.onSpecCheck} onOpenGap={props.onOpenGap} onReview={setSelected} lessonId={lessonId} />;
  return (
    <GoalRepository value={props.repository}>
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
            <div className="goal-panel__situation"><GoalText>{situation(goal, props.conversation.exchanges, props.conversation.gaps).text}</GoalText></div>
            {actionError !== null && <p className="goal-guide__error" role="alert">{actionError}</p>}
          </>
        )}
        {props.onBack === undefined && <div className="goal-panel__views">
          <div className="goal-tabs" role="tablist" aria-label="Goal views">
            {views.map((value) => <button type="button" role="tab" aria-selected={shown === value} key={value}
              className={`goal-tab${shown === value ? ' is-active' : ''}`} onClick={() => { setSelected(null); props.onView(value); }}>{VIEW_NAMES[value]}</button>)}
          </div>
          {goal !== null && props.conversation.model !== null && <GoalModelPicker model={props.conversation.model} onChoose={props.conversation.chooseModel} />}
        </div>}
      </div>
      {item !== undefined ? <WorkDetails key={item.id} {...props} item={item} onBack={() => setSelected(null)} />
        : reading ? (lessonItem !== undefined ? <LessonView key={lessonItem.id} goal={goal} item={lessonItem} repository={props.repository}
          conversation={props.conversation} ask={mode === 'board' ? guide(lessonItem.id) : undefined} onLesson={openLesson} />
          : mode === 'board' ? <div className="goal-book">
            <BookContents goal={goal} lessonId={null} sections={[]} onLesson={openLesson} onSection={() => {}} />
            <div className="goal-book__page"><CourseOverview goal={goal} onLesson={openLesson} /></div>
          </div>
          : <div className="goal-panel__body goal-lesson"><CourseOverview goal={goal} onLesson={openLesson} /></div>)
        : view === 'work' ? (goal === null ? <><NoGoal onCreate={props.onCreate} />{props.workItems.map((saved) => <button type="button" className="goal-work-card" key={saved.id} onClick={() => setSelected(saved.id)}>{saved.title} · {saved.state.toLowerCase()}</button>)}</> : <GoalWork {...props} goal={goal} onSelect={setSelected} />)
        : guide()}
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
    </GoalRepository>
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
function GoalGuide({ goal, repository, conversation, now, onStart, onOpenTask, onLesson, onBack, onStartIssue, onStartTicket, onSpecCheck, onOpenGap, onReview,
  lessonId }: Pick<GoalPanelProps, 'goal' | 'repository' | 'conversation' | 'now' | 'onStart' | 'onOpenTask' | 'onLesson' | 'onBack' | 'onStartIssue' | 'onStartTicket'
    | 'onSpecCheck' | 'onOpenGap'> & {
  onReview: (workItemId: string) => void;
  /** The lesson open beside the conversation, which the messages sent from here are about. */
  lessonId?: string;
}): React.JSX.Element {
  const { exchanges } = conversation;
  const [error, setError] = useState<string | null>(null);
  const last = exchanges.at(-1);
  const answering = last?.state === 'PENDING';
  const question = last?.state === 'ANSWERED' ? last.reply?.question ?? null : null;
  const open = openProposal(goal, exchanges);
  const course = openCourse(goal, exchanges);
  const plan = openPlan(goal, exchanges);
  const send = (operationId: string, message: string): Promise<unknown> => (goal === null ? onStart(operationId, message) : conversation.ask(operationId, message, lessonId));
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
  const composer = <GoalComposer answering={answering} focus={opening} picked={answers} seed={seed} onSent={() => setPicked({ question: '', answers: [] })}
    placeholder={answers.length > 0 ? 'Add your own words, or just send' : opening ? 'I want to…' : question !== null ? 'Answer in your own words'
      : lessonId === undefined ? 'Ask about this goal' : 'Ask about this lesson'}
    onSend={send} />;
  const errorText = error ?? conversation.error;
  const alert = errorText !== null && <p className="goal-guide__error" role="alert">{errorText}</p>;
  // The whole conversation scrolls as one, ending with the question and its answers; only a decision and the reply field stay under it.
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
        {goal !== null && goal.description !== '' && <div className="goal-panel__desc"><GoalText>{goal.description}</GoalText></div>}
        {goal?.definition != null && <Definition definition={goal.definition} />}
        {goal?.course != null && <Course goal={goal} onLesson={onLesson} />}
        {conversation.notes.length > 0 && <Notes notes={conversation.notes} onRemember={conversation.rememberNote} />}
        {goal !== null && goal.topics.length > 0 && <Known topics={goal.topics} onCorrect={conversation.correctTopic} />}
        {conversation.gaps.length > 0 && <Gaps gaps={conversation.gaps} onAdd={conversation.addGap} onFile={onOpenGap} onReview={onReview} />}
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
              {exchange.automatic ? <div className="goal-thread__event" title={exchange.message}><GoalText>{exchange.message}</GoalText></div>
                : <div className="goal-thread__user"><GoalText>{exchange.message}</GoalText></div>}
              {exchange.state === 'PENDING' && <Pending exchange={exchange} now={now} onStop={conversation.stop} />}
              {exchange.state === 'FAILED' && <div className="goal-thread__agent is-failed">
                <span>{exchange.failure}</span>
                {exchange === last && <button type="button" className="goal-link" onClick={() => {
                  setError(null);
                  void conversation.retry(exchange.id).catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'Could not ask again'));
                }}>Ask again</button>}
              </div>}
              {exchange.reply !== null && <div className="goal-thread__agent">
                <p><GoalText>{exchange.reply.assessment}</GoalText></p>
                {/* The newest question is asked last, with its answers. */}
                {exchange.reply.question !== null && exchange !== last && <p className="goal-thread__question"><GoalText>{exchange.reply.question.text}</GoalText></p>}
                {exchange.coverage && <AnswerCoverage coverage={exchange.coverage} />}
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
        {plan !== null && <section className="goal-proposal" aria-label="Proposed plan change">
          <div className="goal-proposal__label">Proposed plan change</div>
          <p className="goal-course__reason"><GoalText>{plan.reason}</GoalText></p>
          <ul className="goal-plan">{plan.differences.map((difference) => <li key={difference}><GoalText>{difference}</GoalText></li>)}</ul>
        </section>}
        {question !== null && <section className="goal-guide__asked" aria-label="The agent's question">
          <div className="goal-guide__question"><GoalText>{question.text}</GoalText></div>
          {question.suggestions.length > 0 && <Choices choices={question.suggestions} picked={answers} disabled={answering} onToggle={toggle}>
            <button type="button" className="goal-link goal-guide__other" disabled={answering} onClick={() => say(OTHER_OPTIONS)}>Other options</button>
          </Choices>}
        </section>}
        {!docked && alert}
      </div>
      {docked && <div className="goal-guide__dock">
        {open !== null && <Decision key={open.exchangeId} label="Accept goal" ready={title.trim() !== ''} answering={answering} failure="The goal was not saved"
          onAccept={() => conversation.accept(open.exchangeId, title.trim())} onAdjust={adjust} onSkip={skip} />}
        {course !== null && <Decision key={course.exchangeId} label={goal?.course == null ? 'Accept course' : 'Accept revision'} ready answering={answering}
          failure="The course was not saved" onAccept={() => conversation.acceptCourse(course.exchangeId)} onAdjust={adjust} onSkip={skip} />}
        {plan !== null && <Decision key={plan.exchangeId} label="Accept plan change" ready answering={answering} failure="The plan change was not saved"
          onAccept={() => conversation.acceptPlan(plan.exchangeId)} onAdjust={adjust} onSkip={skip} />}
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
  onFile: (gap: GoalGapDto) => Promise<void>;
  onReview: (workItemId: string) => void;
}): React.JSX.Element {
  const [note, setNote] = useState<string | null>(null);
  const failed = (fallback: string) => (failure: unknown): void => setNote(failure instanceof Error ? failure.message : fallback);
  return (
    <section className="goal-gaps" aria-label="Reproduced gaps">
      <div className="goal-group">Gaps the spec check reproduced · {gaps.length}</div>
      {gaps.map((gap) => <div key={gap.candidateId} className="goal-gap">
        <span className="goal-gap__title" title={gap.rule}><GoalText>{gap.title}</GoalText></span>
        {gap.test !== null && <span className="goal-muted">{gap.test.path}</span>}
        {gap.draft !== null && <button type="button" className="goal-link" onClick={() => {
          setNote(null);
          void onFile(gap).catch(failed('The local work item did not open'));
        }}>Open local work item</button>}
        {gap.workItemId === null
          ? <button type="button" className="goal-link" onClick={() => { setNote(null); void onAdd(gap.candidateId).catch(failed('The gap was not added')); }}>
            Add to goal</button>
          : <button type="button" className="goal-link" onClick={() => onReview(gap.workItemId)}>Work: {gap.state?.toLowerCase() ?? 'open'}</button>}

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
      <span>{STARTED_LABELS[card.card.kind]}: <GoalText>{card.card.title}</GoalText></span>
      {card.card.kind === 'SPEC_CHECK' && card.card.module !== null && <button type="button" className="goal-link"
        onClick={() => onSpecCheck({ directory: card.card.module })}>Open check</button>}
      {card.card.kind === 'RESEARCH' && card.taskId !== null && <ResearchProgress taskId={card.taskId} read={research.researchRun} stop={research.stopResearch} />}
      {card.card.kind === 'LESSON' && card.card.workItemId !== null && <button type="button" className="goal-link"
        onClick={() => onLesson(card.card.workItemId)}>Open lesson</button>}
      {card.card.kind === 'INVESTIGATE' && work !== undefined && <button type="button" className="goal-link" onClick={() => onReview(work.id)}>Open work</button>}
      {pull !== undefined && <span className="goal-muted"><GoalText>{pull.label}</GoalText> {pull.status.toLowerCase()}</span>}
      {work !== undefined && pull?.state === 'MERGED' && <button type="button" className="goal-link" onClick={() => onReview(work.id)}>Review result</button>}
      {card.card.kind === 'WORK' && (task !== undefined
        ? <button type="button" className="goal-link" onClick={() => onOpenTask(task.key)}>Open task</button>
        : <button type="button" className="goal-link" onClick={() => {
          if (card.card.issue !== null) onStartIssue(card.card.issue, preparedRequest(goal, work ?? null, card.card));
          else if (card.card.ticket !== null) onStartTicket(card.card.ticket, preparedRequest(goal, work ?? null, card.card));
        }}>Open the composer</button>)}
    </div>
  );
}

/** What an answer rested on, as counts, said in words on hover; a count of zero shows nothing. */
function AnswerCoverage({ coverage }: { coverage: GoalCoverageDto }): React.JSX.Element | null {
  const notes = coverage.notesInFull + coverage.notesByTopic;
  const read = [notes === 0 ? null : `${coverage.notesInFull} of ${notes} notes in full${coverage.notesByTopic === 0 ? '' : ' and the rest by topic'}`,
    coverage.questions === 0 ? null : `${coverage.questions} of your questions`,
    coverage.candidates === 0 ? null : `${coverage.candidates} practice candidates from ${coverage.issuesOpen} open issues`].filter((part) => part !== null);
  if (read.length === 0) return null;
  const said = `This answer read ${read.join('; ')}.`;
  return (
    <div className="goal-thread__coverage" role="note" aria-label={said} title={said}>
      {notes > 0 && <span aria-hidden="true"><FileText size={12} />{coverage.notesInFull}/{notes}</span>}
      {coverage.questions > 0 && <span aria-hidden="true"><MessageCircle size={12} />{coverage.questions}</span>}
      {coverage.candidates > 0 && <span aria-hidden="true"><Search size={12} />{coverage.candidates}</span>}
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

/**
 * One lesson, read as a chapter written once from the goal's notes: a summary, what to remember, short sections with diagrams, an exercise
 * and a check. The expanded window shows it as a book, its contents at the left and the questions about it at the right on demand; the
 * floating window shows the chapter alone with its questions under it. Done is the user's decision.
 */
function LessonView({ goal, item, repository, conversation, ask, onLesson }: {
  goal: GoalDto;
  item: WorkItemDto;
  repository: string;
  conversation: GoalConversation;
  /** The conversation the expanded window opens beside the chapter, whose messages are then about it; the floating window has none. */
  ask?: React.ReactNode;
  /** Opens another chapter, or the course's overview for null. */
  onLesson: (lessonId: string | null) => void;
}): React.JSX.Element {
  const { openLesson, readLesson, finishLesson } = conversation;
  const [lesson, setLesson] = useState<GoalLessonDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [operation] = useState(() => crypto.randomUUID());
  const [busy, setBusy] = useState(false);
  const [asking, setAsking] = useState(false);
  const page = useRef<HTMLDivElement>(null);
  const part = goal.course?.parts.find((candidate) => candidate.lessons.includes(item.id))?.title ?? '';
  const open = useCallback((again = false): void => {
    setError(null);
    void openLesson(item.id, again).then(setLesson, (failure: unknown) => setError(failure instanceof Error ? failure.message : 'The lesson could not be opened'));
  }, [item.id, openLesson]);
  useEffect(() => open(), [open]);
  const writing = lesson?.state === 'WRITING';
  useEffect(() => {
    if (!writing) return;
    const timer = window.setInterval(() => { void readLesson(item.id).then(setLesson, () => {}); }, 1_500);
    return () => window.clearInterval(timer);
  }, [item.id, readLesson, writing]);
  const questions = conversation.exchanges.filter((exchange) => exchange.lessonId === item.id);
  const answering = conversation.exchanges.at(-1)?.state === 'PENDING';
  const content = lesson?.content ?? null;
  const order = courseLessons(goal);
  const at = order.findIndex((candidate) => candidate.id === item.id);
  const previous = at > 0 ? order[at - 1] : undefined;
  const next = at >= 0 && at < order.length - 1 ? order[at + 1] : undefined;
  const chapter = (
    <article className="goal-chapter">
      <header className="goal-chapter__head">
        <div className="goal-chapter__bar">
          <button type="button" className="goal-link goal-lesson__back" onClick={() => onLesson(null)}>All chapters</button>
          {ask !== undefined && <button type="button" className={`goal-chapter__ask${asking ? ' is-active' : ''}`} aria-pressed={asking}
            onClick={() => setAsking((value) => !value)}>
            <MessageCircle size={14} strokeWidth={1.8} aria-hidden="true" />Ask{questions.length > 0 && <span className="goal-muted">{questions.length}</span>}
          </button>}
        </div>
        <div className="goal-lesson__part"><GoalText>{part}</GoalText></div>
        <h1 className="goal-chapter__title"><GoalText>{item.title}</GoalText></h1>
        <div className="goal-lesson__outcome">After this chapter you can: <GoalText>{item.acceptanceCriteria}</GoalText></div>
      </header>
      {lesson === null && error === null && <p className="goal-muted" role="status">Opening the lesson…</p>}
      {writing && <p className="goal-muted" role="status">Writing the chapter from the goal’s notes…</p>}
      {lesson?.state === 'FAILED' && <p className="goal-guide__error" role="alert">{lesson.failure}{' '}
        <button type="button" className="goal-link" onClick={() => open()}>Write again</button></p>}
      {content !== null && <ChapterBody content={content} repository={repository} />}
      {content !== null && <section className="goal-chapter__check" aria-label="Check yourself">
        <div className="goal-chapter__kicker">Check yourself</div>
        <p><GoalText>{content.check}</GoalText></p>
        <GoalComposer answering={answering} placeholder="Your answer" submitLabel="Answer" onSend={(operationId, message) => {
          // The agent's word on the answer shows with the questions.
          setAsking(true);
          return conversation.ask(operationId, CHECK_ANSWER + message, item.id);
        }} />
      </section>}
      {error !== null && <p className="goal-guide__error" role="alert">{error}</p>}
      <footer className="goal-chapter__foot">
        {item.state === 'DONE' ? <span className="goal-muted">Done</span> : <button type="button" className="goal-add goal-lesson__done" disabled={busy || content === null}
          onClick={() => {
            setBusy(true); setError(null);
            void finishLesson(item.id, operation).catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'The lesson was not marked done'))
              .finally(() => setBusy(false));
          }}>{busy ? 'Saving…' : 'Mark lesson done'}</button>}
        {content !== null && <button type="button" className="goal-link" title="Write this chapter again from the goal’s current notes, map and code"
          onClick={() => open(true)}>Write again</button>}
        <span className="goal-spacer" />
        {previous !== undefined && <button type="button" className="goal-link" onClick={() => onLesson(previous.id)}>← {previous.title}</button>}
        {next !== undefined && <button type="button" className="goal-add" onClick={() => onLesson(next.id)}>Next: {next.title} →</button>}
      </footer>
    </article>
  );
  if (ask === undefined) {
    return (
      <div className="goal-panel__body goal-lesson">
        {chapter}
        {questions.length > 0 && <ol className="goal-thread" aria-label="Questions about this lesson">{questions.map((exchange) => <li key={exchange.id} className="goal-thread__turn">
          <div className="goal-thread__user"><GoalText>{exchange.message}</GoalText></div>
          {exchange.state === 'PENDING' && <Pending exchange={exchange} onStop={conversation.stop} />}
          {exchange.state === 'FAILED' && <div className="goal-thread__agent is-failed">{exchange.failure}</div>}
          {exchange.reply !== null && <div className="goal-thread__agent"><p><GoalText>{exchange.reply.assessment}</GoalText></p></div>}
        </li>)}</ol>}
        <GoalComposer answering={answering} placeholder="Ask about this lesson" onSend={(operationId, message) => conversation.ask(operationId, message, item.id)} />
      </div>
    );
  }
  return (
    <div className="goal-book">
      <BookContents goal={goal} lessonId={item.id} sections={content?.sections.map((section) => section.title) ?? []} onLesson={onLesson}
        onSection={(index) => {
          const box = page.current;
          const target = box?.querySelector(`[data-section="${index}"]`);
          if (box == null || target == null) return;
          box.scrollTop += target.getBoundingClientRect().top - box.getBoundingClientRect().top - TURN_MARGIN;
        }} />
      <div className="goal-book__page" ref={page}>{chapter}</div>
      {asking && <aside className="goal-book__ask" aria-label="Questions about this chapter">{ask}</aside>}
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
      {course.reason !== null && <p className="goal-course__reason"><GoalText>{course.reason}</GoalText></p>}
      {course.parts.map((part, index) => <div key={part.title} className="goal-course__part">
        <div className="goal-course__title">{index + 1}. <GoalText>{part.title}</GoalText></div>
        <ol className="goal-course__lessons">{part.lessons.map((lesson) => {
          const existing = lesson.lessonId === null ? undefined : lessons.get(lesson.lessonId);
          return <li key={lesson.lessonId ?? lesson.title} className={existing?.state === 'DONE' ? 'is-done' : undefined}>
            <span><GoalText>{existing?.title ?? lesson.title}</GoalText>{accepted !== null && existing === undefined && <span className="goal-course__new">New</span>}</span>
            <span className="goal-course__outcome"><GoalText>{existing === undefined ? lesson.outcome : existing.state === 'DONE' ? 'Done' : existing.acceptanceCriteria}</GoalText></span>
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
        <div className="goal-course__title">{index + 1}. <GoalText>{part.title}</GoalText></div>
        <ol className="goal-course__lessons">{part.lessons.map((id) => lessons.get(id)).filter((lesson) => lesson !== undefined).map((lesson) =>
          <li key={lesson.id} className={`is-${lesson.state.toLowerCase()}`}>
            <button type="button" className="goal-course__open" onClick={() => onLesson(lesson.id)}>{lesson.title}</button>
            <span className="goal-course__outcome"><GoalText>{lesson.state === 'DONE' ? 'Done' : lesson.acceptanceCriteria}</GoalText></span>
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
        <span className="goal-known__topic"><GoalText>{topic.topic}</GoalText></span>
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

/**
 * What the goal's research saved, folded under its count, each note with the pages and files it rests on. A note citing a specification
 * page can be remembered as a SPEC memory of the workspace; memory keeps no notes on how code works.
 */
function Notes({ notes, onRemember }: { notes: GoalKnowledgeNoteDto[]; onRemember?: (noteId: string) => Promise<void> }): React.JSX.Element {
  const [remembering, setRemembering] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const remember = (noteId: string): void => {
    if (onRemember === undefined) return;
    setRemembering(noteId);
    setError(null);
    void onRemember(noteId).catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'The note was not remembered'))
      .finally(() => setRemembering(null));
  };
  return (
    <details className="goal-notes goal-fold" aria-label="Knowledge notes">
      <summary>What the agent learned · {notes.length}</summary>
      {notes.map((note) => <details key={note.id} className="goal-note">
        <summary><GoalText>{note.topic}</GoalText>{note.stale && <span className="goal-note__stale" title="A file it cites changed since the research read it">Stale</span>}</summary>
        <p><GoalText>{note.body}</GoalText></p>
        <ul>{note.sources.map((source) => <li key={source.reference + (source.commit ?? '')}>
          <a href={sourceUrl(source)} target="_blank" rel="noreferrer">{source.commit === null ? source.reference : `${source.reference} @ ${source.commit.slice(0, 7)}`}</a>
        </li>)}</ul>
        {note.memoryId ? <p className="goal-muted">Remembered as {note.memoryId}</p>
          : onRemember !== undefined && note.sources.some((source) => source.commit === null) && <button type="button" className="goal-link"
            disabled={remembering !== null} title="Keep it as a specification memory of this workspace" onClick={() => remember(note.id)}>Remember</button>}
      </details>)}
      {error !== null && <p className="goal-guide__error" role="alert">{error}</p>}
    </details>
  );
}

const NONE_PICKED: string[] = [];
/** What Other options sends: the agent asks the same question again with answers the user has not passed over. */
const OTHER_OPTIONS = 'None of these fit. Show me other options.';
const SKIP_PROPOSAL = 'Skip this proposal for now.';
/** What an answer to a lesson's check starts with, so the agent reads it as the answer to the open lesson's check. */
const CHECK_ANSWER = 'My answer to the check: ';
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
      <dt>Success means</dt><dd><GoalText>{definition.successDefinition}</GoalText></dd>
      {definition.startingKnowledge !== '' && <><dt>You already know</dt><dd><GoalText>{definition.startingKnowledge}</GoalText></dd></>}
      {definition.constraints !== '' && <><dt>Constraints</dt><dd><GoalText>{definition.constraints}</GoalText></dd></>}
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
        <dt>Objective</dt><dd><GoalText>{proposal.objective}</GoalText></dd>
        <dt>Success means</dt><dd><GoalText>{proposal.successDefinition}</GoalText></dd>
        {proposal.startingKnowledge !== '' && <><dt>You already know</dt><dd><GoalText>{proposal.startingKnowledge}</GoalText></dd></>}
        {proposal.constraints !== '' && <><dt>Constraints</dt><dd><GoalText>{proposal.constraints}</GoalText></dd></>}
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
function GoalComposer({ answering, focus = false, picked = NONE_PICKED, seed = null, placeholder, submitLabel = 'Send', onSend, onSent }: {
  answering: boolean;
  focus?: boolean;
  picked?: string[];
  /** Words to start the reply with, such as Adjust's, which take the field and its focus. */
  seed?: { text: string } | null;
  placeholder: string;
  submitLabel?: string;
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
    <Composer className="goal-composer" inputRef={field} aria-label="Message the goal's agent" rows={2} maxLength={4000}
      value={draft} placeholder={placeholder} autoFocus={focus} data-goal-focus={focus || undefined}
      onChange={event => setDraft(event.target.value)} onSubmit={submit} busy={busy}
      submitEnabled={!busy && !answering && (draft.trim() !== '' || picked.length > 0)}
      submitLabel={busy ? 'Sending…' : submitLabel} submitContent={busy ? 'Sending…' : submitLabel}
      notice={error !== null && <p className="goal-guide__error" role="alert">{error}</p>}>
      <span className="agt-spacer" />
    </Composer>
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

export function Candidate({ item, onAdd, board, sub = board ? item.status : undefined, action = 'Add' }: {
  item: GoalItemDto;
  onAdd: (item: GoalItemDto) => Promise<unknown>;
  board?: boolean;
  /** Where the item stands, beside its title; a board shows its status. */
  sub?: string;
  action?: string;
}): React.JSX.Element {
  return (
    <div className="goal-result">
      <SourceIcon source={GOAL_SOURCES[item.kind]} tinted />
      <span className="card-key">{item.label}</span>
      <span className="goal-result__title">{item.title}</span>
      {sub !== undefined && <span className="goal-result__sub">{sub}</span>}
      <button type="button" className="goal-link" onClick={() => { void onAdd(item).catch(() => {}); }}>{action}</button>
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

// The work view shows the latest decisions; the rest of the history opens on demand.
const RECENT_DECISIONS = 5;

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
  const history = goalHistory(goal);
  const [allHistory, setAllHistory] = useState(false);
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
    {history.length > 0 && <div><div className="goal-group">{allHistory ? 'History' : 'Recent decisions'}</div>
      {(allHistory ? history : history.slice(0, RECENT_DECISIONS)).map(({ item, kind, text, at }, index) => <div className="goal-activity__row" key={`${item.id}-${index}`}>
        <span className="goal-activity__kind">{kind}</span>
        <span className="goal-activity__text"><button type="button" className="goal-link" onClick={() => onSelect(item.id)}>{item.title}</button>
          {text !== '' && <> · <GoalText>{text}</GoalText></>}</span>
        <span className="goal-activity__age">{relativeTime(at, { now: props.now })}</span>
      </div>)}
      {history.length > RECENT_DECISIONS && <button type="button" className="goal-link" onClick={() => setAllHistory((value) => !value)}>
        {allHistory ? 'Recent decisions only' : `All history (${history.length})`}</button>}
    </div>}
  </div>;
}

/** A work card says why it stands where it does: the user's priority, a source's own priority, the work waiting on it and what it waits on. */
function WorkCard({ item, all, needsYou, onSelect }: { item: WorkItemDto; all: WorkItemDto[]; needsYou: ReadonlySet<string>; onSelect: (id: string) => void }):
  React.JSX.Element {
  const waiting = unblocks(item, all);
  const waits = isOpen(item) ? waitingOn(item, all) : [];
  const outcome = outcomeOf(item);
  const reopened = item.history.filter((event) => event.action === 'REOPEN').length;
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
    {outcome !== null && <span className="goal-work-card__outcome" title={outcome}>{`${item.state === 'DONE' ? 'Result' : 'Dropped'}: ${outcome}`}</span>}
    {reopened > 0 && <span className="goal-muted">{reopened === 1 ? 'Reopened once' : `Reopened ${reopened} times`}</span>}
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
  // A task for this work opens with a request prepared from its accepted scope, the card it came from and its goal.
  const goal = props.goal !== null && props.goal.id === item.goalId ? props.goal : null;
  const request = (): string => preparedRequest(goal, item, goal?.recommendations.find((found) => found.workItemId === item.id)?.card ?? null);
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
    <strong><GoalText>{item.title}</GoalText></strong><span>{item.state.toLowerCase()} · {item.kind.toLowerCase()}</span>
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
      {waitsOn.map((other) => <div className="goal-result" key={other.id}><span>Waits on</span><span className="goal-result__title"><GoalText>{other.title}</GoalText></span>
        <span>{other.state.toLowerCase()}</span>
        {!concluded && <button type="button" className="goal-link" disabled={busy} onClick={() => arrange({ action: 'UNBLOCK', blocker: other.id })}>Stop waiting</button>}
      </div>)}
      {waiting.map((other) => <div className="goal-result" key={other.id}><span>Blocks</span><span className="goal-result__title"><GoalText>{other.title}</GoalText></span>
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
    {!concluded && !item.isEpic && (issueNumber !== undefined || ticket !== undefined) && lastTask?.state === 'ENDED' && <button type="button" className="goal-link"
      onClick={() => issueNumber !== undefined ? props.onStartIssue(issueNumber, request(), lastTask.key)
        : props.onStartTicket(ticket.key, request(), lastTask.key)}>Start another attempt</button>}
    {/* Work without an issue or ticket opens the task composer itself, the same hand-off an Epic's Work tab uses; its task is the work's attempt. */}
    {!concluded && !item.isEpic && item.kind !== 'LEARN' && issue === undefined && ticket === undefined && (lastTask === undefined || lastTask.state === 'ENDED')
      && <button type="button" className="goal-link" onClick={() => startEpicWork({ workspaceId: item.workspaceId, itemId: item.id, draft: request() })}>
        {lastTask === undefined ? 'Start a task' : 'Start another attempt'}</button>}
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
      <span className="goal-muted">{relativeTime(event.at, { now: props.now })}</span><p><GoalText>{event.detail}</GoalText></p>
    </div>)}
  </div>;
}
