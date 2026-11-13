import React, { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { api } from '../api/client';
import type { HourBucket } from '../api/types';
import { ChevronIcon } from '../components/Icons';
import { Card, ErrorBanner, Muted, Screen } from '../components/ui';
import { useAsync } from '../hooks/useAsync';
import { font, radius, space, useTheme } from '../theme';
import * as fmt from '../utils/format';

/** Hour-by-hour heatmap of one day: how much of each hour the screen was on, and what for. */
export function TimelineScreen() {
  const c = useTheme();
  const [date, setDate] = useState(fmt.today());
  const timeline = useAsync(() => api.timeline(date), [date]);
  const isToday = date === fmt.today();
  const hours = timeline.data?.hours ?? [];
  const nowHour = new Date().getHours();
  const totals = hours.reduce(
    (acc, h) => ({ screen: acc.screen + h.screenMinutes, unlocks: acc.unlocks + h.unlocks, steps: acc.steps + h.steps }),
    { screen: 0, unlocks: 0, steps: 0 },
  );

  return (
    <Screen
      title="Timeline"
      subtitle={`${fmt.minutes(totals.screen)} screen · ${totals.unlocks} unlocks · ${fmt.count(totals.steps)} steps`}
      refreshing={timeline.loading && !!timeline.data}
      onRefresh={timeline.reload}>
      <View style={[styles.dateBar, { backgroundColor: c.surface, borderColor: c.border }]}>
        <Pressable accessibilityLabel="Previous day" onPress={() => setDate(fmt.addDays(date, -1))} style={styles.arrow} hitSlop={8}>
          <ChevronIcon color={c.text} left />
        </Pressable>
        <Text style={{ color: c.text, fontSize: font.body, fontWeight: '600' }}>{isToday ? 'Today' : fmt.dayLabel(date)}</Text>
        <Pressable
          accessibilityLabel="Next day"
          disabled={isToday}
          onPress={() => setDate(fmt.addDays(date, 1))}
          style={[styles.arrow, isToday && { opacity: 0.3 }]}
          hitSlop={8}>
          <ChevronIcon color={c.text} />
        </Pressable>
      </View>

      {timeline.error ? <ErrorBanner message={timeline.error} onRetry={timeline.reload} /> : null}

      <Card style={{ paddingVertical: space.sm }}>
        <View style={styles.legend}>
          <Muted style={{ flex: 1 }}>Bar = minutes of screen-on in that hour</Muted>
          <Muted>steps</Muted>
        </View>
        {hours.map(h => (
          <HourRow key={h.hour} bucket={h} future={isToday && h.hour > nowHour} />
        ))}
        {!timeline.data && timeline.loading ? <Muted style={{ padding: space.lg }}>Loading…</Muted> : null}
      </Card>
    </Screen>
  );
}

function HourRow({ bucket, future }: { bucket: HourBucket; future: boolean }) {
  const c = useTheme();
  const fill = Math.min(1, bucket.screenMinutes / 60);
  // Heavier use reads warmer: purple for light use, orange when the hour was mostly screen.
  const color = fill > 0.6 ? c.accent : c.primary;
  return (
    <View style={[styles.hourRow, future && { opacity: 0.35 }]}>
      <Text style={[styles.hourLabel, { color: c.muted }]}>{String(bucket.hour).padStart(2, '0')}</Text>
      <View style={{ flex: 1 }}>
        <View style={[styles.track, { backgroundColor: c.surfaceAlt }]}>
          {fill > 0 ? <View style={[styles.fill, { width: `${Math.max(fill * 100, 3)}%`, backgroundColor: color }]} /> : null}
        </View>
        {bucket.topApp && bucket.topAppMinutes >= 2 ? (
          <Text numberOfLines={1} style={[styles.app, { color: c.muted }]}>
            {bucket.topApp} {bucket.topAppMinutes}m{bucket.unlocks ? ` · ${bucket.unlocks} unlocks` : ''}
          </Text>
        ) : bucket.unlocks ? (
          <Text style={[styles.app, { color: c.muted }]}>{bucket.unlocks} unlocks</Text>
        ) : null}
      </View>
      <Text style={[styles.steps, { color: bucket.steps ? c.text : c.border }]}>{bucket.steps ? fmt.count(bucket.steps) : '·'}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  dateBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderRadius: radius.lg,
    borderWidth: StyleSheet.hairlineWidth,
    padding: space.xs,
    marginBottom: space.md,
  },
  arrow: { padding: space.sm },
  legend: { flexDirection: 'row', paddingVertical: space.xs, marginBottom: space.xs },
  hourRow: { flexDirection: 'row', alignItems: 'flex-start', gap: space.sm, paddingVertical: 5 },
  hourLabel: { width: 22, fontSize: font.caption, fontVariant: ['tabular-nums'], marginTop: 1 },
  track: { height: 14, borderRadius: 4, overflow: 'hidden' },
  fill: { height: 14, borderRadius: 4 },
  app: { fontSize: 11, marginTop: 2 },
  steps: { width: 48, textAlign: 'right', fontSize: font.caption, fontVariant: ['tabular-nums'], marginTop: 1 },
});
