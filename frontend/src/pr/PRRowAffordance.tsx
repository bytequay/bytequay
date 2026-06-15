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
import type { PrLinksDto, PullRequestDto } from '../types';
import { ReviewChip } from '../threads/ReviewChip';
import { TaskChip } from '../threads/TaskChip';

type Props = {
  pr: PullRequestDto;
  /** The PR's links (fetched by the parent), or null while loading. */
  links: PrLinksDto | null;
  onCreateTask: () => void;
  onAssignReview: () => void;
  onOpenTask: (taskId: string) => void;
  onOpenReview: (passId: string) => void;
};

/**
 * The single, authorship-gated affordance for a PR row. Authorship comes
 * from the PR's {@code origin} — {@code AUTHORED} is mine, everything
 * else is a review request. Own PR → Create-dev-task (or its TaskChip
 * when linked); others' PR → Assign-review (or its ReviewChip). Never
 * both; never a menu.
 */
export function PRRowAffordance(
  { pr, links, onCreateTask, onAssignReview, onOpenTask, onOpenReview }: Props,
) {
  const isMine = pr.origin === 'AUTHORED';

  if (isMine) {
    const task = links?.linkedActiveTask;
    return task
      ? <TaskChip title={task.title} group={task.phaseGroup} onOpen={() => onOpenTask(task.id)} />
      : <CreateDevTaskButton onClick={onCreateTask} />;
  }
  const review = links?.linkedActiveReviewRef;
  return review
    ? (
      <ReviewChip
        phase={review.phase}
        round={review.round}
        roundCap={review.roundCap}
        onOpen={() => onOpenReview(review.passId)}
      />
    )
    : <AssignReviewButton onClick={onAssignReview} />;
}

/** Green primary — own PR. */
export function CreateDevTaskButton({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" style={greenStyle} onClick={onClick}>+ Create dev task</button>
  );
}

/** Amber primary — others' PR. */
export function AssignReviewButton({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" style={amberStyle} onClick={onClick}>+ Assign review</button>
  );
}

const baseBtn: React.CSSProperties = {
  padding: '5px 12px',
  border: 'none',
  borderRadius: 999,
  color: '#fff',
  fontSize: 12,
  fontWeight: 700,
  cursor: 'pointer',
  font: 'inherit',
};

const greenStyle: React.CSSProperties = {
  ...baseBtn,
  background: 'linear-gradient(135deg,#34d399,#10b981)',
};

const amberStyle: React.CSSProperties = {
  ...baseBtn,
  background: 'linear-gradient(135deg,#fbbf24,#d97706)',
};
