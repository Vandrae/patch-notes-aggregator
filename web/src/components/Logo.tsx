import { useId } from 'react';

/**
 * The Patch Notes logo: a note page with a green patch stitched onto it. "Patch notes", drawn literally.
 *
 * Steam-flavoured palette (slate navy tile, pale page, button-green patch). The colours are fixed rather than taken
 * from the theme, so the mark looks the same on light and dark pages; the tile carries a faint light rim so it still
 * separates from a dark header. Drawn on a 32x32 grid so it stays legible from a 16px browser tab to the large
 * sign-in mark. Keep it in step with {@code public/favicon.svg}, which is the same drawing with literal gradient ids.
 */
export function Logo({ size = 28 }: { size?: number }) {
  // gradient ids must be unique per rendered copy, or every copy would reuse the first one's definition
  const id = useId().replace(/[^a-zA-Z0-9]/g, '');
  return (
    <svg viewBox="0 0 32 32" width={size} height={size} aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id={`${id}tile`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#35546d" />
          <stop offset=".45" stopColor="#1b2838" />
          <stop offset="1" stopColor="#10161f" />
        </linearGradient>
        <linearGradient id={`${id}sheen`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#fff" stopOpacity=".16" />
          <stop offset=".5" stopColor="#fff" stopOpacity="0" />
        </linearGradient>
        <linearGradient id={`${id}patch`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#a4d007" />
          <stop offset="1" stopColor="#3f7a1c" />
        </linearGradient>
      </defs>
      {/* tile, with a soft top sheen and a faint rim */}
      <rect width="32" height="32" rx="8" fill={`url(#${id}tile)`} />
      <rect width="32" height="16" rx="8" fill={`url(#${id}sheen)`} />
      <rect x=".5" y=".5" width="31" height="31" rx="7.5" fill="none" stroke="#fff" strokeOpacity=".1" />
      {/* the note: a page with a folded corner and two lines, tilted slightly back */}
      <g transform="rotate(-7 12 13)">
        <path d="M5.5 7.5a2 2 0 0 1 2-2h8l4.5 4.5V20a2 2 0 0 1-2 2h-10.5a2 2 0 0 1-2-2z" fill="#c7d5e0" />
        <rect x="8" y="11" width="7" height="1.5" rx=".75" fill="#46657e" />
        <rect x="8" y="14.2" width="5" height="1.5" rx=".75" fill="#46657e" />
      </g>
      {/* the patch: a green square with a stitched border and a plus, tilted the other way */}
      <g transform="rotate(9 21.5 21.5)">
        <rect x="14" y="14" width="15" height="15" rx="3.2" fill={`url(#${id}patch)`} stroke="#10161f" strokeWidth="1.4" />
        <rect
          x="15.8"
          y="15.8"
          width="11.4"
          height="11.4"
          rx="2"
          fill="none"
          stroke="#fff"
          strokeOpacity=".85"
          strokeWidth=".9"
          strokeDasharray="1.6 1.2"
        />
        <path d="M21.5 18.8v5.4M18.8 21.5h5.4" stroke="#fff" strokeWidth="2" strokeLinecap="round" />
      </g>
    </svg>
  );
}
