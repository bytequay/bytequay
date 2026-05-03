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
import Avatar from './Avatar';
import {
  followingNarrativeSegments,
  type NarrativeSegment,
} from './activityNarrative';
import type { RecentEventDto } from './types';

/** Renders a list of narrative segments. Linked segments become
 *  borderless link-buttons that fire {@link onLinkClick} (so the host
 *  component decides between an in-app jump vs. an external open). */
export function NarrativeText({
  segments,
  onLinkClick,
}: {
  segments: NarrativeSegment[];
  onLinkClick: (url: string) => void;
}) {
  return (
    <>
      {segments.map((s, i) => (
        s.url
          ? (
            <button
              key={i}
              type="button"
              className="home-following-item__link"
              onClick={() => onLinkClick(s.url!)}
              title={s.url}
            >
              {s.text}
            </button>
          )
          : <span key={i}>{s.text}</span>
      ))}
    </>
  );
}

type Props = {
  event: RecentEventDto;
  /** Optional actor avatar + login. When omitted (own-activity card),
   *  the row uses the prebuilt actor block from the parent. */
  actor: { login: string; profileUrl: string } | null;
  /** Whether to show the actor login as a clickable name before the
   *  narrative. The "your recent activity" card hides this since every
   *  row is implicitly the user. */
  showActorName: boolean;
  formatTime: (createdAt: string) => string;
  onOpenUrl: (url: string) => void;
};

/**
 * One row in either home-page activity card. Single component so a
 * forgotten reference (e.g. the {@code followingNarrative is not defined}
 * regression that hit production after a rename) breaks both cards
 * uniformly — and is caught by the ActivityRow render test.
 */
function ActivityRow({ event, actor, showActorName, formatTime, onOpenUrl }: Props) {
  const segments = followingNarrativeSegments(event);
  return (
    <div className="home-following-item">
      {actor && (
        <button
          className="home-following-item__actor"
          onClick={() => onOpenUrl(actor.profileUrl)}
          type="button"
        >
          <Avatar login={actor.login} size={28} className="home-following-item__avatar" />
        </button>
      )}
      <div className="home-following-item__body">
        <div className="home-following-item__text">
          {showActorName && actor && (
            <>
              <button
                className="home-following-item__name"
                onClick={() => onOpenUrl(actor.profileUrl)}
                type="button"
              >
                {actor.login}
              </button>
              {' '}
            </>
          )}
          <span className="home-following-item__verb">
            <NarrativeText segments={segments} onLinkClick={onOpenUrl} />
          </span>
        </div>
        <div className="home-following-item__time">{formatTime(event.createdAt)}</div>
      </div>
    </div>
  );
}

export default ActivityRow;
