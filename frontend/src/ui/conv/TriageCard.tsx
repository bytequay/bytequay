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
 * A compact triage card for a trunk-proposed backlog candidate. Shows the
 * title + a clamped body + tag chips, and three actions — Start dev (the
 * bright primary CTA, kicks off trunk exploration), Keep (park it as-is), and
 * Skip (mark not-to-proceed). Acting on a card removes it from the triage
 * tray; the item stays in the backlog with its new status.
 */
export function TriageCard({ title, body, tags, onStartDev, onKeep, onSkip }: {
  title: string;
  body?: string;
  tags?: string[];
  onStartDev?: () => void;
  onKeep?: () => void;
  onSkip?: () => void;
}) {
  return (
    <div className="triage-card">
      <div className="triage-card__title">{title}</div>
      {body !== undefined && body.length > 0 && <div className="triage-card__body">{body}</div>}
      <div className="triage-card__meta">
        {tags?.map(t => <span key={t} className="triage-card__tag">{t}</span>)}
        <span className="triage-card__grow" />
        <button type="button" className="triage-card__btn triage-card__btn--start" onClick={onStartDev}>
          Start dev →
        </button>
        <button type="button" className="triage-card__btn" onClick={onKeep}>Keep</button>
        <button type="button" className="triage-card__btn" onClick={onSkip}>Skip</button>
      </div>
    </div>
  );
}
