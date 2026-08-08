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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { Avatar, Chev, Kbd, StatusDot, Tag } from './index';

afterEach(cleanup);

describe('StatusDot', () => {
  it('renders the variant modifier class', () => {
    const { container } = render(<StatusDot variant="active" title="DevelopmentStage running" />);
    const dot = container.querySelector('.v3-dot');
    expect(dot?.className).toBe('v3-dot v3-dot--active');
    expect(dot?.getAttribute('title')).toBe('DevelopmentStage running');
  });

  it('matches snapshot for every variant', () => {
    const { container } = render(
      <>
        <StatusDot variant="active" />
        <StatusDot variant="planning" />
        <StatusDot variant="sleep" />
        <StatusDot variant="done" />
        <StatusDot variant="future" />
      </>,
    );
    expect(container).toMatchSnapshot();
  });
});

describe('Tag', () => {
  it('defaults to the accent base class with no modifier', () => {
    const { container } = render(<Tag>enhancement</Tag>);
    expect(container.querySelector('.v3-tag')?.className).toBe('v3-tag');
  });

  it('adds a colour modifier for non-accent colours', () => {
    const { container } = render(<Tag color="green">ui</Tag>);
    expect(container.querySelector('.v3-tag')?.className).toBe('v3-tag v3-tag--green');
  });

  it('matches snapshot', () => {
    const { container } = render(
      <>
        <Tag>accent</Tag>
        <Tag color="green">green</Tag>
        <Tag color="orange">orange</Tag>
        <Tag color="teal">teal</Tag>
      </>,
    );
    expect(container).toMatchSnapshot();
  });
});

describe('Avatar', () => {
  it('sizes the box + font and stays decorative without a label', () => {
    const { container } = render(<Avatar initials="JC" size={26} hue="teal" />);
    const el = container.querySelector('.v3-avatar') as HTMLElement;
    expect(el.className).toBe('v3-avatar v3-avatar--teal');
    expect(el.style.width).toBe('26px');
    expect(el.style.fontSize).toBe('11px');
    expect(el.getAttribute('aria-hidden')).toBe('true');
    expect(el.textContent).toBe('JC');
  });

  it('exposes a label to assistive tech when given', () => {
    render(<Avatar initials="JC" label="Jack Chen" />);
    expect(screen.getByRole('img', { name: 'Jack Chen' })).toBeTruthy();
  });

  it('matches snapshot', () => {
    const { container } = render(<Avatar initials="AB" size={18} hue="amber" />);
    expect(container).toMatchSnapshot();
  });
});

describe('Kbd', () => {
  it('renders a <kbd> with the hint', () => {
    const { container } = render(<Kbd>⌘B</Kbd>);
    const kbd = container.querySelector('kbd.v3-kbd');
    expect(kbd?.textContent).toBe('⌘B');
  });
});

describe('Chev', () => {
  it('adds the open modifier only when open', () => {
    const { container, rerender } = render(<Chev />);
    expect(container.querySelector('.v3-chev')?.getAttribute('class')).toBe('v3-chev');
    rerender(<Chev open />);
    expect(container.querySelector('.v3-chev')?.getAttribute('class')).toBe('v3-chev v3-chev--open');
  });
});
