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
import { useState } from 'react';
import type { FindingRow } from './agentReviewTypes';

export function NeedsJudgementCard({ finding, onAnswer }: { finding: FindingRow; onAnswer: (text: string) => void }) {
  const [answering, setAnswering] = useState(false);
  const [text, setText] = useState('');
  return (
    <div className="agent-judgement-card">
      <div className="agent-judgement-card__body">
        <b>Needs your judgement</b>
        <p>{finding.claim}</p>
        <small>{finding.requested_action}</small>
        {answering ? (
          <div className="agent-judgement-answer">
            <textarea value={text} onChange={event => setText(event.target.value)} placeholder="State the intended behavior…" />
            <button type="button" onClick={() => setAnswering(false)}>Cancel</button>
            <button type="button" className="primary" disabled={text.trim().length === 0} onClick={() => { onAnswer(text.trim()); setAnswering(false); }}>Record answer</button>
          </div>
        ) : <button type="button" onClick={() => setAnswering(true)}>Answer</button>}
      </div>
    </div>
  );
}
