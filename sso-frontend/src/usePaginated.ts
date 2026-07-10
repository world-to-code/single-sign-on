import { useEffect, useState } from "react";
import { useApiData } from "./useApiData";
import type { Page } from "./api";

/**
 * Loads one page of an admin list from `basePath`, appending a page/size query (joining with `&` when
 * basePath already has a query string). Owns the current page index and resets to the first page
 * whenever `basePath` changes (e.g. a category filter switches).
 * Built on {@link useApiData}, so it inherits loading/error state and `reload()` after mutations.
 */
export function usePaginated<T>(basePath: string, size = 20) {
  const [page, setPage] = useState(0);
  useEffect(() => { setPage(0); }, [basePath]);

  const sep = basePath.includes("?") ? "&" : "?";
  const { data, error, cause, loading, reload } = useApiData<Page<T>>(`${basePath}${sep}page=${page}&size=${size}`);

  // If the current page fell past the end (e.g. the last row on the last page was deleted), step back
  // so the user isn't stranded on an empty, mislabeled page.
  const total = data?.total ?? 0;
  useEffect(() => {
    const lastPage = Math.max(0, Math.ceil(total / size) - 1);
    if (total > 0 && page > lastPage) { setPage(lastPage); }
  }, [total, size, page]);

  return {
    page, setPage, size,
    items: data?.items ?? null,
    total,
    error, cause, loading, reload,
  };
}
