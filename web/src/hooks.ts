import { createContext, useContext, useEffect, useState } from 'react';

export function useDocumentTitle(title: string) {
  useEffect(() => {
    document.title = title ? `${title} · Patch Notes` : 'Patch Notes';
  }, [title]);
}

export function useDebounced<T>(value: T, delayMs = 250): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

/** Lets any component announce a change to screen readers (e.g. "Now watching X") via a shared live region. */
export const AnnounceContext = createContext<(message: string) => void>(() => undefined);
export const useAnnounce = () => useContext(AnnounceContext);
