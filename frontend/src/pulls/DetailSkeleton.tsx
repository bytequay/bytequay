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
import '../css/pulls.css';

const EVENTS = [0, 1, 2];

/**
 * The activity cards alone, for a pane whose own body already renders. Belongs directly
 * inside a `.pr-timeline`, so it sits on the same rail the real cards will.
 */
export function SkeletonTimeline({ label }: { label: string }) {
  return (
    <div className="pr-skeleton" role="status">
      <span className="pr-skeleton__label">{label}</span>
      {EVENTS.map(index => (
        <div className="pr-card" key={index}>
          <div className="pr-card__header">
            <span className="pr-skeleton__glyph" />
            <span className="pr-skeleton__bar is-name" />
            <span className="pr-skeleton__avatar" />
            <span className="pr-skeleton__bar is-time" />
          </div>
          <div className="pr-skeleton__lines">
            <span className="pr-skeleton__bar is-full" />
            <span className="pr-skeleton__bar is-long" />
          </div>
          <div className="pr-skeleton__footer">
            <span className="pr-skeleton__bar is-tag" />
            <span className="pr-skeleton__button" />
          </div>
        </div>
      ))}
    </div>
  );
}

/** The shape the loaded pull request will take: its description card, then its activity. */
export default function DetailSkeleton({ label }: { label: string }) {
  return (
    <div className="pr-overview pull-request-overview">
      <section className="pr-timeline">
        <div className="pr-card">
          <div className="pr-card__header">
            <span className="pr-skeleton__avatar" />
            <span className="pr-skeleton__bar is-name" />
            <span className="pr-skeleton__bar is-time" />
          </div>
          <div className="pr-skeleton__lines">
            <span className="pr-skeleton__bar is-short" />
            <span className="pr-skeleton__bar is-full" />
            <span className="pr-skeleton__bar is-long" />
          </div>
        </div>
        <SkeletonTimeline label={label} />
      </section>
    </div>
  );
}
