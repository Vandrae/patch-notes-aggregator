import { describe, expect, it } from 'vitest';
import { hueFor, initials, safeExternalUrl, timeAgo } from './format';

describe('timeAgo', () => {
  const now = new Date('2026-10-01T12:00:00Z');

  it.each([
    ['2026-10-01T11:59:40Z', 'just now'],
    ['2026-10-01T11:30:00Z', '30 minutes ago'],
    ['2026-10-01T09:00:00Z', '3 hours ago'],
    ['2026-09-30T12:00:00Z', 'yesterday'],
    ['2026-09-26T12:00:00Z', '5 days ago'],
    ['2026-09-10T12:00:00Z', '3 weeks ago'],
    ['2026-07-31T12:00:00Z', '2 months ago'],
    ['2025-09-01T12:00:00Z', 'last year'],
  ])('%s -> %s', (iso, expected) => {
    expect(timeAgo(iso, now, 'en')).toBe(expected);
  });
});

describe('initials', () => {
  it.each([
    ['RuneScape: Dragonwilds', 'RD'],
    ['Elden Ring', 'ER'],
    ['Portal', 'PO'],
    ['  ', '?'],
    ['Baldur’s Gate 3', 'BG'],
  ])('%s -> %s', (name, expected) => {
    expect(initials(name)).toBe(expected);
  });
});

describe('hueFor', () => {
  it('is stable per name and within the colour wheel', () => {
    expect(hueFor('RuneScape: Dragonwilds')).toBe(hueFor('RuneScape: Dragonwilds'));
    expect(hueFor('Elden Ring')).toBeGreaterThanOrEqual(0);
    expect(hueFor('Elden Ring')).toBeLessThan(360);
    expect(hueFor('A')).not.toBe(hueFor('B'));
  });
});

describe('safeExternalUrl', () => {
  it('allows only http(s) links', () => {
    expect(safeExternalUrl('https://store.steampowered.com/news/1')).toBe('https://store.steampowered.com/news/1');
    expect(safeExternalUrl('http://example.test/x')).toBe('http://example.test/x');
    expect(safeExternalUrl('javascript:alert(1)')).toBeUndefined();
    expect(safeExternalUrl('data:text/html,<script>1</script>')).toBeUndefined();
    expect(safeExternalUrl('not a url')).toBeUndefined();
  });
});
