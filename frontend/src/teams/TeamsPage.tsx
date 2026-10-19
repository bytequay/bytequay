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
import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { Check, ChevronDown, LockKeyhole, Pencil, Plus, RefreshCw, TriangleAlert, UserPlus, Users, X } from 'lucide-react';
import Avatar from '../Avatar';
import { ConfirmDialog } from '../ConfirmDialog';
import Toast from '../Toast';
import { remoteErrorMessage } from '../remoteError';
import { usePopoverDismiss } from '@bytequay/ui/usePopoverDismiss';
import { ago } from '@bytequay/ui/relativeTime';
import { ACTIVITY_TYPES, METRICS, membersOf, people, type ActivityType, type Metric } from './activity';
import CircleActivity, { ACTIVITY_LIMIT_MAX } from './CircleActivity';
import CircleDialog, { CircleIcon } from './CircleDialog';
import CircleInsights from './CircleInsights';
import CircleMembers from './CircleMembers';
import { parseTeamCreateInput } from './contracts';
import NeedsYou, { useLiveAttention, type Notify } from './NeedsYou';
import PersonPanel from './PersonPanel';
import { circleNeeds } from './priority';
import TeamPeoplePicker from './TeamPeoplePicker';
import type { CircleAvatar, TeamActivityFilter, TeamCircle, TeamCoverage, TeamCreateInput, TeamDirectory, TeamMember, TeamMemberInput } from './types';
import { GITHUB_ACCOUNT_CHANGED, updateTeams, useTeams } from './useTeams';
import { useTeamActivity } from './useTeamActivity';
import './teams.css';

type Tab = 'needs' | 'activity' | 'members' | 'insights';
const TABS: [Tab, string][] = [['needs', 'Needs you'], ['activity', 'Activity'], ['members', 'Members'], ['insights', 'Insights']];
type Popover = 'switcher' | 'add' | 'person' | 'coverage' | 'circles' | null;
type Editing = { circle: TeamCircle | null; name: string } | null;
type ToastState = { id: number; text: string; undo?: () => void };
type View = { circleId: string | null; tab: Tab };
const TEMPLATES = ['Internal engineering', 'Trusted OSS', 'Connector maintainers'];
const PERIODS = [7, 30, 90];
const VIEW_KEY = 'bytequay.circles.view.v1';
const ALL_CIRCLES: CircleAvatar = { color: 'gray', shape: 'circle' };

const scopeShort = (circle: TeamCircle) => circle.repositories.length === 0 ? 'Any repository'
  : `${circle.repositories[0].split('/')[1]}${circle.repositories.length > 1 ? ` +${circle.repositories.length - 1}` : ''}`;

function savedViews(): Record<string, unknown> {
  try {
    const saved: unknown = JSON.parse(window.localStorage.getItem(VIEW_KEY) ?? '{}');
    return typeof saved === 'object' && saved !== null ? saved as Record<string, unknown> : {};
  }
  catch {
    return {};
  }
}

/** The circle and view an account used last; the first visit opens All circles on Needs you. */
function loadView(account: string): View {
  const view = savedViews()[account.toLowerCase()];
  if (typeof view !== 'object' || view === null) return { circleId: null, tab: 'needs' };
  const { circleId, tab } = view as Record<string, unknown>;
  return { circleId: typeof circleId === 'string' ? circleId : null, tab: TABS.find(([id]) => id === tab)?.[0] ?? 'needs' };
}

function saveView(account: string, view: View): void {
  try { window.localStorage.setItem(VIEW_KEY, JSON.stringify({ ...savedViews(), [account.toLowerCase()]: view })); }
  catch { /* The remembered view is a convenience; the page works without it. */ }
}

/** Opens the Circles page on one circle, keeping the account's last tab. */
export function openCircle(account: string, circleId: string): void {
  saveView(account, { ...loadView(account), circleId });
  window.location.hash = '#/teams';
}

