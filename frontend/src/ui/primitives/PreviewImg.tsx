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
import type { ImgHTMLAttributes } from 'react';

/**
 * An attachment thumbnail that opens full-size on click — pending pasted
 * images in the composer and sent ones in the conversation both use it.
 * The native `<dialog>` gives us the top-layer backdrop, Esc-to-close and
 * focus handling for free; clicking anywhere in it closes. `className`
 * stays on the thumbnail only, so the enlarged copy isn't cropped to the
 * chip's fixed size.
 */
export function PreviewImg({ className, alt, ...img }: ImgHTMLAttributes<HTMLImageElement>) {
  const [open, setOpen] = useState(false);
  return (
    <>
      {/* ponytail: display:contents button — keyboard-reachable without
          adding a box that would break each call site's thumbnail CSS. */}
      <button
        type="button"
        className="img-preview-btn"
        title="Click to preview"
        onClick={() => setOpen(true)}
      >
        <img {...img} alt={alt} className={className} />
      </button>
      {open && (
        <dialog
          className="img-preview"
          ref={el => { el?.showModal(); }}
          onClose={() => setOpen(false)}
          onClick={e => e.currentTarget.close()}
        >
          <img {...img} alt={alt} />
        </dialog>
      )}
    </>
  );
}
