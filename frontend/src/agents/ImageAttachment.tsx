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

import { useEffect, useRef, useState } from 'react';
import { Maximize, Minus, Plus, X } from 'lucide-react';

export function ImageAttachment({ name, url, compact = false }: { name: string; url: string; compact?: boolean }) {
  const [open, setOpen] = useState(false);
  return <>
    <button type="button" className="agt-image-button" aria-label={`Preview image ${name}`} title="Click to enlarge" onClick={() => setOpen(true)}>
      <img className={compact ? 'agt-thumb' : 'agt-image'} src={url} alt={name} />
    </button>
    {open && <ImageViewer name={name} url={url} onClose={() => setOpen(false)} />}
  </>;
}

function ImageViewer({ name, url, onClose }: { name: string; url: string; onClose: () => void }) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const imageRef = useRef<HTMLImageElement>(null);
  const [scale, setScale] = useState<number | null>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const minimumScale = 1 / Math.max(size.width, size.height, 1);
  useEffect(() => {
    const dialog = dialogRef.current;
    dialog?.showModal();
    closeRef.current?.focus();
    return () => { dialog?.close(); };
  }, []);

  const zoom = (factor: number) => {
    const image = imageRef.current;
    if (image === null || image.naturalWidth === 0) return;
    setScale(Math.max(minimumScale, Math.min(8, (scale ?? image.getBoundingClientRect().width / image.naturalWidth) * factor)));
  };

  return (
    <dialog
      ref={dialogRef}
      className="agt-image-viewer"
      aria-label={`Image preview ${name}`}
      onClose={(event) => { if (!event.currentTarget.open) onClose(); }}
      onClick={(event) => { if (event.target === event.currentTarget) dialogRef.current?.close(); }}
      onKeyDown={(event) => event.stopPropagation()}
    >
      <div className="agt-image-viewer__toolbar">
        <button type="button" aria-label="Zoom out" title="Zoom out" disabled={scale !== null && scale <= minimumScale} onClick={() => zoom(0.8)}><Minus aria-hidden="true" /></button>
        <button type="button" aria-label="Actual size" title="Actual size" onClick={() => setScale(1)}>{scale === null ? 'Fit' : `${Math.round(scale * 100)}%`}</button>
        <button type="button" aria-label="Zoom in" title="Zoom in" disabled={scale !== null && scale >= 8} onClick={() => zoom(1.25)}><Plus aria-hidden="true" /></button>
        <button type="button" aria-label="Fit image to window" title="Fit to window" aria-pressed={scale === null} onClick={() => setScale(null)}><Maximize aria-hidden="true" /></button>
        <button ref={closeRef} type="button" className="agt-image-viewer__close" aria-label="Close image preview" title="Close (Esc)" onClick={() => dialogRef.current?.close()}><X aria-hidden="true" /></button>
      </div>
      <div className="agt-image-viewer__canvas" onClick={(event) => { if (event.target === event.currentTarget) dialogRef.current?.close(); }}>
        <img
          ref={imageRef}
          src={url}
          alt={name}
          className={scale === null ? 'is-fit' : undefined}
          style={scale === null || size.width === 0 ? undefined : { width: size.width * scale }}
          onLoad={(event) => setSize({ width: event.currentTarget.naturalWidth, height: event.currentTarget.naturalHeight })}
        />
      </div>
    </dialog>
  );
}