/** Moves focus through a list of buttons with the arrow keys, as the circle menu and the views do. */
function arrowFocus(event: ReactKeyboardEvent<HTMLElement>, keys: [string, string], select?: (button: HTMLButtonElement) => void): void {
  if (event.key !== keys[0] && event.key !== keys[1]) return;
  const buttons = [...event.currentTarget.querySelectorAll<HTMLButtonElement>(':scope > button')];
  const next = buttons[Math.max(0, Math.min(buttons.length - 1, buttons.indexOf(document.activeElement as HTMLButtonElement) + (event.key === keys[1] ? 1 : -1)))];
  if (!next) return;
  event.preventDefault();
  next.focus();
  select?.(next);
}

export default function TeamsPage() {
  const { directory, loading, error: directoryError } = useTeams();
  const account = directory?.accountLogin;
  const viewerLogin = account ?? null;
  const circles = directory?.circles ?? [];
  const [view, setView] = useState<View & { account?: string }>({ circleId: null, tab: 'needs' });
  const [person, setPerson] = useState<number | null>(null);
  const [period, setPeriod] = useState<number | 'custom'>(30);
  const [customDays, setCustomDays] = useState(14);
  const [type, setType] = useState<ActivityType>('all');
  const [metric, setMetric] = useState<Metric | null>(null);
  const [limit, setLimit] = useState(100);
  const [inspect, setInspect] = useState<number | null>(null);
  const [popover, setPopover] = useState<Popover>(null);
  const [editing, setEditing] = useState<Editing>(null);
  const [deleting, setDeleting] = useState<TeamCircle | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<ToastState | null>(null);
  const toastId = useRef(0);
  const currentAccount = useRef(account);
  currentAccount.current = account;

  useLayoutEffect(() => {
    if (account && view.account !== account) setView({ account, ...loadView(account) });
  }, [account, view.account]);
  useEffect(() => {
    if (account && view.account === account) saveView(account, { circleId: view.circleId, tab: view.tab });
  }, [account, view]);

  const circle = circles.find(item => item.id === view.circleId) ?? null;
  const tab = view.tab;
  const scope = circle ? [circle] : circles;
  const members = membersOf(scope);
  const everyone = membersOf(circles);
  const byId = new Map(everyone.map(member => [member.githubId, member]));
  const logins = new Set(everyone.map(member => member.login.toLowerCase()));
  const personMember = members.find(member => member.githubId === person) ?? null;
  const inspected = inspect === null ? null : byId.get(inspect) ?? null;
  const days = period === 'custom' ? customDays : period;
  const periodLabel = `Past ${days} ${days === 1 ? 'day' : 'days'}`;
  const filter: TeamActivityFilter = tab === 'activity' ? metric ?? type : 'all';
  const activity = useTeamActivity(account, { circleId: circle?.id, memberId: tab === 'members' ? undefined : personMember?.githubId, days, limit, filter });
  const panelCircle = circle && inspected && circle.members.some(member => member.githubId === inspected.githubId) ? circle : null;
  const panel = useTeamActivity(inspected ? account : undefined, { circleId: panelCircle?.id, memberId: inspected?.githubId, days, limit: 50 });
  const attention = useLiveAttention(account);
  const only = (githubId: number) => (item: TeamCircle) => ({ ...item, members: item.members.filter(member => member.githubId === githubId) });
  const needs = circleNeeds(attention.live, personMember ? scope.map(only(personMember.githubId)) : scope, viewerLogin);
  const personNeeds = inspected ? circleNeeds(attention.live, circles.map(only(inspected.githubId)), viewerLogin) : [];

  useEffect(() => {
    const reset = () => {
      currentAccount.current = undefined;
      setPerson(null); setMetric(null); setType('all'); setInspect(null); setPopover(null); setEditing(null); setDeleting(null); setError(null);
    };
    window.addEventListener(GITHUB_ACCOUNT_CHANGED, reset);
    return () => window.removeEventListener(GITHUB_ACCOUNT_CHANGED, reset);
  }, []);

  function setTab(next: Tab) { setView(current => ({ ...current, tab: next })); setPopover(null); setLimit(100); }
  function selectCircle(id: string | null) {
    setView(current => ({ ...current, circleId: id }));
    setPerson(null); setMetric(null); setType('all'); setLimit(100); setPopover(null); setInspect(null);
  }
  function drill(next: Metric, memberId?: number) {
    setMetric(next); setType('all'); setLimit(100); setPopover(null);
    if (memberId !== undefined) setPerson(memberId);
    setView(current => ({ ...current, tab: 'activity' }));
  }
  const toggle = (next: Exclude<Popover, null>) => setPopover(current => current === next ? null : next);
  const notify: Notify = (text, undo) => { toastId.current += 1; setToast({ id: toastId.current, text, undo }); };
  const openCreate = (name: string) => { setError(null); setPopover(null); setEditing({ circle: null, name }); };

  const keys = useRef({ popover, inspect, dialog: false });
  keys.current = { popover, inspect, dialog: editing !== null || deleting !== null };
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (keys.current.dialog) return;
      if (event.key === 'Escape') {
        // Esc closes the top layer: a popover, then the person panel.
        if (keys.current.popover !== null) setPopover(null);
        else if (keys.current.inspect !== null) setInspect(null);
      }
      else if ((event.metaKey || event.ctrlKey) && !event.altKey && !event.shiftKey && /^[1-4]$/.test(event.key)) {
        event.preventDefault();
        setView(current => ({ ...current, tab: TABS[Number(event.key) - 1][0] }));
        setPopover(null); setLimit(100);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  /** Applies a directory change unless the account changed while it ran. */
  async function mutate(action: () => Promise<TeamDirectory>, failed: (text: string) => void): Promise<TeamDirectory | null> {
    if (busy) return null;
    const owner = currentAccount.current;
    setBusy(true); setError(null);
    try {
      const result = await action();
      if (owner !== currentAccount.current || result.accountLogin.toLowerCase() !== owner?.toLowerCase()) return null;
      updateTeams(result);
      return result;
    }
    catch (failure) {
      if (owner === currentAccount.current) failed(remoteErrorMessage(failure));
      return null;
    }
    finally { setBusy(false); }
  }

  function saveCircle(value: TeamCreateInput) {
    let input: TeamCreateInput;
    try { input = parseTeamCreateInput(value); }
    catch (failure) { setError(remoteErrorMessage(failure)); return; }
    const existing = editing?.circle ?? null;
    const before = circles;
    void mutate(() => existing ? window.bridge.updateTeam(existing.id, input) : window.bridge.createTeam(input), setError).then(result => {
      if (result === null) return;
      setEditing(null);
      if (existing) { notify(`Saved “${input.name}”`); return; }
      const added = result.circles.find(item => !before.some(previous => previous.id === item.id));
      if (added) {
        selectCircle(added.id);
        setView(current => ({ ...current, tab: input.members.length > 0 ? 'needs' : 'members' }));
      }
      notify(`Created “${input.name}”${input.members.length > 0 ? ` with ${people(input.members.length).toLowerCase()}` : ''}`);
    });
  }

  function deleteCircle(target: TeamCircle) {
    void mutate(() => window.bridge.removeTeam(target.id), text => notify(`Couldn’t delete “${target.name}”: ${text}`)).then(result => {
      if (result === null) return;
      setDeleting(null);
      selectCircle(null);
      notify(`Deleted “${target.name}”`);
    });
  }

  function addMember(target: TeamCircle, member: TeamMemberInput) {
    void mutate(() => window.bridge.addTeamMember(target.id, member), text => notify(`Couldn’t add ${member.login}: ${text}`)).then(result => {
      if (result !== null) notify(`Added ${member.login} to ${target.name} · they aren’t notified`);
    });
  }

  function removeMember(target: TeamCircle, member: TeamMember) {
    const others = circles.filter(item => item.id !== target.id && item.members.some(person => person.githubId === member.githubId));
    void mutate(() => window.bridge.removeTeamMember(target.id, member.githubId), text => notify(`Couldn’t remove ${member.login}: ${text}`)).then(result => {
      // Undo re-adds the stored GitHub ID, which is verified again like any addition.
      if (result !== null) notify(`Removed ${member.login} from ${target.name}${others.length > 0 ? ` · still in ${others[0].name}` : ''}`,
        () => addMember(target, { githubId: member.githubId, login: member.login }));
    });
  }

  function toggleCircle(target: TeamCircle, add: boolean, member: TeamMember) {
    void mutate(() => add ? window.bridge.addTeamMember(target.id, { githubId: member.githubId, login: member.login })
      : window.bridge.removeTeamMember(target.id, member.githubId), text => notify(text)).then(result => {
      if (result !== null) notify(`${add ? 'Added to' : 'Removed from'} ${target.name}`);
    });
  }

  function refresh() {
    setPopover(null);
    void activity.refresh().then(() => { if (inspected) void panel.reload(); });
  }

  const dialogs = <>
    {editing && <CircleDialog key={editing.circle?.id ?? 'new'} circle={editing.circle} initialName={editing.name} circles={circles} busy={busy} error={error}
      onSave={saveCircle} onCancel={() => { setEditing(null); setError(null); }} onClearError={() => setError(null)}
      onDelete={editing.circle ? () => { setDeleting(editing.circle); setEditing(null); } : undefined}
      onManagePeople={() => { setEditing(null); setTab('members'); setPopover('add'); }} />}
    {deleting && <ConfirmDialog title={`Delete “${deleting.name}”?`} confirmLabel="Delete circle" destructive busy={busy}
      onCancel={() => setDeleting(null)} onConfirm={() => deleteCircle(deleting)}>
      <p>This removes the private circle and its Home priority preference. {deleting.members.length === 0 ? 'Nothing changes on GitHub.'
        : `Its ${people(deleting.members.length).toLowerCase()} ${deleting.members.length === 1 ? 'stays' : 'stay'} in any other circles, and nothing changes on GitHub.`}</p>
    </ConfirmDialog>}
    {toast && <Toast key={toast.id} text={toast.text} onDismiss={() => setToast(null)}
      onUndo={toast.undo ? () => { toast.undo?.(); setToast(null); } : undefined} />}
  </>;

  if (directory === null) return <div className="cir-page">
    {directoryError && !loading ? <div className="cir-empty" role="alert"><h2>Circles are unavailable</h2><p>{directoryError}</p></div> : <LoadingPage />}
  </div>;

  if (circles.length === 0) return <div className="cir-page">
    <FirstUse onCreate={openCreate} />
    {dialogs}
  </div>;

  const coverage = activity.snapshot?.coverage ?? null;
  const status = activity.refreshing ? { tone: 'busy', text: 'Refreshing…' } : activity.refreshError ? { tone: 'failed', text: 'Refresh failed' }
    : { tone: coverage?.partial ? 'partial' : 'ok', text: coverage?.lastRefreshedAt ? `${coverage.partial ? 'Partial ·' : 'Updated'} ${ago(coverage.lastRefreshedAt)}` : 'Not collected yet' };
  const title = circle?.name ?? 'All circles';
  const counts: Partial<Record<Tab, number>> = { needs: needs.length, members: members.length };
  const emptyScope = personMember?.login ?? (circle ? `people in ${circle.name}` : 'people in your circles');

  return <div className="cir-page">
    <main className="cir-main">
      <div className="cir-work">
        <header className="bq-page-header">
          <div className="cir-anchor cir-switcher">
            <h1 className="bq-page-header__title">
              <button type="button" className="cir-switcher__button" aria-haspopup="menu" aria-expanded={popover === 'switcher'}
                title={circle?.description || 'Switch circle'} onClick={() => toggle('switcher')}>
                <CircleIcon avatar={circle?.avatar ?? ALL_CIRCLES} size="dot" /><span className="cir-ellipsis">{title}</span>
                <ChevronDown size={14} strokeWidth={2.2} aria-hidden="true" />
              </button>
            </h1>
            {popover === 'switcher' && <Switcher circles={circles} current={circle} needsOf={scope => circleNeeds(attention.live, scope, viewerLogin).length}
              onSelect={selectCircle} onCreate={() => openCreate('')} onClose={() => setPopover(null)} />}
          </div>
          <div className="bq-page-header__meta">
            <button type="button" className="cir-people" onClick={() => setTab('members')}>
              {members.length > 0 && <span className="cir-avatars">{members.slice(0, 5).map(member =>
                <Avatar key={member.githubId} login={member.login} avatarUrl={member.avatarUrl} size={20} />)}</span>}
              <span>{people(members.length)}</span>
            </button>
            <span className="cir-sep" aria-hidden="true">·</span>
            {circle ? <span className="cir-ellipsis" title="Repository scope filters activity. It doesn’t watch repositories or change access.">
              {circle.repositories.length > 0 ? circle.repositories.join(', ') : 'Any repository'}
            </span> : <span className="cir-ellipsis">{circles.length} {circles.length === 1 ? 'circle' : 'circles'}</span>}
          </div>
          <div className="bq-page-header__actions cir-anchor">
            {circle ? <>
              <button type="button" className="cir-iconbtn" aria-label="Add people" title="Add people" aria-haspopup="dialog" aria-expanded={popover === 'add'} onClick={() => {
                const opening = popover !== 'add';
                setTab('members');
                setPopover(opening ? 'add' : null);
              }}><UserPlus size={15} strokeWidth={1.9} aria-hidden="true" /></button>
              <button type="button" className="cir-iconbtn" aria-label="Edit circle" title="Edit circle"
                onClick={() => { setError(null); setPopover(null); setEditing({ circle, name: circle.name }); }}>
                <Pencil size={14} strokeWidth={1.9} aria-hidden="true" />
              </button>
              {popover === 'add' && <TeamPeoplePicker key={circle.id} circle={circle} circles={circles} busy={busy}
                onAdd={member => addMember(circle, member)} onClose={() => setPopover(null)} />}
            </> : <button type="button" className="cir-iconbtn" aria-label="New circle" title="New circle" onClick={() => openCreate('')}>
              <Plus size={16} strokeWidth={1.9} aria-hidden="true" />
            </button>}
          </div>
        </header>

        <div className="bq-page-tabs">
          <nav className="bq-page-tabs__list" role="tablist" aria-label="Circle views"
            onKeyDown={event => arrowFocus(event, ['ArrowLeft', 'ArrowRight'], button => setTab(TABS[Number(button.dataset.index)][0]))}>
            {TABS.map(([id, label], index) => <button key={id} type="button" role="tab" data-index={index} aria-selected={tab === id} tabIndex={tab === id ? 0 : -1}
              title={`⌘${index + 1}`} className="bq-page-tabs__tab" onClick={() => setTab(id)}>
              {label}{counts[id] !== undefined && <span className="bq-page-tabs__count">{counts[id]}</span>}
            </button>)}
          </nav>
          <div className="bq-page-tabs__tools">
            {tab !== 'members' && <>
              <div className="cir-anchor cir-person-select">
                <button type="button" className="cir-select" aria-haspopup="listbox" aria-expanded={popover === 'person'} aria-label="Person" title="Filter by person"
                  onClick={() => toggle('person')}>
                  {personMember ? <Avatar login={personMember.login} avatarUrl={personMember.avatarUrl} size={16} /> : <Users size={14} aria-hidden="true" />}
                  <span className="cir-ellipsis">{personMember?.login ?? 'Everyone'}</span><ChevronDown size={12} strokeWidth={2.2} aria-hidden="true" />
                </button>
                {popover === 'person' && <PersonFilter members={members} selected={personMember} onClose={() => setPopover(null)}
                  onSelect={id => { setPerson(id); setPopover(null); setLimit(100); }} />}
              </div>
              <span className="cir-divider" aria-hidden="true" />
            </>}
            <div className="cir-anchor cir-status-group">
              <button type="button" className={`cir-status is-${status.tone}`} aria-haspopup="dialog" aria-expanded={popover === 'coverage'}
                title={tab === 'needs' ? 'Current backlog · not limited by period' : undefined} onClick={() => toggle('coverage')}>
                <span className="cir-status__dot" aria-hidden="true" /><span className="cir-ellipsis">{status.text}</span>
              </button>
              <button type="button" className="cir-iconbtn" aria-label="Refresh activity" title="Refresh (runs one bounded collection pass)"
                disabled={activity.refreshing} onClick={refresh}>
                {activity.refreshing ? <span className="cir-spinner" aria-hidden="true" /> : <RefreshCw size={14} strokeWidth={2} aria-hidden="true" />}
              </button>
              {popover === 'coverage' && <CoveragePopover coverage={coverage} refreshError={activity.refreshError} onRefresh={refresh} onClose={() => setPopover(null)} />}
            </div>
          </div>
        </div>

        {tab !== 'needs' && <div className="bq-page-filters">
          <div className="atn-filters" role="group" aria-label="Period">
            {PERIODS.map(value => <button key={value} type="button" aria-pressed={period === value} title={`Past ${value} days`}
              className={`atn-filter${period === value ? ' is-active' : ''}`} onClick={() => { setPeriod(value); setLimit(100); }}>{value}d</button>)}
            <button type="button" aria-pressed={period === 'custom'} title="Custom period, 1–90 days" className={`atn-filter${period === 'custom' ? ' is-active' : ''}`}
              onClick={() => setPeriod('custom')}>Custom</button>
          </div>
          {period === 'custom' && <label className="cir-days">Past<input type="number" min={1} max={90} value={customDays} aria-label="Past days, 1 to 90"
            onChange={event => { const value = Math.round(Number(event.target.value)); if (value >= 1 && value <= 90) { setCustomDays(value); setLimit(100); } }} />days</label>}
          {tab === 'activity' && (metric === null
            ? <div className="atn-filters" role="group" aria-label="Activity type">
              {ACTIVITY_TYPES.map(([id, label]) => <button key={id} type="button" aria-pressed={type === id} className={`atn-filter${type === id ? ' is-active' : ''}`}
                onClick={() => { setType(id); setLimit(100); }}>{label}</button>)}
            </div>
            : <span className="cir-metric-chip">From Insights: <span className="cir-strong">{METRICS[metric]}</span>
              <button type="button" aria-label="Clear metric filter" onClick={() => { setMetric(null); setLimit(100); }}><X size={12} strokeWidth={2.2} aria-hidden="true" /></button>
            </span>)}
        </div>}

        <div className="cir-body">
          {activity.refreshError && <div className="cir-banner" role="alert">
            <TriangleAlert size={15} aria-hidden="true" />
            <span className="cir-banner__text"><span className="cir-strong">Couldn’t refresh.</span> {activity.refreshError.replace(/\.?$/, '.')} You’re seeing activity recorded {
              coverage?.lastRefreshedAt ? ago(coverage.lastRefreshedAt) : 'earlier'}.</span>
            <button type="button" className="bq-button" onClick={refresh}>Try again</button>
            <button type="button" className="bq-button bq-button--ghost" onClick={() => setPopover('coverage')}>Details</button>
            <button type="button" className="cir-iconbtn" aria-label="Dismiss" title="Dismiss" onClick={activity.dismissRefreshError}><X size={14} aria-hidden="true" /></button>
          </div>}
          {activity.error && <div className="cir-banner" role="alert"><TriangleAlert size={15} aria-hidden="true" /><span className="cir-banner__text">{activity.error}</span></div>}
          {attention.error && tab === 'needs' && <div className="cir-banner is-quiet" role="status">{attention.error}</div>}
          {tab === 'needs' ? <NeedsYou needs={needs} circles={circles} allMemberLogins={logins} viewerLogin={viewerLogin} showCircle={circle === null}
            handled={attention.handled} setHandled={attention.setHandled} notify={notify} onPerson={setInspect} onActivity={() => setTab('activity')} emptyScope={emptyScope} />
            : activity.snapshot === null ? <LoadingRows />
              : tab === 'activity' ? <CircleActivity snapshot={activity.snapshot} people={byId} viewerLogin={viewerLogin} days={days}
                personLogin={personMember?.login ?? null} metricLabel={metric ? METRICS[metric] : null} filtered={personMember !== null || metric !== null || type !== 'all'}
                canLoadMore={limit < ACTIVITY_LIMIT_MAX} onLoadMore={() => setLimit(value => Math.min(ACTIVITY_LIMIT_MAX, value + 100))}
                onWiden={() => { setPeriod(90); setLimit(100); }} onClear={() => { setPerson(null); setMetric(null); setType('all'); setLimit(100); }} />
                : tab === 'members' ? <CircleMembers circle={circle} circles={circles} members={members} statistics={activity.snapshot.people} periodLabel={periodLabel}
                  inspected={inspect} busy={busy} onInspect={setInspect} onRemove={member => { if (circle) removeMember(circle, member); }} onAddPeople={() => setPopover('add')} />
                  : <CircleInsights snapshot={activity.snapshot} caption={`${personMember?.login ?? title} · ${periodLabel}`} members={byId}
                    personId={personMember?.githubId ?? null} onDrill={drill} onPerson={setInspect} />}
        </div>
      </div>
      {inspected && <PersonPanel member={inspected} circles={circles} backLabel={title} needs={personNeeds} snapshot={panel.snapshot} periodLabel={periodLabel}
        viewerLogin={viewerLogin} circlesOpen={popover === 'circles'} busy={busy}
        onClose={() => { setInspect(null); setPopover(current => current === 'circles' ? null : current); }}
        onCircles={open => setPopover(open ? 'circles' : null)} onSelectCircle={selectCircle}
        onToggleCircle={(target, add) => toggleCircle(target, add, inspected)} onDrill={next => drill(next, inspected.githubId)}
        onAllActivity={() => { setPerson(inspected.githubId); setMetric(null); setType('all'); setLimit(100); setTab('activity'); }} />}
    </main>
    {dialogs}
  </div>;
}

function Switcher({ circles, current, needsOf, onSelect, onCreate, onClose }: {
  circles: TeamCircle[];
  current: TeamCircle | null;
  needsOf: (scope: TeamCircle[]) => number;
  onSelect: (id: string | null) => void;
  onCreate: () => void;
  onClose: () => void;
}) {
  const host = usePopoverDismiss(onClose, true);
  const row = (circle: TeamCircle | null) => {
    const name = circle?.name ?? 'All circles';
    const selected = (current?.id ?? null) === (circle?.id ?? null);
    const needs = needsOf(circle ? [circle] : circles);
    return <button type="button" role="menuitemradio" aria-checked={selected} key={circle?.id ?? 'all'} title={name} className="cir-menu__item"
      onClick={() => onSelect(circle?.id ?? null)}>
      <CircleIcon avatar={circle?.avatar ?? ALL_CIRCLES} size="dot" />
      <span className="cir-stack"><span className="cir-menu__name">{name}</span>
        <span className="cir-menu__meta">{circle ? `${people(circle.members.length)} · ${scopeShort(circle)}` : 'Everyone across your circles'}</span></span>
      {needs > 0 && <span className="cir-muted" title={`${needs} ${needs === 1 ? 'needs' : 'need'} you`}>{needs}</span>}
      <span className="cir-menu__check">{selected && <Check size={14} strokeWidth={2.2} aria-hidden="true" />}</span>
    </button>;
  };
  return <div ref={host} className="cir-popover cir-menu cir-switcher__menu" role="menu" aria-label="Circles" onKeyDown={event => arrowFocus(event, ['ArrowUp', 'ArrowDown'])}>
    <div className="cir-label">Circles</div>
    {row(null)}
    {circles.map(row)}
    <span className="cir-menu__rule" aria-hidden="true" />
    <button type="button" role="menuitem" className="cir-menu__item" onClick={onCreate}><Plus size={14} aria-hidden="true" />New circle</button>
    <div className="cir-menu__note"><LockKeyhole size={12} strokeWidth={2} aria-hidden="true" />Private to you · no one is notified</div>
  </div>;
}

function PersonFilter({ members, selected, onSelect, onClose }: {
  members: TeamMember[];
  selected: TeamMember | null;
  onSelect: (id: number | null) => void;
  onClose: () => void;
}) {
  const host = usePopoverDismiss(onClose, true);
  return <div ref={host} className="cir-popover cir-menu cir-person-filter" role="listbox" aria-label="Filter by person">
    <button type="button" role="option" aria-selected={selected === null} className="cir-menu__item" onClick={() => onSelect(null)}>
      <span className="cir-menu__check">{selected === null && <Check size={14} strokeWidth={2.2} aria-hidden="true" />}</span>
      <Users size={16} aria-hidden="true" /><span>Everyone</span><span className="cir-muted">{people(members.length)}</span>
    </button>
    {members.map(member => <button type="button" role="option" key={member.githubId} aria-selected={selected?.githubId === member.githubId} className="cir-menu__item"
      onClick={() => onSelect(member.githubId)}>
      <span className="cir-menu__check">{selected?.githubId === member.githubId && <Check size={14} strokeWidth={2.2} aria-hidden="true" />}</span>
      <Avatar login={member.login} avatarUrl={member.avatarUrl} size={20} /><span className="cir-ellipsis">{member.login}</span>
      <span className="cir-muted cir-ellipsis">{member.name !== member.login ? member.name : ''}</span>
    </button>)}
  </div>;
}

function CoveragePopover({ coverage, refreshError, onRefresh, onClose }: {
  coverage: TeamCoverage | null;
  refreshError: string | null;
  onRefresh: () => void;
  onClose: () => void;
}) {
  const host = usePopoverDismiss(onClose, true);
  const errors = [...(coverage?.errors ?? []), ...(refreshError ? [refreshError] : [])];
  return <div ref={host} className="cir-popover cir-coverage" role="dialog" aria-label="Observed activity">
    <div className="cir-strong">Observed activity</div>
    {coverage && <p>{coverage.lastRefreshedAt ? `Last refreshed ${ago(coverage.lastRefreshedAt)}.` : 'Not collected yet.'} {coverage.membersScanned} of {coverage.memberCount} people
      and {coverage.repositoriesScanned} of {coverage.repositoryCount} repositories scanned in the latest pass.</p>}
    <p>Up to 90 days are kept{coverage?.observedSince ? `, since ${new Date(coverage.observedSince).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}` : ''}.
      Private repositories are collected only when watched and accessible to this account.</p>
    {errors.length > 0 && <div className="cir-coverage__errors">{errors.map((text, index) => <span key={index}>{text}</span>)}</div>}
    <div className="cir-coverage__actions">
      <button type="button" className="bq-button bq-button--primary" onClick={onRefresh}>Refresh now</button>
      <button type="button" className="bq-button" onClick={onClose}>Close</button>
    </div>
  </div>;
}

function FirstUse({ onCreate }: { onCreate: (name: string) => void }) {
  return <div className="cir-first">
    <div className="cir-first__inner">
      <span className="cir-first__icon" aria-hidden="true"><Users size={20} strokeWidth={1.7} /></span>
      <h1>Keep the people you review close</h1>
      <p>A circle is a private list of GitHub people — colleagues or trusted open-source contributors. See what needs your review from them,
        what they’ve been working on, and how you’ve collaborated.</p>
      <ul className="cir-facts">
        {['Only you can see it', 'No invitations or notifications', 'GitHub access stays the same'].map(fact =>
          <li key={fact}><Check size={13} strokeWidth={2.4} aria-hidden="true" />{fact}</li>)}
      </ul>
      <button type="button" className="bq-button bq-button--primary bq-button--large" onClick={() => onCreate('')}><Plus aria-hidden="true" />Create a circle</button>
      <div className="cir-muted cir-first__or">Or start from a name</div>
      <div className="cir-templates">{TEMPLATES.map(name => <button type="button" key={name} className="cir-template" onClick={() => onCreate(name)}>{name}</button>)}</div>
    </div>
  </div>;
}

function LoadingRows() {
  return <div className="cir-skeleton" role="status" aria-label="Loading recorded activity">
    {[0, 1, 2, 3, 4].map(index => <div key={index} className="cir-skeleton__row"><span className="cir-skeleton__dot" />
      <span className="cir-stack"><span className="cir-skeleton__line" /><span className="cir-skeleton__line is-short" /></span></div>)}
  </div>;
}

function LoadingPage() {
  return <main className="cir-main"><div className="cir-work cir-loading">
    <span className="cir-skeleton__line is-title" /><span className="cir-skeleton__line is-text" />
    <div className="cir-loading__note"><span className="cir-spinner" aria-hidden="true" />Loading your circles and recorded activity…</div>
    <LoadingRows />
  </div></main>;
}
