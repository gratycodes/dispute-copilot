export function money(minor: number, currency: string): string {
  return new Intl.NumberFormat('en-GB', { style: 'currency', currency }).format(minor / 100);
}

export function day(iso: string): string {
  return iso.slice(0, 10);
}

export function pct(x: number): string {
  return `${Math.round(x * 100)}%`;
}

export function label(code: string): string {
  return code.toLowerCase().replace(/_/g, ' ');
}
