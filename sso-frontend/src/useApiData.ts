import { useCallback, useEffect, useState } from "react";
import { apiGet } from "./api";

export interface ApiData<T> {
  data: T | null;
  error: string | null;
  /** The raw caught error, kept so DataList can render a kind-specific FailurePanel (status/trace-aware). */
  cause: unknown;
  loading: boolean;
  reload: () => void;
}

/** Loads data from the admin API on mount (and on `reload()`), with path-change cancellation. */
export function useApiData<T>(path: string): ApiData<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cause, setCause] = useState<unknown>(null);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    let active = true;
    setData(null);   // avoid flashing the previous path's data/error while the new request is in flight
    setError(null);
    setCause(null);
    apiGet<T>(path)
      .then((result) => active && setData(result))
      .catch((e) => {
        if (!active) return;
        setError(String(e));
        setCause(e);
      });
    return () => {
      active = false;
    };
  }, [path, nonce]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  const loading = data === null && error === null;
  return { data, error, cause, loading, reload };
}
