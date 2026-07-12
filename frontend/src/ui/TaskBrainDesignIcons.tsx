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

export function BackChevronIcon(props: IconProps) {
  return <Icon size={14} strokeWidth={2} {...props}><path d="m15 18-6-6 6-6" /></Icon>;
}

export function ThreadBubbleIcon(props: IconProps) {
  return <Icon size={12} strokeWidth={2} {...props}><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" /></Icon>;
}

export function UpArrowIcon(props: IconProps) {
  return (
    <Icon size={11} strokeWidth={2.2} {...props}>
      <path d="M12 19V5" />
      <path d="m5 12 7-7 7 7" />
    </Icon>
  );
}

export function TaskBranchIcon(props: IconProps) {
  return (
    <Icon size={13} strokeWidth={1.9} {...props}>
      <circle cx="6" cy="6" r="2.2" />
      <circle cx="18" cy="8" r="2.2" />
      <path d="M8.2 6.6C13 7 12 12 16.4 8.6" />
      <path d="M12 10v11" />
    </Icon>
  );
}

export function ShieldIcon(props: IconProps) {
  return <Icon size={13} strokeWidth={1.9} {...props}><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" /></Icon>;
}

export function PlanIcon(props: IconProps) {
  return (
    <Icon size={14} strokeWidth={1.7} {...props}>
      <path d="M9 11l3 3L22 4" />
      <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
    </Icon>
  );
}

export function BranchIcon(props: IconProps) {
  return (
    <Icon size={14} strokeWidth={1.7} {...props}>
      <circle cx="6" cy="6" r="2.4" />
      <circle cx="6" cy="18" r="2.4" />
      <path d="M6 8.4v7.2" />
      <path d="M18 9a3 3 0 0 0-3-3H9" />
      <circle cx="18" cy="7" r="1.8" />
    </Icon>
  );
}

export function CloudIcon(props: IconProps) {
  return <Icon size={14} strokeWidth={1.7} {...props}><path d="M17.5 19a4.5 4.5 0 0 0 .5-9 6 6 0 0 0-11.5-1.5A4 4 0 0 0 6.5 19z" /></Icon>;
}

export function BroomIcon(props: IconProps) {
  return (
    <Icon size={14} strokeWidth={1.7} {...props}>
      <path d="M13 4 8.5 8.5" />
      <path d="M14.5 7.5 16 6a2.1 2.1 0 0 1 3 3l-1.5 1.5" />
      <path d="M8 20c-2 0-3-1-3-3l4-4 4 4c0 2-1 3-3 3z" />
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

export function ChatBubbleIcon(props: IconProps) {
  return (
    <Icon size={11} strokeWidth={2} {...props}>
      <path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 8.6 8.6 0 0 1-4-1L3 20l1.1-5A8.4 8.4 0 1 1 21 11.5z" />
    </Icon>
  );
}

export function MergeBranchIcon(props: IconProps) {
  return (
    <Icon size={13} strokeWidth={2.2} {...props}>
      <circle cx="6" cy="6" r="2.4" />
      <circle cx="6" cy="18" r="2.4" />
      <circle cx="18" cy="13" r="2.4" />
      <path d="M6 8.4v7.2" />
      <path d="M6.4 8.4C7.5 11.6 10.5 13 15.6 13" />
    </Icon>
  );
}

export function EyeIcon(props: IconProps) {
  return (
    <Icon size={11} strokeWidth={1.8} {...props}>
      <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z" />
      <circle cx="12" cy="12" r="2.6" />
    </Icon>
  );
}

export function CommitIcon(props: IconProps) {
  return (
    <Icon size={12} strokeWidth={2} {...props}>
      <circle cx="12" cy="12" r="3.4" />
      <path d="M12 3v5.6" />
      <path d="M12 15.4V21" />
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

export function SparkIcon({ size = 9, ...props }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" stroke="none" aria-hidden="true" focusable="false" {...props}>
      <path d="M12 3l1.5 4.9L18.4 9l-4.9 1.6L12 15.5l-1.6-4.9L5.6 9l4.8-1.6z" />
    </svg>
  );
}

export function PanelIcon(props: IconProps) {
  return (
    <Icon size={15} strokeWidth={1.7} {...props}>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M15 4v16" />
    </Icon>
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

export function CodeIcon(props: IconProps) {
  return (
    <Icon size={12} strokeWidth={2} {...props}>
      <path d="m8 6-6 6 6 6" />
      <path d="m16 6 6 6-6 6" />
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
