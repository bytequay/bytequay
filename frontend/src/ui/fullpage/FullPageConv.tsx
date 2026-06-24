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
import { Composer } from '../shell';

/**
 * The kept-visible conversation column on the left of the full-page
 * Changes and CI Status views — the conversation plus the pinned
 * composer, so the agent stays steerable without leaving the diff/log.
 * Shared between both full-page views.
 */
export function FullPageConv({ conversation, composer }: {
  conversation: ReactNode;
  composer: {
    value: string;
    onChange: (next: string) => void;
    onSubmit: () => void;
    busy?: boolean;
    modePill?: ReactNode;
    placeholder?: string;
  };
}) {
  return (
    <div className="full-page-conv">
      {conversation}
      <Composer
        value={composer.value}
        onChange={composer.onChange}
        onSubmit={composer.onSubmit}
        busy={composer.busy}
        modePill={composer.modePill}
        placeholder={composer.placeholder}
      />
    </div>
  );
}
