import Bepro from '../native/NativeBepro';
import type { DailyAggregate, DigestResult, Insight, Timeline, TodaySummary, WeekSummary } from './types';

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

let baseUrl: string | null = null;

async function base(): Promise<string> {
  if (!baseUrl) {
    const config = await Bepro.getConfig();
    if (!config.serverUrl) {
      throw new ApiError(0, 'Not paired with a server yet');
    }
    baseUrl = config.serverUrl;
  }
  return baseUrl;
}

/** Call after pairing/unpairing so the next request re-reads the server address. */
export function resetApiCache() {
  baseUrl = null;
}

async function request<T>(method: string, path: string, body?: unknown, retried = false): Promise<T> {
  const url = (await base()) + path;
  const token = await Bepro.getAccessToken(retried);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 20000);
  let response: Response;
  try {
    response = await fetch(url, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    });
  } catch {
    throw new ApiError(0, `Can't reach the server (${url}). Check Wi-Fi/data or adb reverse.`);
  } finally {
    clearTimeout(timeout);
  }
  if (response.status === 401 && !retried) {
    return request<T>(method, path, body, true);
  }
  if (!response.ok) {
    let detail = '';
    try {
      const problem = await response.json();
      detail = problem.detail ?? problem.message ?? '';
    } catch {
      // not JSON
    }
    throw new ApiError(response.status, detail || `Server error ${response.status}`);
  }
  return (await response.json()) as T;
}

export const api = {
  today: () => request<TodaySummary>('GET', '/v1/summary/today'),
  week: (end?: string) => request<WeekSummary>('GET', `/v1/summary/week${end ? `?end=${end}` : ''}`),
  daily: (from: string, to: string) => request<DailyAggregate[]>('GET', `/v1/summary/daily?from=${from}&to=${to}`),
  timeline: (date: string) => request<Timeline>('GET', `/v1/timeline?date=${date}`),
  latestInsights: () => request<Insight[]>('GET', '/v1/insights/latest'),
  insightHistory: (limit = 30) => request<Insight[]>('GET', `/v1/insights?limit=${limit}`),
  setInsightStatus: (id: string, status: 'dismissed' | 'actioned') =>
    request<Insight>('PATCH', `/v1/insights/${id}`, { status }),
  runDigest: (force = false) => request<DigestResult>('POST', `/v1/digest/run?force=${force}`),
};
