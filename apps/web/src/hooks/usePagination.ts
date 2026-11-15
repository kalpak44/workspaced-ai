import { useEffect, useState } from "react";

/**
 * One page of a list that is already loaded. `resetKey` names the filter that produced the list:
 * when it changes the view returns to the first page, and a list that shrinks under the current
 * page (after a delete, say) moves back to its last page instead of showing nothing.
 */
export function usePagination<T>(items: readonly T[], pageSize: number, resetKey: unknown = null) {
  const [page, setPage] = useState(1);
  const pageCount = Math.max(1, Math.ceil(items.length / pageSize));

  useEffect(() => {
    setPage(1);
  }, [resetKey]);

  useEffect(() => {
    setPage((current) => Math.min(current, pageCount));
  }, [pageCount]);

  const current = Math.min(page, pageCount);
  const start = (current - 1) * pageSize;
  return {
    page: current,
    pageCount,
    setPage,
    items: items.slice(start, start + pageSize),
    /** One-based positions of the first and last item shown, for "1–10 of 42". */
    first: items.length === 0 ? 0 : start + 1,
    last: Math.min(start + pageSize, items.length),
    total: items.length,
  };
}
