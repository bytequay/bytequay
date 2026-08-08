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

/** Outline glyphs shared by the settings pages. All 24x24, stroked with
 *  `currentColor` so they inherit the row they sit in. */

type Props = { size?: number; width?: number };

function Svg({ size = 14, width = 1.8, children }: Props & { children: React.ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={width}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  );
}

export function CheckIcon(props: Props) {
  return <Svg {...props} width={props.width ?? 2.6}><path d="M20 6.5 9.4 17.1 4.2 11.9" /></Svg>;
}

export function PlusIcon(props: Props) {
  return <Svg {...props} width={props.width ?? 2.2}><path d="M12 5v14" /><path d="M5 12h14" /></Svg>;
}

export function RefreshIcon(props: Props) {
  return (
    <Svg {...props} width={props.width ?? 2}>
      <path d="M20 11.5A8 8 0 0 0 6.3 6.3L4 8.5" />
      <path d="M4 4.5v4h4" />
      <path d="M4 12.5a8 8 0 0 0 13.7 5.2L20 15.5" />
      <path d="M20 19.5v-4h-4" />
    </Svg>
  );
}

export function PencilIcon(props: Props) {
  return <Svg {...props} width={props.width ?? 1.9}><path d="M4 20h4L20 8l-4-4L4 16z" /></Svg>;
}

export function TrashIcon(props: Props) {
  return (
    <Svg {...props}>
      <path d="M4.5 7h15" />
      <path d="M9 7V4.8h6V7" />
      <path d="M6.5 7l.9 12.2h9.2L17.5 7" />
    </Svg>
  );
}

export function WarnIcon(props: Props) {
  return (
    <Svg {...props} width={props.width ?? 1.9}>
      <path d="M12 4.5 21 19.5H3z" />
      <path d="M12 10v4" />
      <path d="M12 16.8h.01" />
    </Svg>
  );
}

export function InfoIcon(props: Props) {
  return (
    <Svg {...props} width={props.width ?? 1.9}>
      <circle cx="12" cy="12" r="8.6" />
      <path d="M12 11v5.5" />
      <path d="M12 7.9h.01" />
    </Svg>
  );
}

export function LockIcon(props: Props) {
  return (
    <Svg {...props} width={props.width ?? 1.9}>
      <rect x="4.5" y="10.5" width="15" height="10" rx="2.4" />
      <path d="M8 10.5V7.8a4 4 0 0 1 8 0v2.7" />
    </Svg>
  );
}

export function IssueIcon(props: Props) {
  return <Svg {...props}><circle cx="12" cy="12" r="8.6" /><circle cx="12" cy="12" r="2.2" /></Svg>;
}

export function ClipboardIcon(props: Props) {
  return <Svg {...props}><path d="M6 3.5h9L19 8v12.5H6z" /><path d="M9 12h7M9 16h5" /></Svg>;
}

export function StarIcon({ size = 15, filled = false }: { size?: number; filled?: boolean }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill={filled ? '#f2cc60' : 'none'}
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="m12 4 2.4 5 5.6.7-4 3.9 1 5.4-5-2.7-5 2.7 1-5.4-4-3.9 5.6-.7z" />
    </svg>
  );
}
