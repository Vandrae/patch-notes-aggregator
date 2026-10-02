import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, safeNext, steamLoginUrl } from './api';
import { jsonResponse, stubApi } from './test-utils';

function setCookie(value: string) {
  document.cookie = `${value}; path=/`;
}
function clearCookies() {
  document.cookie.split('; ').forEach((c) => (document.cookie = `${c.split('=')[0]}=; max-age=0; path=/`));
}

describe('api client', () => {
  beforeEach(clearCookies);
  afterEach(() => vi.unstubAllGlobals());

  it('does not send a CSRF header on reads', async () => {
    setCookie('XSRF-TOKEN=abc');
    const { calls } = stubApi({ 'GET /api/me': () => jsonResponse({ id: 1 }) });

    await api.get('/api/me');

    expect(calls[0].headers['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('echoes the XSRF cookie in a header on writes', async () => {
    setCookie('XSRF-TOKEN=tok-123');
    const { calls } = stubApi({ 'PUT /api/watchlist/1': () => jsonResponse(null, 204) });

    await api.put('/api/watchlist/1');

    expect(calls[0].headers['X-XSRF-TOKEN']).toBe('tok-123');
  });

  it('fetches the cookie first if it has not been handed out yet', async () => {
    const { calls } = stubApi({
      'GET /api/me': () => {
        setCookie('XSRF-TOKEN=fresh');
        return jsonResponse({ id: 1 }, 401);
      },
      'DELETE /api/me': () => jsonResponse(null, 204),
    });

    await api.delete('/api/me');

    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual(['GET /api/me', 'DELETE /api/me']);
    expect(calls[1].headers['X-XSRF-TOKEN']).toBe('fresh');
  });

  it('turns error responses into ApiError carrying the status', async () => {
    stubApi({ 'GET /api/me': () => jsonResponse({}, 401) });

    await expect(api.get('/api/me')).rejects.toMatchObject({ name: 'ApiError', status: 401 });
    await expect(api.get('/api/me')).rejects.toBeInstanceOf(ApiError);
  });

  it('returns undefined for 204 responses', async () => {
    setCookie('XSRF-TOKEN=t');
    stubApi({ 'DELETE /api/watchlist/1': () => jsonResponse(null, 204) });

    await expect(api.delete('/api/watchlist/1')).resolves.toBeUndefined();
  });
});

describe('login redirect safety', () => {
  it('only ever lands on same-site paths', () => {
    expect(safeNext('/feed?game=3')).toBe('/feed?game=3');
    expect(safeNext('https://evil.test')).toBe('/feed');
    expect(safeNext('//evil.test')).toBe('/feed');
    expect(safeNext('/\\evil.test')).toBe('/feed');
    expect(safeNext(null)).toBe('/feed');
  });

  it('builds the login URL with an encoded next path', () => {
    expect(steamLoginUrl('/feed?game=3')).toBe('/api/auth/steam/login?next=%2Ffeed%3Fgame%3D3');
  });
});
