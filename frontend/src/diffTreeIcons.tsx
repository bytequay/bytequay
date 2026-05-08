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

export function Chevron({ open }: { open: boolean }) {
  return (
    <svg
      className={`tree-chevron${open ? ' tree-chevron--open' : ''}`}
      width="10"
      height="10"
      viewBox="0 0 10 10"
      aria-hidden="true"
    >
      <path
        d="M3.5 2L7 5L3.5 8"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
    </svg>
  );
}

export function FolderIcon({ open }: { open: boolean }) {
  return (
    <svg
      className="tree-folder"
      width="14"
      height="14"
      viewBox="0 0 14 14"
      aria-hidden="true"
    >
      {open ? (
        <path
          d="M1.5 3.5a1 1 0 0 1 1-1h3l1.2 1.2h4.8a1 1 0 0 1 1 1v.8H3.1l-1.6 5.6a.5.5 0 0 1-.5.4H1V3.5Zm1.2 7.5 1.5-5.2h9.3l-1.5 5.2H2.7Z"
          fill="currentColor"
        />
      ) : (
        <path
          d="M2.5 2.5a1 1 0 0 0-1 1v7a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1V5a1 1 0 0 0-1-1H7.2L6 2.5H2.5Z"
          fill="currentColor"
        />
      )}
    </svg>
  );
}
