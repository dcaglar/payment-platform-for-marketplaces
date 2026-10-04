import type { ApiError } from '../api';

/** Loading and error lines, the same on every screen. 404 = not found for this person (another merchant's, or unknown). */
export function Status({ loading, error }: { loading: boolean; error: ApiError | null }) {
  if (loading) {
    return <p className="muted">Loading…</p>;
  }
  if (error === null) {
    return null;
  }
  if (error.status === 404) {
    return <p className="error">Not found.</p>;
  }
  if (error.status === 403) {
    return <p className="error">You may not see this.</p>;
  }
  return <p className="error">{error.message}</p>;
}
