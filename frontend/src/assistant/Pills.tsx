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
import { useEffect, useState } from 'react';
import type { RunningTask } from '../agents/runningTaskModel';
import { doneRatio, showsToday } from '../home/today';
import { useTodayAgenda, type TodayAgenda } from '../home/useTodayAgenda';
import { pollVisible } from '../ide/pollVisible';
import { approvalTimeLeft, type AppNotice, type NoticeSnapshot } from '../notices/contracts';
import { useNow } from '../useNow';
import type { AgendaDto } from '../types';
import { PILLS, soonestDeadline, waitingCards, type Pill, type WaitingCard } from './model';

const RUNNING_POLL_MS = 5000;

const LABELS: Record<Pill, string> = { all: 'All', decision: 'Decision', running: 'Running', finished: 'Finished', today: 'Today', mywork: 'My work' };
const TITLES: Record<Pill, string> = {
  all: 'Decision, Running and Finished in one view', decision: 'Waiting for your answer, soonest first', running: 'Agent tasks at work',
  finished: 'Results you have not seen', today: 'Today’s plan', mywork: 'My work',
};

/** Running tasks in every repository, as the sidebar lists them; null until the first read answers. */
function useRunningTasks(): RunningTask[] | null {
  const [tasks, setTasks] = useState<RunningTask[] | null>(null);
  useEffect(() => pollVisible(async () => {
    try { setTasks(await window.bridge.listRunningTasks()); }
    catch { /* The last list stays through a failed read. */ }
  }, RUNNING_POLL_MS), []);
  return tasks;
}

/** The task notices the window holds, read once and then as they change. */
export function useNotices(): AppNotice[] {
  const [notices, setNotices] = useState<AppNotice[]>([]);
  useEffect(() => {
    let received = false;
    const show = (state: NoticeSnapshot) => setNotices(state.notices);
    const stop = window.bridge.onAppNotices?.(state => { received = true; show(state); });
    void window.bridge.readAppNotices?.().then(state => { if (!received) show(state); }).catch(() => {});
    return stop;
  }, []);
  return notices;
}

/** `ready` once running tasks were read, so an empty Running means none rather than not read yet. */
export type Waiting = { cards: WaitingCard[]; today: TodayAgenda; agenda: AgendaDto | null; ready: boolean };

/** What the pills count and open: the waiting cards and today's agenda. */
export function useWaiting(notices: AppNotice[]): Waiting {
  const today = useTodayAgenda();
  const tasks = useRunningTasks();
  const agenda = today.agenda !== null && today.agenda.state !== 'NONE' && showsToday(today.agenda, 'global') ? today.agenda : null;
  return { cards: waitingCards(notices, tasks ?? []), today, agenda, ready: tasks !== null };
}

/**
 * The pills under a composer, each a door to one view: a count only where it changes what to do next, zero counts hidden, and an empty
 * pill dim and opening nothing. Decision says when its soonest card stops waiting, in the warning colour near the end; My work shows a
 * dot while Needs you holds work.
 */
export default function AssistantPills({ waiting, needsYou, open, onPick }: {
  waiting: Waiting;
  needsYou: boolean;
  open: Pill | null;
  onPick: (pill: Pill) => void;
}) {
  const { cards, agenda } = waiting;
  const count = (group: WaitingCard['group']) => cards.filter(card => card.group === group).length;
  const soonest = soonestDeadline(cards);
  const now = useNow(soonest !== null);
  const left = soonest === null ? null : approvalTimeLeft(soonest, now);
  const ratio = agenda === null ? null : doneRatio(agenda.items);
  const counts: Record<Pill, string | null> = {
    all: null, decision: String(count('decision')), running: String(count('running')), finished: String(count('finished')),
    today: ratio === null || ratio.total === 0 ? null : `${ratio.done}/${ratio.total}`, mywork: null,
  };
  const empty = (pill: Pill): boolean => pill === 'all' ? cards.length === 0 : pill === 'today' ? agenda === null
    : pill === 'mywork' ? false : counts[pill] === '0';
  return (
    <div className="assistant-pills" role="group" aria-label="Assistant views">
      {PILLS.map(pill => {
        const none = empty(pill);
        const urgent = pill === 'decision' && left?.urgent === true;
        return (
          <button key={pill} type="button" data-pill={pill} aria-disabled={none} aria-pressed={open === pill}
            className={`assistant-pill${open === pill ? ' is-open' : ''}${none ? ' is-empty' : ''}${urgent ? ' is-urgent' : ''}`}
            title={none ? 'Nothing here' : pill === 'mywork' && needsYou ? 'Needs you has work' : TITLES[pill]}
            onClick={() => { if (!none) onPick(pill); }}>
            {LABELS[pill]}
            {!none && counts[pill] !== null && pill !== 'all' && <span className="assistant-pill__count">{counts[pill]}</span>}
            {pill === 'decision' && left !== null && <><span className="assistant-pill__count">·</span><span className="assistant-pill__left">{left.text}</span></>}
            {pill === 'mywork' && needsYou && <span className="assistant-pill__dot" aria-label="Needs you has work" />}
          </button>
        );
      })}
    </div>
  );
}
