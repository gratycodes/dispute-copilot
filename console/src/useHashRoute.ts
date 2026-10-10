import { useEffect, useState } from 'react';

export type Route = { page: 'queue' } | { page: 'dispute'; id: string } | { page: 'labels' };

export function parseHash(hash: string): Route {
  const m = /^#\/disputes\/([\w-]+)$/.exec(hash);
  if (m) return { page: 'dispute', id: m[1]! };
  if (hash === '#/labels') return { page: 'labels' };
  return { page: 'queue' };
}

export function useHashRoute(): Route {
  const [route, setRoute] = useState(() => parseHash(window.location.hash));
  useEffect(() => {
    const on = () => setRoute(parseHash(window.location.hash));
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return route;
}
