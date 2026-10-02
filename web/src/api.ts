export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

function readCookie(name: string): string | undefined {
  return document.cookie
    .split('; ')
    .find((c) => c.startsWith(name + '='))
    ?.slice(name.length + 1);
}

/**
 * The server protects browser sessions against cross-site request forgery: every write must echo the XSRF-TOKEN
 * cookie in an X-XSRF-TOKEN header, which a page on another site can't read. The cookie is handed out on any
 * API response; if we somehow don't have it yet, one cheap GET fetches it.
 */
async function csrfToken(): Promise<string | undefined> {
  let token = readCookie('XSRF-TOKEN');
  if (!token) {
    await fetch('/api/me').catch(() => undefined);
    token = readCookie('XSRF-TOKEN');
  }
  return token ? decodeURIComponent(token) : undefined;
}

async function request<T>(method: string, path: string): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (!SAFE_METHODS.has(method)) {
    const token = await csrfToken();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }
  const response = await fetch(path, { method, headers });
  if (!response.ok) {
    throw new ApiError(response.status, `${method} ${path} failed with ${response.status}`);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  put: <T = void>(path: string) => request<T>('PUT', path),
  post: <T = void>(path: string) => request<T>('POST', path),
  delete: <T = void>(path: string) => request<T>('DELETE', path),
};

/** Where "Sign in through Steam" goes; `next` is where to land afterwards (same-site paths only). */
export function steamLoginUrl(next: string = '/feed'): string {
  return `/api/auth/steam/login?next=${encodeURIComponent(safeNext(next))}`;
}

/** Only ever send people to a path on this site after login (the server enforces this too). */
export function safeNext(next: string | null | undefined): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.includes('\\')) return '/feed';
  return next;
}
