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

/**
 * A live "agent is working" row — a pulsing dot + label shown at the foot
 * of the conversation while the agent is generating, so the surface
 * never looks idle between a prompt and the response.
 */
export function Working({ label = 'Working…' }: { label?: string }) {
  return (
    <div className="working" role="status" aria-live="polite">
      <span className="working__dot" aria-hidden />
      <span className="working__label">{label}</span>
    </div>
  );
}
