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
import type { ReactNode } from 'react';
import { EventRow } from './EventRow';
import { useAttachmentImages } from '../../threads/useAttachmentImages';

/**
 * The user's own message — an {@link EventRow} in the teal `user` colour
 * ("You"), keeping visual weight on the agent's work rather than a big
 * chat bubble. Body renders as plain text (user prompts skip markdown).
 */
export function UserMsg({ text, timestamp, who = 'You', children, threadId, images }: {
  text?: string;
  timestamp?: ReactNode;
  who?: ReactNode;
  children?: ReactNode;
  /** The thread images are scoped under — required (alongside `images`) to
   *  resolve attached-screenshot thumbnails. */
  threadId?: string;
  /** Attached-image file paths from the message's envelope, resolved to
   *  renderable thumbnails via the bridge. */
  images?: string[];
}) {
  const resolvedImages = useAttachmentImages(threadId ?? '', images ?? []);
  return (
    <EventRow kind="user" who={who} timestamp={timestamp}>
      {resolvedImages.length > 0 && (
        <div className="sp-ublock__images">
          {resolvedImages.map(src => <img key={src} src={src} alt="Attached" className="sp-ublock__img" />)}
        </div>
      )}
      {text !== undefined ? <div className="tx">{text}</div> : children}
    </EventRow>
  );
}
