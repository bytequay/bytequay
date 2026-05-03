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

type Props = {
  title?: string;
  hint?: ReactNode;
  /** Optional control rendered to the right of the title (e.g. an action button). */
  action?: ReactNode;
  children?: ReactNode;
};

function SettingCard({ title, hint, action, children }: Props) {
  return (
    <section className="setting-card">
      {(title || action) && (
        <header className="setting-card__head">
          {title && <h3 className="setting-card__title">{title}</h3>}
          {action}
        </header>
      )}
      {hint && <p className="setting-card__hint">{hint}</p>}
      {children}
    </section>
  );
}

export default SettingCard;
