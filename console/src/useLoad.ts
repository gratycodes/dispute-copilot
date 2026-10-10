import { useEffect, useRef, useState } from 'react';

/** Load data for `key`; reloads when the key changes or reload() is called. Stale responses are dropped. */
export function useLoad<T>(load: () => Promise<T>, key: string) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const loadRef = useRef(load);
  useEffect(() => {
    loadRef.current = load;
  });
  useEffect(() => {
    let live = true;
    loadRef.current().then(
      (d) => {
        if (live) {
          setData(d);
          setError(null);
        }
      },
      (e: unknown) => {
        if (live) setError(e instanceof Error ? e.message : String(e));
      },
    );
    return () => {
      live = false;
    };
  }, [key, tick]);
  return { data, error, reload: () => setTick((t) => t + 1) };
}
