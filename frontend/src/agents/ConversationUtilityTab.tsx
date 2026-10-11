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
import { Bot, MessageCircle, X } from 'lucide-react';

export type ConversationUtility = 'agents' | 'discussions';

export default function ConversationUtilityTab({ tab, count, active, onSelect, onClose }: {
  tab: ConversationUtility;
  count: number;
  active: boolean;
  onSelect: () => void;
  onClose: () => void;
}): React.JSX.Element {
  const label = tab === 'agents' ? 'Subagents' : 'Discussions';
  return <span className={`pull-detail__utility${active ? ' is-active' : ''}`}>
    <button type="button" className={active ? 'is-active' : ''} aria-pressed={active} onClick={onSelect}>
      {tab === 'agents' ? <Bot aria-hidden="true" /> : <MessageCircle aria-hidden="true" />} {label}
      {count > 0 && <> <span className="agt-chip">{count}</span></>}
    </button>
    <button type="button" className="pull-detail__utility-close" aria-label={`Close ${label}`} title={`Close ${label}`}
      onClick={onClose}><X aria-hidden="true" /></button>
  </span>;
}
