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
import type { SVGProps } from 'react';

type IconProps = SVGProps<SVGSVGElement> & {
  size?: number;
  strokeWidth?: number;
};

function Icon({ size = 14, strokeWidth = 1.7, children, ...props }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...props}
    >
      {children}
    </svg>
  );
}

export function PlanIcon(props: IconProps) {
  return (
    <Icon size={14} strokeWidth={1.7} {...props}>
      <path d="M9 11l3 3L22 4" />
      <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
    </Icon>
  );
}

export function CheckIcon(props: IconProps) {
  return <Icon size={13} strokeWidth={2.6} {...props}><path d="M20 6 9 17l-5-5" /></Icon>;
}

export function ChevronRightIcon(props: IconProps) {
  return <Icon size={11} strokeWidth={2} {...props}><path d="m9 18 6-6-6-6" /></Icon>;
}

export function CloseIcon(props: IconProps) {
  return (
    <Icon size={12} strokeWidth={2.2} {...props}>
      <path d="M18 6 6 18" />
      <path d="M6 6l12 12" />
    </Icon>
  );
}

export function ClockIcon(props: IconProps) {
  return (
    <Icon size={12} strokeWidth={1.9} {...props}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 8v4l2.5 1.5" />
    </Icon>
  );
}

export function TerminalRunIcon(props: IconProps) {
  return (
    <Icon size={14} strokeWidth={1.9} {...props}>
      <path d="M4 17l6-6-6-6" />
      <path d="M12 19h8" />
    </Icon>
  );
}

export function SearchIcon(props: IconProps) {
  return (
    <Icon size={14} strokeWidth={1.8} {...props}>
      <circle cx="11" cy="11" r="7" />
      <path d="M21 21l-4.3-4.3" />
    </Icon>
  );
}

export function McpCubeIcon(props: IconProps) {
  return (
    <Icon size={13} strokeWidth={1.6} {...props}>
      <path d="M21 8v8a2 2 0 0 1-1 1.73l-7 4a2 2 0 0 1-2 0l-7-4A2 2 0 0 1 3 16V8a2 2 0 0 1 1-1.73l7-4a2 2 0 0 1 2 0l7 4A2 2 0 0 1 21 8z" />
      <path d="M3.3 7 12 12l8.7-5" />
      <path d="M12 12v9" />
    </Icon>
  );
}

export function PenIcon(props: IconProps) {
  return (
    <Icon size={13} strokeWidth={1.8} {...props}>
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2 2 0 0 1 3 3L7 19l-4 1 1-4z" />
    </Icon>
  );
}

export function WarnTriangleIcon(props: IconProps) {
  return (
    <Icon size={15} strokeWidth={1.8} {...props}>
      <path d="M12 9v4" />
      <path d="M12 17h.01" />
      <path d="M10.3 3.9 2.4 18a1.9 1.9 0 0 0 1.7 2.8h15.8a1.9 1.9 0 0 0 1.7-2.8L13.7 3.9a1.9 1.9 0 0 0-3.4 0z" />
    </Icon>
  );
}

export function SparkIcon({ size = 9, ...props }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" stroke="none" aria-hidden="true" focusable="false" {...props}>
      <path d="M12 3l1.5 4.9L18.4 9l-4.9 1.6L12 15.5l-1.6-4.9L5.6 9l4.8-1.6z" />
    </svg>
  );
}

export function PullRequestIcon(props: IconProps) {
  return (
    <Icon size={12} strokeWidth={2} {...props}>
      <circle cx="6" cy="6" r="2.4" />
      <circle cx="6" cy="18" r="2.4" />
      <circle cx="18" cy="18" r="2.4" />
      <path d="M6 8.4v7.2" />
      <path d="M18 15.6V11a3 3 0 0 0-3-3h-2.3" />
      <path d="m14.6 5.2-2.7 2.6 2.7 2.6" />
    </Icon>
  );
}

export function PlusIcon(props: IconProps) {
  return (
    <Icon size={16} strokeWidth={2} {...props}>
      <path d="M12 5v14M5 12h14" />
    </Icon>
  );
}

export function SendUpIcon(props: IconProps) {
  return (
    <Icon size={15} strokeWidth={2.2} {...props}>
      <path d="M12 19V5M5 12l7-7 7 7" />
    </Icon>
  );
}
