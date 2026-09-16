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
import Avatar from '../../Avatar';
import MarkdownBody from '../MarkdownBody';
import { ReviewThreadMessage } from '../ReviewThreadCard';
import SaveReviewLesson from './SaveReviewLesson';
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
  if (messages.length === 0) return null;
  return (
    <div className="agr-thread">
      {messages.map(message => {
        const mine = message.author === 'USER';
        const sourceRun = review.runs.find(run => run.id === message.runId);
        return (
          <ReviewThreadMessage key={message.id} timestamp={message.createdAt}
            author={<><Avatar login={mine ? viewerLogin : 'Agent'} avatarUrl={mine ? viewerAvatarUrl : null}
              size={22} allowGitHubFallback={false} /><strong>{mine ? 'You' : 'Agent'}</strong></>}
            menu={<>{queued(review, message) && (
              <span className="agr-pill is-queued"><Spinner />Queued · runs after the current turn</span>
            )}{sourceRun !== undefined && <SaveReviewLesson review={review} run={sourceRun} message={message} />}</>}>
            <MarkdownBody body={message.body} repository={review.repo} />
          </ReviewThreadMessage>
        );
      })}
    </div>
  );
}
