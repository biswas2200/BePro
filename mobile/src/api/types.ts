// Mirrors the Spring Boot response records (backend/src/main/java/app/bepro/...).

export type DailyAggregate = {
  date: string; // YYYY-MM-DD
  sleepHours?: number;
  sleepStart?: string;
  sleepEnd?: string;
  sleepSource?: 'manual' | 'health_connect' | 'inferred';
  screenMinutes: number;
  unlocks: number;
  usageMinutes: number;
  steps?: number;
  topApp?: string;
  topAppLabel?: string;
  topAppMinutes?: number;
  focusSessions: number;
  focusMinutes: number;
  activeMinutes: number;
  moodScore?: number;
};

export type TodaySummary = {
  today: DailyAggregate;
  yesterday: DailyAggregate;
  lastSyncAt?: string;
  timezone: string;
};

export type WeekSummary = {
  from: string;
  to: string;
  daysWithData: number;
  completeDays: number;
  sleep: {
    avgHours?: number;
    avgSleepTime?: string;
    avgWakeTime?: string;
    nightsUnder6h: number;
    nightsTracked: number;
  };
  topApps: { packageName: string; label: string; dailyAvgMinutes: number }[];
  screen: { avgDailyMinutes?: number; peakHour?: number; avgUnlocks?: number };
  focus: { count: number; avgDurationMinutes?: number; completionRate?: number };
  steps: { avgDaily?: number; daysUnder5000: number; daysTracked: number };
  mood: { avgScore?: number; lowestDay?: string; lowestScore?: number; notesKeywords: string[]; entries: number };
};

export type HourBucket = {
  hour: number;
  screenMinutes: number;
  unlocks: number;
  steps: number;
  activeMinutes: number;
  topApp?: string;
  topAppMinutes: number;
};

export type Timeline = { date: string; hours: HourBucket[] };

export type Insight = {
  id: string;
  kind: 'weekly' | 'alert' | 'notice';
  generatedAt: string;
  weekStart?: string;
  pattern: string;
  dataEvidence: string;
  action: string;
  category: string;
  confidence: 'high' | 'medium';
  status: 'active' | 'dismissed' | 'actioned';
};

export type DigestResult = {
  status: 'generated' | 'skipped' | 'failed';
  reason?: string;
  weekStart: string;
};
