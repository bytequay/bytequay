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
import { formatAbsoluteTime, formatRelativeTime } from './utils';

/** A live, hover-exact relative timestamp — our take on github.com's
 *  `<relative-time>` web component. Shows the same compact relative
 *  label as before ("7h ago"), but adds the two behaviours the plain
 *  string was missing:
 *
 *  <ul>
 *    <li>The absolute local time sits in a `title` tooltip, so the
 *        exact "when" is one hover away (cursor turns to `help` to
 *        hint it's there).</li>
 *    <li>The label re-renders on a shared 30s tick, so "5m ago"
 *        becomes "6m ago" without a refresh.</li>
 *  </ul>
 *
 *  <p>One module-level interval drives every mounted instance, so a
 *  timeline with dozens of timestamps still costs a single timer. */
export function RelativeTime({
  timestamp, className,
}: {
  timestamp: string;
  className?: string;
}) {
  useNowTick();
  const absolute = formatAbsoluteTime(timestamp);
  return (
    <span
      className={className}
      title={absolute || undefined}
      style={absolute ? RELATIVE_TIME_STYLE : undefined}
    >
      {formatRelativeTime(timestamp)}
    </span>
  );
}

const RELATIVE_TIME_STYLE = { cursor: 'help' as const };

// A single shared ticker. Each <RelativeTime> subscribes a forced
// re-render; the interval only runs while at least one is mounted and
// stops once the last unmounts, so idle screens cost nothing.
const TICK_MS = 30_000;
const subscribers = new Set<() => void>();
let timer: ReturnType<typeof setInterval> | null = null;

function useNowTick(): void {
  const [, force] = useState(0);
  useEffect(() => {
    const onTick = () => force(n => n + 1);
    subscribers.add(onTick);
    if (timer === null) {
      timer = setInterval(() => {
        for (const sub of subscribers) sub();
      }, TICK_MS);
    }
    return () => {
      subscribers.delete(onTick);
      if (subscribers.size === 0 && timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    };
  }, []);
}
