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
import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';

type Props = {
  text: string;
  onUndo?: () => void;
  onDismiss: () => void;
};

/** Bottom-centered confirmation. Render with a per-message `key` so a new
 *  message restarts the timer instead of inheriting the previous one's. */
export default function Toast({ text, onUndo, onDismiss }: Props) {
  const dismiss = useRef(onDismiss);
  dismiss.current = onDismiss;

  useEffect(() => {
    const timer = window.setTimeout(() => dismiss.current(), 4200);
    return () => window.clearTimeout(timer);
  }, []);

  return createPortal(
    <div className="bq-toast" role="status">
      <span className="bq-toast__text">{text}</span>
      {onUndo && (
        <button className="bq-toast__undo" type="button" onClick={onUndo}>Undo</button>
      )}
    </div>,
    document.body,
  );
}
