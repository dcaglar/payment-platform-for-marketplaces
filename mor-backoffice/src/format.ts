/** Cents to a readable amount, e.g. 300000 EUR -> "€3,000.00". */
export function money(cents: number, currency: string): string {
  return new Intl.NumberFormat('en-GB', { style: 'currency', currency }).format(cents / 100);
}

export function dateTime(iso: string | null): string {
  if (iso === null) {
    return '—';
  }
  return new Date(iso).toLocaleString('en-GB');
}
