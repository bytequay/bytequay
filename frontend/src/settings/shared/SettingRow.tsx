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
  title: ReactNode;
  description?: ReactNode;
  /** Control(s) rendered to the right — toggle, button, input, group of buttons, etc. */
  control: ReactNode;
};

function SettingRow({ title, description, control }: Props) {
  return (
    <div className="setting-row">
      <div className="setting-row__text">
        <div className="setting-row__title">{title}</div>
        {description && <div className="setting-row__desc">{description}</div>}
      </div>
      <div className="setting-row__control">{control}</div>
    </div>
  );
}

export default SettingRow;
