import React, { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { api } from '../api/client';
import type { DailyAggregate } from '../api/types';
import { BarChart } from '../components/BarChart';
import { Card, ErrorBanner, Muted, Screen, SectionTitle, Segmented } from '../components/ui';
import { useAsync } from '../hooks/useAsync';
import { font, radius, space, useTheme } from '../theme';
import * as fmt from '../utils/format';

type Metric = {
  key: string;
  label: string;
  value: (d: DailyAggregate) => number | null;
  format: (v: number) => string;
  target?: number;
  /** true when going over the target is the bad direction */
  lowerIsBetter?: boolean;
};

const METRICS: Metric[] = [
  { key: 'sleep', label: 'Sleep', value: d => d.sleepHours ?? null, format: v => `${v.toFixed(1)}h`, target: 7 },
  {
    key: 'screen',
    label: 'Screen',
    value: d => (d.screenMinutes > 0 ? d.screenMinutes : null),
    format: v => fmt.minutes(v),
    target: 180,
    lowerIsBetter: true,
  },
  { key: 'steps', label: 'Steps', value: d => d.steps ?? null, format: v => (v >= 1000 ? `${(v / 1000).toFixed(1)}k` : `${Math.round(v)}`), target: 7000 },
  { key: 'unlocks', label: 'Unlocks', value: d => (d.unlocks > 0 ? d.unlocks : null), format: v => `${Math.round(v)}`, lowerIsBetter: true },
  { key: 'focus', label: 'Focus', value: d => d.focusMinutes, format: v => fmt.minutes(v), target: 60 },
  { key: 'mood', label: 'Mood', value: d => d.moodScore ?? null, format: v => v.toFixed(1) },
];

export function TrendsScreen() {
  const c = useTheme();
  const [range, setRange] = useState<'7' | '30'>('7');
  const [metricKey, setMetricKey] = useState('sleep');
  const to = fmt.today();
  const from = fmt.addDays(to, -(Number(range) - 1));
  const daily = useAsync(() => api.daily(from, to), [from, to]);
  const week = useAsync(() => api.week(), []);
  const metric = METRICS.find(m => m.key === metricKey)!;

  const days = daily.data ?? [];
  const values = days.map(metric.value);
  const tracked = values.filter((v): v is number => v != null);
  const avg = tracked.length ? tracked.reduce((a, b) => a + b, 0) / tracked.length : null;
  const best = tracked.length ? (metric.lowerIsBetter ? Math.min(...tracked) : Math.max(...tracked)) : null;
  const bars = days.map((d, i) => ({
    label: range === '7' ? fmt.shortDay(d.date) : String(Number(d.date.slice(8))),
    value: values[i],
    highlight:
      metric.target != null && values[i] != null && (metric.lowerIsBetter ? values[i]! > metric.target : values[i]! < metric.target),
  }));

  const reload = () => {
    daily.reload();
    week.reload();
  };

  return (
    <Screen title="Trends" subtitle="Orange bars miss the target line" refreshing={daily.loading && !!daily.data} onRefresh={reload}>
      <Segmented
        options={[
          { value: '7', label: '7 days' },
          { value: '30', label: '30 days' },
        ]}
        value={range}
        onChange={setRange}
      />
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginTop: space.md }} contentContainerStyle={{ gap: space.sm }}>
        {METRICS.map(m => {
          const active = m.key === metricKey;
          return (
            <Pressable
              key={m.key}
              accessibilityRole="button"
              accessibilityState={{ selected: active }}
              onPress={() => setMetricKey(m.key)}
              style={[styles.chip, { borderColor: active ? c.primary : c.border, backgroundColor: active ? c.primarySoft : c.surface }]}>
              <Text style={{ color: active ? c.primary : c.text, fontWeight: active ? '600' : '400', fontSize: font.small }}>{m.label}</Text>
            </Pressable>
          );
        })}
      </ScrollView>

      {daily.error ? <ErrorBanner message={daily.error} onRetry={daily.reload} /> : null}

      <Card style={{ marginTop: space.md }}>
        <View style={styles.stats}>
          <Stat label="Average" value={avg == null ? '—' : metric.format(avg)} />
          <Stat label={metric.lowerIsBetter ? 'Lowest' : 'Best'} value={best == null ? '—' : metric.format(best)} />
          <Stat label="Days tracked" value={`${tracked.length}/${days.length || range}`} />
        </View>
        <BarChart bars={bars} target={metric.target} format={metric.format} labelEvery={range === '7' ? 1 : 5} />
      </Card>

      <SectionTitle>This week</SectionTitle>
      {week.error ? <ErrorBanner message={week.error} onRetry={week.reload} /> : null}
      {week.data ? (
        <>
          <Card>
            <Text style={[styles.cardTitle, { color: c.text }]}>Top apps · avg per day</Text>
            {week.data.topApps.length === 0 ? <Muted>No app usage recorded yet.</Muted> : null}
            {week.data.topApps.map((a, i) => {
              const max = week.data!.topApps[0].dailyAvgMinutes || 1;
              return (
                <View key={a.packageName} style={styles.appRow}>
                  <Text style={[styles.appRank, { color: c.muted }]}>{i + 1}</Text>
                  <View style={{ flex: 1 }}>
                    <View style={styles.appHeader}>
                      <Text numberOfLines={1} style={{ color: c.text, fontSize: font.body, flex: 1 }}>{a.label}</Text>
                      <Text style={{ color: c.text, fontSize: font.small, fontWeight: '600' }}>{fmt.minutes(a.dailyAvgMinutes)}</Text>
                    </View>
                    <View style={[styles.track, { backgroundColor: c.surfaceAlt }]}>
                      <View style={[styles.fill, { width: `${(a.dailyAvgMinutes / max) * 100}%`, backgroundColor: i === 0 ? c.accent : c.primary }]} />
                    </View>
                  </View>
                </View>
              );
            })}
          </Card>
          <Card>
            <Row label="Average sleep" value={fmt.hours(week.data.sleep.avgHours)} />
            <Row label="Usual bedtime → wake" value={`${week.data.sleep.avgSleepTime ?? '—'} → ${week.data.sleep.avgWakeTime ?? '—'}`} />
            <Row label="Nights under 6 h" value={`${week.data.sleep.nightsUnder6h} of ${week.data.sleep.nightsTracked}`} />
            <Row label="Peak screen hour" value={week.data.screen.peakHour != null ? `${week.data.screen.peakHour}:00` : '—'} />
            <Row label="Unlocks per day" value={week.data.screen.avgUnlocks != null ? `${Math.round(week.data.screen.avgUnlocks)}` : '—'} />
            <Row
              label="Focus sessions"
              value={`${week.data.focus.count}${week.data.focus.completionRate != null ? ` · ${Math.round(week.data.focus.completionRate * 100)}% completed` : ''}`}
            />
            <Row label="Days under 5,000 steps" value={`${week.data.steps.daysUnder5000} of ${week.data.steps.daysTracked}`} />
            <Row label="Days with full data" value={`${week.data.completeDays} of 7`} last />
          </Card>
        </>
      ) : null}
    </Screen>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  const c = useTheme();
  return (
    <View style={{ flex: 1 }}>
      <Text style={{ color: c.muted, fontSize: font.caption }}>{label}</Text>
      <Text style={{ color: c.text, fontSize: font.title, fontWeight: '700', marginTop: 2 }}>{value}</Text>
    </View>
  );
}

function Row({ label, value, last }: { label: string; value: string; last?: boolean }) {
  const c = useTheme();
  return (
    <View style={[styles.row, !last && { borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: c.border }]}>
      <Text style={{ color: c.muted, fontSize: font.small, flex: 1 }}>{label}</Text>
      <Text style={{ color: c.text, fontSize: font.small, fontWeight: '600' }}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  chip: { borderWidth: 1, borderRadius: radius.pill, paddingHorizontal: space.md, paddingVertical: 7 },
  stats: { flexDirection: 'row', marginBottom: space.md },
  cardTitle: { fontSize: font.body, fontWeight: '600', marginBottom: space.sm },
  appRow: { flexDirection: 'row', alignItems: 'center', gap: space.sm, paddingVertical: 6 },
  appRank: { width: 16, fontSize: font.small },
  appHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: 4 },
  track: { height: 6, borderRadius: 3, overflow: 'hidden' },
  fill: { height: 6, borderRadius: 3 },
  row: { flexDirection: 'row', alignItems: 'center', paddingVertical: 10 },
});
