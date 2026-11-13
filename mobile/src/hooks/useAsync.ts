import { useCallback, useEffect, useRef, useState } from 'react';

export type AsyncState<T> = {
  data: T | undefined;
  error: string | undefined;
  loading: boolean;
  reload: () => Promise<void>;
};

/**
 * Minimal data loader: fetch on mount and whenever `deps` change, keep the last good data while
 * reloading, and ignore responses that arrive after the component unmounted or deps changed.
 */
export function useAsync<T>(load: () => Promise<T>, deps: unknown[]): AsyncState<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(true);
  const generation = useRef(0);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(load, deps);

  const reload = useCallback(async () => {
    const mine = ++generation.current;
    setLoading(true);
    try {
      const result = await run();
      if (mine === generation.current) {
        setData(result);
        setError(undefined);
      }
    } catch (e) {
      if (mine === generation.current) {
        setError(e instanceof Error ? e.message : String(e));
      }
    } finally {
      if (mine === generation.current) {
        setLoading(false);
      }
    }
  }, [run]);

  useEffect(() => {
    reload();
    // Bumping the counter makes any in-flight response from this load be ignored.
    const counter = generation;
    return () => {
      counter.current++;
    };
  }, [reload]);

  return { data, error, loading, reload };
}
