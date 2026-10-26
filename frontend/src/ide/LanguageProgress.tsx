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

export default function LanguageProgress({ value }: { value: number | undefined }) {
  return <span className="ide-language-progress" role="progressbar" aria-label="Language service progress"
    aria-valuemin={0} aria-valuemax={100} aria-valuenow={value}>
    <span className="ide-language-progress__fill" style={value === undefined ? undefined : { transform: `scaleX(${value / 100})` }} />
  </span>;
}
