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
import type { CSSProperties, ReactNode } from 'react';
import { MarkdownProse } from '../../../threads/MarkdownProse';
import { useAttachmentImages } from '../../../threads/useAttachmentImages';

/**
 * Layer-2 conversation unit: a round — one user turn (or an autonomous
 * `R#` tag) plus the agent's whole response to it. The atomic unit of the
 * timeline; a long stage is a short list of rounds. Anchored by either a
 * {@link UserTurn} child or the `tag` for autonomous rounds.
 */
export function Round({ tag, children }: { tag?: ReactNode; children: ReactNode }) {
  return (
    <div className="sp-round">
      {tag !== undefined && <span className="sp-round__tag">{tag}</span>}
      {children}
    </div>
  );
}

/**
 * Layer-2 conversation unit: the human's turn — a teal spine node + a
 * teal-bordered block. First-class and never folds; the spine answers
 * "where did I intervene?" by these nodes alone.
 */
export function UserTurn({ text, timestamp, glyph = 'Y', threadId, images, managedSkills = [], messageSeq }: {
  text: string;
  timestamp?: ReactNode;
  glyph?: ReactNode;
  /** The thread images are scoped under — required (alongside `images`) to
   *  resolve attached-screenshot thumbnails. */
  threadId?: string;
  /** Attached-image file paths from the message's envelope (see
   *  `extractImages`), resolved to renderable thumbnails via the bridge. */
  images?: string[];
  /** Hidden runtime markers. Shown only when the user opens the disclosure. */
  managedSkills?: string[];
  messageSeq?: number | null;
}) {
  const resolvedImages = useAttachmentImages(threadId ?? '', images ?? []);
  return (
    <div className="sp-uturn" data-seq={messageSeq ?? undefined}>
      <span className="sp-uturn__mark" aria-hidden>{glyph}</span>
      <div className="sp-ublock">
        <div className="sp-ublock__who">
          You{timestamp !== undefined && <span className="ago">{timestamp}</span>}
          {managedSkills.length > 0 && (
            <details style={runtimeDetailsStyle}>
              <summary style={runtimeSummaryStyle}>runtime</summary>
              <div style={runtimeBodyStyle}>Managed skills: {managedSkills.join(', ')}</div>
            </details>
          )}
        </div>
        {resolvedImages.length > 0 && (
          <div className="sp-ublock__images">
            {resolvedImages.map(src => <img key={src} src={src} alt="Attached" className="sp-ublock__img" />)}
          </div>
        )}
        <div className="sp-ublock__tx"><MarkdownProse text={text} /></div>
      </div>
    </div>
  );
}

const runtimeDetailsStyle: CSSProperties = {
  display: 'inline-block',
  marginLeft: 8,
  fontSize: 10.5,
  fontWeight: 600,
  color: 'var(--text-4)',
};

const runtimeSummaryStyle: CSSProperties = {
  cursor: 'pointer',
  userSelect: 'none',
};

const runtimeBodyStyle: CSSProperties = {
  marginTop: 4,
  fontFamily: 'var(--mono)',
  fontSize: 10,
  fontWeight: 500,
  color: 'var(--text-3)',
};

/**
 * Layer-2 conversation unit: the headline — the agent's final message of a
 * round, always visible (the work folds, the conclusion stays). `color`
 * tints the dot + name to the active surface (brain blue, trunk blue).
 * When `reply` is set the headline tucks under a user turn with a dashed
 * `↳ replies` connector (a question + its answer read as one exchange).
 */
export function Headline({ who = 'Brain', body, timestamp, color = 'blue', reply = false }: {
  who?: ReactNode;
  body: string;
  timestamp?: ReactNode;
  color?: 'blue' | 'purple';
  reply?: boolean;
}) {
  if (reply) {
    return (
      <div className="sp-reply">
        <div className="sp-headline__tx"><MarkdownProse text={body} /></div>
      </div>
    );
  }
  return (
    <div className={`sp-headline sp-headline--${color}`}>
      <div className="sp-headline__who">
        <span className="sp-headline__ava" aria-hidden>B</span>{who}
        {timestamp !== undefined && <span className="ago">{timestamp}</span>}
      </div>
      <div className="sp-headline__tx"><MarkdownProse text={body} /></div>
    </div>
  );
}

/**
 * Layer-2 conversation unit: a small gray chatter dot for an agent prose
 * message that isn't a round headline — the lowest-signal message tier.
 * Folds away in Focused density.
 */
export function BrainDot({ body, timestamp, who = 'Brain' }: {
  body: string;
  timestamp?: ReactNode;
  who?: ReactNode;
}) {
  return (
    <div className="sp-bmsg">
      <div className="sp-headline__who">
        <span className="sp-headline__ava" aria-hidden>B</span>{who}
        {timestamp !== undefined && <span className="ago">{timestamp}</span>}
      </div>
      <div className="sp-headline__tx"><MarkdownProse text={body} /></div>
    </div>
  );
}
