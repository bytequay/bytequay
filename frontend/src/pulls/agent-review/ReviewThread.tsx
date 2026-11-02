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
import Avatar from '../../Avatar';
import MarkdownBody from '../MarkdownBody';
import { commentIndexTarget } from '../ReviewCommentIndex';
import { ProviderAvatar } from '../../agents/ProviderIcon';
import { LocalCardMessage } from '../local-review/LocalCard';
import type { AgentReviewDto, ReviewMessageDto } from './contracts';
import { queued } from './model';

function Spinner() {
  return <span className="agr-spinner" aria-hidden="true" />;
}

/**
 * One private discussion, on the summary or on a finding.
 *
 * A question the agent has not started yet is marked queued, because the alternative is a message
 * that looks sent and silently is not.
 */
export default function ReviewThread({ review, messages, viewerLogin, viewerAvatarUrl }: {
  review: AgentReviewDto;
  messages: ReviewMessageDto[];
  viewerLogin: string;
  viewerAvatarUrl: string | null;
}) {
  const [expanded, setExpanded] = useState(false);
  if (messages.length === 0) return null;
  // A long discussion keeps its opening question and latest exchange in view; the rounds between fold.
  const hidden = expanded || messages.length <= 4 ? 0 : messages.length - 3;
  const render = (message: ReviewMessageDto) => {
    const mine = message.author === 'USER';
    // A development agent answers a finding from its own task, so its reply must not read as the reviewer's.
    const developer = message.developerTaskId != null;
    const author = mine ? 'You' : developer ? 'Developer' : 'Agent review';
    return (
      <LocalCardMessage key={message.id} timestamp={message.createdAt} author={author}
        attributes={commentIndexTarget(mine ? 'human' : 'agent', author, message.body)}
        avatar={mine ? <Avatar login={viewerLogin || 'You'} avatarUrl={viewerAvatarUrl} size={22} allowGitHubFallback={false} />
          : developer ? <Avatar login="Agent" size={22} allowGitHubFallback={false} /> : <ProviderAvatar id={review.task.laneId} />}
        menu={queued(review, message) && (review.queuePause === null
          ? <span className="agr-pill is-queued"><Spinner />Queued · runs after the current turn</span>
          // A paused question waits for Resume in the conversation; it does not run by itself.
          : <span className="agr-pill is-queued" title={review.queuePause}>Paused · resume it in the conversation</span>)}>
        <MarkdownBody body={message.body} repository={review.repo} />
      </LocalCardMessage>
    );
  };
  return (
    <div className="agr-thread">
      {hidden === 0 ? messages.map(render) : <>
        {render(messages[0])}
        <button type="button" className="agr-thread__fold" onClick={() => setExpanded(true)}>Show {hidden} more replies</button>
        {messages.slice(-2).map(render)}
      </>}
    </div>
  );
}
