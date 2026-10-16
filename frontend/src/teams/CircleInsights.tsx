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
import { shortDate } from '../displayText';
import Avatar from '../Avatar';
import { METRICS, type Metric } from './activity';
import type { TeamActivitySnapshot, TeamMember, TeamStatistics } from './types';

const TILES: [Metric, keyof TeamStatistics, string][] = [
  ['prs-opened', 'prsOpened', 'Pull requests, not commits'],
  ['authored-merges', 'authoredPrsMerged', 'Their PRs, merged by anyone'],
  ['reviews-performed', 'prsReviewed', 'Counted once per person and PR'],
  ['interactions', 'directInteractions', 'Mentions, review requests, reviews of each other’s PRs'],
];
const SECONDARY: [Metric, keyof TeamStatistics][] = [
  ['merges-performed', 'mergesPerformed'], ['issues-opened', 'issuesOpened'], ['issues-closed', 'issuesClosed'], ['comments', 'comments'],
];
const COLUMNS: [Metric, keyof TeamStatistics, string][] = [
  ['prs-opened', 'prsOpened', 'PRs opened'], ['authored-merges', 'authoredPrsMerged', 'Authored merged'],
  ['merges-performed', 'mergesPerformed', 'Merges performed'], ['reviews-performed', 'prsReviewed', 'PRs reviewed'],
  ['issues-opened', 'issuesOpened', 'Issues opened'], ['issues-closed', 'issuesClosed', 'Issues closed'],
  ['comments', 'comments', 'Comments'], ['interactions', 'directInteractions', 'With you'],
];

const shared = (statistics: TeamStatistics) => `${statistics.sharedPullRequests} PRs · ${statistics.sharedIssues} issues`;

/** Headline counts, secondary counts, updates per day and a per-person table; every number opens its updates. */
export default function CircleInsights({ snapshot, caption, members, personId, onDrill, onPerson }: {
  snapshot: TeamActivitySnapshot;
  caption: string;
  members: ReadonlyMap<number, TeamMember>;
  personId: number | null;
  onDrill: (metric: Metric, memberId?: number) => void;
  onPerson: (memberId: number) => void;
}) {
  const statistics = personId === null ? snapshot.totals : snapshot.people.find(person => person.memberId === personId)?.statistics ?? snapshot.totals;
  const most = Math.max(1, ...snapshot.days.map(day => day.updates));
  const gaps = snapshot.days.some(day => !day.collected);
  const people = [...snapshot.people].sort((left, right) => left.login.localeCompare(right.login));
  return <div className="cir-insights">
    <div className="cir-muted">{caption} · counts are recorded observations, select any number to see its updates</div>
    <div className="cir-tiles">
      {TILES.map(([metric, field, detail]) => {
        const value = statistics[field];
        return <button type="button" key={metric} className="cir-tile" onClick={() => onDrill(metric)}
          title={value === 0 ? 'No recorded observation in this period' : `Open the ${value} matching updates in Activity`}>
          <span className="cir-tile__value">{value}</span><span className="cir-tile__label">{METRICS[metric]}</span><span className="cir-tile__detail">{detail}</span>
        </button>;
      })}
    </div>
    <div className="cir-secondary">
      {SECONDARY.map(([metric, field]) => <button type="button" key={metric} className="cir-count" onClick={() => onDrill(metric)}>
        <span>{METRICS[metric]}</span><span className="cir-count__value">{statistics[field]}</span>
      </button>)}
      <span className="cir-divider" aria-hidden="true" />
      <button type="button" className="cir-count" title="Work you both participated in — not necessarily a conversation" onClick={() => onDrill('shared')}>
        <span>Shared work</span><span className="cir-count__value">{shared(statistics)}</span>
      </button>
    </div>
    <section aria-label="Recorded updates per day">
      <div className="cir-heading"><h2>Recorded updates per day</h2><span className="cir-muted">Shows quiet days and collection gaps apart</span></div>
      <div className="cir-bars">
        {snapshot.days.map(day => <span key={day.date} className={`cir-bar${day.collected ? day.updates > 0 ? ' is-some' : '' : ' is-gap'}`}
          style={{ height: !day.collected ? 56 : day.updates > 0 ? Math.max(3, Math.round(day.updates / most * 56)) : 2 }}
          title={`${shortDate(day.date)} · ${day.collected ? `${day.updates} recorded ${day.updates === 1 ? 'update' : 'updates'}` : 'not collected'}`} />)}
      </div>
      <div className="cir-bars__axis cir-muted">
        <span>{snapshot.days.length > 0 ? shortDate(snapshot.days[0].date) : ''}</span>
        {gaps && <span className="cir-bars__legend"><span className="cir-bar is-gap" aria-hidden="true" />Not collected — zero here means unknown</span>}
        <span>Today</span>
      </div>
    </section>
    <section aria-label="By person">
      <div className="cir-heading"><h2>By person</h2><span className="cir-muted">· alphabetical, not a ranking</span></div>
      <div className="cir-table cir-table--scroll">
        <div className="cir-person-row cir-person-row--head">
          <span>Person</span>{COLUMNS.map(([metric, , label]) => <span key={metric}>{label}</span>)}<span title="Shared PRs · shared issues">Shared PRs · issues</span>
        </div>
        {people.map(person => {
          const member = members.get(person.memberId);
          const cell = (metric: Metric, value: number | string, label: string, empty: boolean) =>
            <button type="button" key={metric} className={`cir-cell${empty ? ' is-empty' : ''}`} onClick={() => onDrill(metric, person.memberId)}
              title={empty ? 'No recorded observation' : `Show ${person.login}’s ${label.toLowerCase()}`}>{empty && typeof value === 'number' ? '–' : value}</button>;
          return <div className="cir-person-row" key={person.memberId}>
            <button type="button" className="cir-person-link" onClick={() => onPerson(person.memberId)}>
              <Avatar login={person.login} avatarUrl={member?.avatarUrl} size={20} /><span>{person.login}</span>
            </button>
            {COLUMNS.map(([metric, field, label]) => cell(metric, person.statistics[field], label, person.statistics[field] === 0))}
            {cell('shared', shared(person.statistics), 'Shared work', person.statistics.sharedPullRequests + person.statistics.sharedIssues === 0)}
          </div>;
        })}
      </div>
      <p className="cir-footnote">Direct interactions are explicit mentions, review requests between you, and reviews of each other’s PRs. Shared work counts items you both participated in — commenting on the same issue isn’t treated as a conversation. PR counts are not commit counts.</p>
    </section>
  </div>;
}
