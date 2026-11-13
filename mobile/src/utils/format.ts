/** 135 → "2h 15m", 45 → "45m" */
export function minutes(value: number | undefined | null): string {
  if (value == null) {
    return '—';
  }
  const m = Math.round(value);
  if (m < 60) {
    return `${m}m`;
  }
  const h = Math.floor(m / 60);
  const rest = m % 60;
  return rest === 0 ? `${h}h` : `${h}h ${rest}m`;
}

export function hours(value: number | undefined | null): string {
  return value == null ? '—' : `${value.toFixed(1)} h`;
}

export function count(value: number | undefined | null): string {
  if (value == null) {
    return '—';
  }
  return Math.round(value).toLocaleString('en-IN');
}

function pad(n: number) {
  return n < 10 ? `0${n}` : String(n);
}

/** ISO instant → "23:40" in the phone's local time. */
export function clock(iso: string | undefined | null): string {
  if (!iso) {
    return '—';
  }
  const d = new Date(iso);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function clockFromMillis(ms: number): string {
  const d = new Date(ms);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Date → "2026-10-05" using local calendar date. */
export function isoDate(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number);
  return isoDate(new Date(y, m - 1, d + days));
}

export function today(): string {
  return isoDate(new Date());
}

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "2026-10-05" → "Mon 5 Oct" */
export function dayLabel(date: string): string {
  const [y, m, d] = date.split('-').map(Number);
  const dt = new Date(y, m - 1, d);
  return `${WEEKDAYS[dt.getDay()]} ${d} ${MONTHS[m - 1]}`;
}

export function shortDay(date: string): string {
  const [y, m, d] = date.split('-').map(Number);
  return WEEKDAYS[new Date(y, m - 1, d).getDay()].slice(0, 2);
}

/** "5 min ago", "3 h ago", "2 d ago" */
export function ago(ms: number | undefined | null): string {
  if (!ms) {
    return 'never';
  }
  const diff = Math.max(0, Date.now() - ms) / 60000;
  if (diff < 1) {
    return 'just now';
  }
  if (diff < 60) {
    return `${Math.round(diff)} min ago`;
  }
  if (diff < 48 * 60) {
    return `${Math.round(diff / 60)} h ago`;
  }
  return `${Math.round(diff / 1440)} d ago`;
}
