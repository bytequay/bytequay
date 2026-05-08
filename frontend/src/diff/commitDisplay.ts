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

/** Defensive: a non-string slipped in once via an onClick handler that
 *  forwarded its MouseEvent as a sha. Coerce to string and accept
 *  anything; render an empty span when we can't make sense of it. */
export function formatShortSha(sha: unknown): string {
  if (typeof sha !== 'string') return '';
  return sha.slice(0, 7);
}

/** First line of a commit message, capped so a long subject doesn't
 *  blow up the popover row width. */
export function commitSubject(message: string | null | undefined): string {
  if (!message) return '';
  const first = message.split('\n')[0];
  return first.length > 120 ? first.slice(0, 117) + '…' : first;
}
