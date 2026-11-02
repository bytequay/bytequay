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

export function WindowsUpdate({ remainingSeconds, durationSeconds }: { remainingSeconds: number; durationSeconds: number }) {
  const progress = Math.min(100, Math.max(0, Math.floor((durationSeconds - remainingSeconds) * 100 / durationSeconds)));
  return (
    <div className="windows-update">
      <div className="windows-update__spinner" aria-hidden="true">
        {[0, 1, 2, 3, 4].map(dot => <span key={dot} style={{ animationDelay: `${dot * 160}ms` }} />)}
      </div>
      <p>Working on updates</p>
      <p className="windows-update__progress">{progress}% complete</p>
      <p>Don’t turn off your computer.</p>
      <p className="windows-update__footer">Your PC will restart several times</p>
    </div>
  );
}
