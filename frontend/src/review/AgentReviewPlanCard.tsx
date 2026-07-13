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
import type { AgentReviewData } from './agentReviewTypes';
import { formatCents, roundPlanObjectives } from './agentReviewTypes';

function kindLabel(kind: string): string {
  if (kind === 'hard-invariant') return 'INVARIANT';
  if (kind === 'engineering-principle') return 'PRINCIPLE';
  return 'CONVENTION';
}

export function AgentReviewPlanCard({ data, roundId }: { data: AgentReviewData; roundId?: string }) {
  const round = data.rounds.find(row => row.id === roundId) ?? data.rounds[0];
  const objectives = roundPlanObjectives(data, round.id);
  return (
    <details className="agent-plan-card" open>
      <summary>
        <span>Review plan</span>
        <span>{objectives.length} objectives · cap {formatCents(round.budget_json.cost_cap_cents)}</span>
      </summary>
      <div className="agent-plan-card__body">
        {objectives.map(objective => {
          const criterion = data.criteria.find(row => row.id === objective.criterion_id);
          return (
            <div className="agent-plan-objective" key={objective.id}>
              <span className={`agent-finding-chip kind kind--${criterion?.kind ?? 'hard-invariant'}`}>
                {kindLabel(criterion?.kind ?? 'hard-invariant')}
              </span>
              <span>{objective.statement}</span>
              <span className="agent-plan-source">{objective.source === 'planner-suggested' ? '✨ planner' : 'rule table'}</span>
            </div>
          );
        })}
        <div className="agent-plan-reviewers">
          {data.assignments.filter(row => row.round_id === round.id)
            .map(row => `${row.reviewer_def_id} (${row.runner})`).join(' · ')}
          {' · '}fixed budget · wall clock {round.budget_json.wall_clock_minutes}m
        </div>
      </div>
    </details>
  );
}
