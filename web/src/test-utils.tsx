import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';

export function renderApp(ui: ReactElement, route = '/') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  const result = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
  return { client, ...result };
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

type Handler = (url: string, init?: RequestInit) => Response | Promise<Response>;

/** Replaces fetch with a router keyed by "METHOD /path"; anything unrouted fails the test loudly. */
export function stubApi(routes: Record<string, Handler>) {
  const calls: { method: string; url: string; headers: Record<string, string> }[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    calls.push({ method, url, headers: (init?.headers as Record<string, string>) ?? {} });
    const key = `${method} ${url.split('?')[0]}`;
    const handler = routes[key];
    if (!handler) throw new Error(`Unrouted request in test: ${method} ${url}`);
    return handler(url, init);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { calls, fetchMock };
}
