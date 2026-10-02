import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Logo } from './Logo';

describe('Logo', () => {
  it('renders at the requested size (28 by default), hidden from screen readers because it always sits beside the words "Patch Notes"', () => {
    const { container, rerender } = render(<Logo size={48} />);

    const svg = container.querySelector('svg')!;
    expect(svg).toHaveAttribute('width', '48');
    expect(svg).toHaveAttribute('height', '48');
    expect(svg).toHaveAttribute('aria-hidden', 'true');
    expect(svg).toHaveAttribute('focusable', 'false');

    rerender(<Logo />);
    expect(container.querySelector('svg')).toHaveAttribute('width', '28');
  });

  it('draws a note page with a stitched green patch carrying a plus', () => {
    const { container } = render(<Logo />);

    expect(container.querySelector('path[fill="#c7d5e0"]')).toBeInTheDocument();   // the page
    expect(container.querySelector('rect[stroke-dasharray]')).toBeInTheDocument(); // the stitching
    expect(container.querySelector('path[d="M21.5 18.8v5.4M18.8 21.5h5.4"]')).toBeInTheDocument(); // the plus
  });

  it('gives every copy its own gradient ids, so two logos on one page never share (or break) each other\'s fills', () => {
    const { container } = render(
      <>
        <Logo size={28} />
        <Logo size={56} />
      </>,
    );

    const ids = [...container.querySelectorAll('linearGradient')].map((g) => g.id);
    expect(ids).toHaveLength(6);                       // 3 gradients per logo
    expect(new Set(ids).size).toBe(6);                 // all different
    // and every fill refers to a gradient that really exists in the document
    for (const fill of [...container.querySelectorAll('[fill^="url(#"]')].map((e) => e.getAttribute('fill')!)) {
      const target = fill.slice(5, -1);
      expect(container.querySelector(`[id="${target}"]`), fill).not.toBeNull();
    }
  });
});
