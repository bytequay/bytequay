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
import { Check, Copy } from 'lucide-react';

/** Copies one value and says so for a moment; the surrounding surface gives it its size and colour. */
export default function CopyLinkButton({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);
  const [failed, setFailed] = useState(false);
  const resetTimer = useRef<number | null>(null);

  useEffect(() => () => {
    if (resetTimer.current !== null) window.clearTimeout(resetTimer.current);
  }, []);

  const copy = async () => {
    setFailed(false);
    setCopied(false);
    try { await navigator.clipboard.writeText(value); }
    catch { setFailed(true); return; }
    setCopied(true);
    if (resetTimer.current !== null) window.clearTimeout(resetTimer.current);
    resetTimer.current = window.setTimeout(() => setCopied(false), 1500);
  };

  return (
    <button type="button" className={`pr-copy-button${copied ? ' is-copied' : ''}`}
      aria-label={copied ? `${label} copied` : `Copy ${label}`}
      title={copied ? undefined : `Copy ${label}`} onClick={() => { void copy(); }}>
      {copied
        ? <Check size={16} strokeWidth={2} aria-hidden="true" />
        : <Copy size={16} strokeWidth={1.5} aria-hidden="true" />}
      {copied && <span className="pr-copy-button__message" role="status">Copied</span>}
      {failed && <span className="pr-copy-button__message" role="alert">Could not copy. Try again.</span>}
    </button>
  );
}
