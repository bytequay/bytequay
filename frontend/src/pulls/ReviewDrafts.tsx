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
import type { InlineReviewCommentDto } from '../types';
import { createContext, useContext, useState, type Dispatch, type SetStateAction } from 'react';

export const PendingReviewComments = createContext<{ comments: InlineReviewCommentDto[]; onChange: (comments: InlineReviewCommentDto[]) => void } | null>(null);

/** Opt-in storage shared by the existing cards when they are hosted in a disposable IDE view. */
export const ReviewDraftStorage = createContext<{ read: (key: string) => unknown; write: (key: string, value: unknown) => void } | null>(null);

export function useReviewDraft<T>(key: string, initial: T): [T, Dispatch<SetStateAction<T>>] {
  const storage = useContext(ReviewDraftStorage);
  const read = () => (storage?.read(key) as T | undefined) ?? initial;
  const [state, setState] = useState(() => ({ key, value: read() }));
  if (state.key !== key) setState({ key, value: read() });
  const update: Dispatch<SetStateAction<T>> = next => setState(current => {
    const previous = current.key === key ? current.value : read();
    const value = typeof next === 'function' ? (next as (previous: T) => T)(previous) : next;
    storage?.write(key, Object.is(value, initial) ? undefined : value);
    return { key, value };
  });
  return [state.key === key ? state.value : read(), update];
}
