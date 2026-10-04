import type { Page } from '../types';

/** Previous / next for a page of the API (pages start at 0). */
export function Pager({ page, onPage }: { page: Page<unknown>; onPage: (page: number) => void }) {
  return (
    <div className="pager">
      <button disabled={!page.hasPrevious} onClick={() => onPage(page.page - 1)}>
        ← Previous
      </button>
      <span className="muted">
        page {page.page + 1} of {Math.max(page.totalPages, 1)} · {page.totalItems} in total
      </span>
      <button disabled={!page.hasNext} onClick={() => onPage(page.page + 1)}>
        Next →
      </button>
    </div>
  );
}
