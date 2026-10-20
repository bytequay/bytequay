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
import { Check, GitPullRequest, MessageSquare, Play, Pause, Sun } from 'lucide-react';
import development from '../../../website/dist/assets/app-development.jpg';
import assistant from '../../../website/dist/assets/app-assistant.jpg';
import review from '../../../website/dist/assets/app-ask-agent.jpg';
import developmentLight from './assets/development-light.jpg';
import assistantLight from './assets/assistant-light.jpg';
import reviewLight from './assets/review-light.jpg';

export type PreviewFeature = 'development' | 'automation' | 'today' | 'assistant';
const CAPTIONS: Record<PreviewFeature, string> = {
  development: 'Ask for a change, follow the agent, and inspect the result beside your conversation.',
  automation: 'Choose how far changes go on their own, from a local commit to a merged pull request.',
  today: 'A few things worth doing first, picked from your work with a reason for each.',
  assistant: 'Ask about your day and see tasks that need a decision in one place.',
};

export default function FeaturePreview({ feature }: { feature: PreviewFeature }) {
  const [playing, setPlaying] = useState(() => !window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  return <figure className={`setup-preview${playing ? ' is-playing' : ''}`}>
    <div className="setup-preview-bar"><span><i /><i /><i /></span><span>ByteQuay · example workspace</span></div>
    {feature === 'today' ? <div className="setup-today" role="img" aria-label="Today preview: review a pull request, answer a teammate, and inspect passing checks">
      <h3><Sun size={17} /> Today <span>3 things to look at</span></h3>
      <p>One review is ready, and a teammate is waiting for your answer.</p>
      <div><GitPullRequest size={17} /><span><strong>Review the session refresh fix</strong><small>Checks are green. Your review is the last one needed.</small></span></div>
      <div><MessageSquare size={17} /><span><strong>Answer the retry policy comment</strong><small>A teammate asked whether this should be a separate change.</small></span></div>
      <div><Check size={17} /><span><strong>Inspect the CI repair</strong><small>The agent fixed the failing test. All checks now pass.</small></span></div>
    </div> : <picture>
      <source media="(prefers-color-scheme: dark)" srcSet={feature === 'assistant' ? assistant : feature === 'automation' ? review : development} />
      <img src={feature === 'assistant' ? assistantLight : feature === 'automation' ? reviewLight : developmentLight}
        alt={feature === 'assistant' ? 'Assistant conversation with a task approval card' : feature === 'automation' ? 'Private agent discussion beside a pull-request review comment' : 'Agent conversation beside the code changes it made'} />
    </picture>}
    {feature === 'automation' && <div className="setup-preview-sequence" aria-label="Automation stages">
      <span>Commit</span><span>Draft PR</span><span>CI fix</span><span>Review fix</span><span>Merge</span>
    </div>}
    <figcaption><span>{CAPTIONS[feature]}</span><button type="button" aria-pressed={playing} onClick={() => setPlaying(value => !value)}>
      {playing ? <Pause size={13} /> : <Play size={13} />}{playing ? 'Pause preview' : 'Play preview'}
    </button></figcaption>
  </figure>;
}
