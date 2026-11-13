import React, { useCallback, useEffect, useState } from 'react';
import { AppState, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { api } from '../api/client';
import type { DailyAggregate } from '../api/types';
import { InsightCard } from '../components/InsightCard';
import { Button, Card, ErrorBanner, Muted, Screen, SectionTitle } from '../components/ui';
import { useAsync } from '../hooks/useAsync';
import Bepro, { type ActiveFocus } from '../native/NativeBepro';
import { font, radius, space, useTheme } from '../theme';
import * as fmt from '../utils/format';
import { Logo } from '../components/Logo';

export function TodayScreen() {
  const summary = useAsync(() => api.today(), []);
  const insights = useAsync(() => api.latestInsights(), []);
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState<string>();

  const refresh = useCallback(async () => {
    setSyncing(true);
    setSyncError(undefined);
    try {
      const result = await Bepro.syncNow();
      if (result.error) {
        setSyncError(result.error);
      }
    } catch (e) {
      setSyncError(e instanceof Error ? e.message : String(e));
    } finally {
      setSyncing(false);
    }
    await Promise.all([summary.reload(), insights.reload()]);
  }, [summary, insights]);

  // Pull fresh data from the phone's collectors once when the screen opens, without blocking the UI.
  const { reload: reloadSummary } = summary;
  const { reload: reloadInsights } = insights;
  useEffect(() => {
    Bepro.syncNow()
      .then(result => (result.uploaded > 0 ? Promise.all([reloadSummary(), reloadInsights()]) : undefined))
      .catch(() => undefined);
  }, [reloadSummary, reloadInsights]);

  const today = summary.data?.today;
  const yesterday = summary.data?.yesterday;
  // Last night's sleep is filed under today (the wake-up date); before it's inferred, show yesterday's.
  const sleepDay = today?.sleepHours != null ? today : yesterday;
  const active = (insights.data ?? []).filter(i => i.status !== 'dismissed');
  const lastSync = summary.data?.lastSyncAt ? Date.parse(summary.data.lastSyncAt) : null;

  return (
    <Screen
      leading={<Logo size={44} />}
      title={fmt.dayLabel(fmt.today())}
      subtitle={`Synced ${fmt.ago(lastSync)} · pull down to sync`}
      refreshing={syncing}
      onRefresh={refresh}>
      {summary.error ? <ErrorBanner message={summary.error} onRetry={summary.reload} /> : null}
      {syncError ? <ErrorBanner message={`Sync: ${syncError}`} /> : null}

      {active.map(i => (
        <InsightCard key={i.id} insight={i} onChanged={() => insights.reload()} />
      ))}

      <SectionTitle>Today</SectionTitle>
      <View style={styles.grid}>
        <Tile
          label="Sleep"
          value={fmt.hours(sleepDay?.sleepHours)}
          detail={
            sleepDay?.sleepHours != null
              ? `${fmt.clock(sleepDay.sleepStart)} → ${fmt.clock(sleepDay.sleepEnd)}${sleepDay.sleepSource === 'inferred' ? ' · estimated' : ''}`
              : 'Not detected yet'
          }
          tone={sleepDay?.sleepHours != null && sleepDay.sleepHours < 6 ? 'bad' : undefined}
        />
        <Tile label="Screen time" value={fmt.minutes(today?.screenMinutes)} detail={`${today?.unlocks ?? 0} unlocks`} />
        <Tile
          label="Top app"
          value={today?.topAppLabel ?? '—'}
          detail={today?.topAppMinutes ? fmt.minutes(today.topAppMinutes) : 'No usage yet'}
          small
        />
        <Tile
          label="Steps"
          value={fmt.count(today?.steps)}
          detail={today?.activeMinutes ? `${today.activeMinutes} active min` : today?.steps == null ? 'No step data' : ' '}
        />
        <Tile
          label="Focus"
          value={fmt.minutes(today?.focusMinutes)}
          detail={`${today?.focusSessions ?? 0} session${today?.focusSessions === 1 ? '' : 's'}`}
        />
        <Tile label="Mood" value={today?.moodScore != null ? `${today.moodScore.toFixed(1)} / 5` : '—'} detail="Log below" />
      </View>

      <FocusTimer onStopped={summary.reload} />
      <MoodLogger onLogged={summary.reload} />
      <SleepCorrection day={sleepDay} onSaved={summary.reload} />
    </Screen>
  );
}

function Tile({
  label,
  value,
  detail,
  tone,
  small,
}: {
  label: string;
  value: string;
  detail: string;
  tone?: 'bad';
  small?: boolean;
}) {
  const c = useTheme();
  return (
    <View style={[styles.tile, { backgroundColor: c.surface, borderColor: c.border }]}>
      <Text style={[styles.tileLabel, { color: c.muted }]}>{label}</Text>
      <Text numberOfLines={1} style={[styles.tileValue, small && { fontSize: font.title }, { color: tone === 'bad' ? c.bad : c.text }]}>
        {value}
      </Text>
      <Text numberOfLines={1} style={[styles.tileDetail, { color: c.muted }]}>
        {detail}
      </Text>
    </View>
  );
}

function FocusTimer({ onStopped }: { onStopped: () => void }) {
  const c = useTheme();
  const [focus, setFocus] = useState<ActiveFocus | null>(null);
  const [planned, setPlanned] = useState(25);
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    Bepro.getActiveFocus().then(setFocus);
    const sub = AppState.addEventListener('change', s => s === 'active' && Bepro.getActiveFocus().then(setFocus));
    return () => sub.remove();
  }, []);

  useEffect(() => {
    if (!focus) {
      return;
    }
    // Tick every 15 s rather than every second: plenty for a minutes display, kinder to the battery.
    const t = setInterval(() => setNow(Date.now()), 15000);
    setNow(Date.now());
    return () => clearInterval(t);
  }, [focus]);

  const elapsed = focus ? Math.floor((now - focus.startedAt) / 60000) : 0;
  const done = focus ? elapsed >= focus.plannedMinutes : false;

  return (
    <Card>
      <Text style={[styles.cardTitle, { color: c.text }]}>Focus session</Text>
      {focus ? (
        <>
          <Text style={[styles.big, { color: done ? c.good : c.primary }]}>
            {elapsed} / {focus.plannedMinutes} min
          </Text>
          <Muted>
            {done ? 'Target reached. Stop when you’re ready.' : `Started ${fmt.clockFromMillis(focus.startedAt)}. Keep the phone face down.`}
          </Muted>
          <View style={styles.row}>
            <Button
              label="Stop & save"
              onPress={async () => {
                await Bepro.stopFocus();
                setFocus(null);
                onStopped();
              }}
              style={{ flex: 1 }}
            />
            <Button
              label="Cancel"
              kind="ghost"
              onPress={async () => {
                await Bepro.cancelFocus();
                setFocus(null);
              }}
            />
          </View>
        </>
      ) : (
        <>
          <View style={styles.chips}>
            {[25, 45, 60, 90].map(m => (
              <Chip key={m} label={`${m} min`} active={planned === m} onPress={() => setPlanned(m)} />
            ))}
          </View>
          <Button label={`Start ${planned}-minute focus`} onPress={async () => setFocus(await Bepro.startFocus(planned))} />
        </>
      )}
    </Card>
  );
}

const MOODS = [
  { score: 1, label: 'Awful' },
  { score: 2, label: 'Low' },
  { score: 3, label: 'Okay' },
  { score: 4, label: 'Good' },
  { score: 5, label: 'Great' },
];

function MoodLogger({ onLogged }: { onLogged: () => void }) {
  const c = useTheme();
  const [score, setScore] = useState<number>();
  const [note, setNote] = useState('');
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);

  const save = async () => {
    if (!score) {
      return;
    }
    setBusy(true);
    try {
      await Bepro.logMood(score, note);
      setSaved(true);
      setNote('');
      setScore(undefined);
      onLogged();
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <Text style={[styles.cardTitle, { color: c.text }]}>How are you feeling?</Text>
      <View style={styles.moods}>
        {MOODS.map(m => {
          const active = score === m.score;
          return (
            <Pressable
              key={m.score}
              accessibilityRole="button"
              accessibilityLabel={`Mood ${m.score}, ${m.label}`}
              accessibilityState={{ selected: active }}
              onPress={() => {
                setScore(m.score);
                setSaved(false);
              }}
              style={[styles.mood, { borderColor: active ? c.primary : c.border, backgroundColor: active ? c.primarySoft : c.surface }]}>
              <Text style={{ color: active ? c.primary : c.text, fontSize: font.title, fontWeight: '700' }}>{m.score}</Text>
              <Text style={{ color: c.muted, fontSize: 11 }}>{m.label}</Text>
            </Pressable>
          );
        })}
      </View>
      <TextInput
        value={note}
        onChangeText={setNote}
        placeholder="Optional note: what affected it?"
        placeholderTextColor={c.muted}
        maxLength={300}
        style={[styles.input, { color: c.text, borderColor: c.border, backgroundColor: c.surfaceAlt }]}
      />
      {saved ? <Muted style={{ color: c.good, marginBottom: space.sm }}>Saved. It will sync in the background.</Muted> : null}
      <Button label="Save mood" kind="secondary" onPress={save} disabled={!score} busy={busy} />
    </Card>
  );
}

/** Lets you correct an estimated night, or enter one when no sleep was detected. */
function SleepCorrection({ day, onSaved }: { day?: DailyAggregate; onSaved: () => void }) {
  const c = useTheme();
  const [open, setOpen] = useState(false);
  const [bed, setBed] = useState(23 * 60);
  const [wake, setWake] = useState(7 * 60);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  const openEditor = () => {
    if (day?.sleepStart && day?.sleepEnd) {
      const s = new Date(day.sleepStart);
      const e = new Date(day.sleepEnd);
      setBed(s.getHours() * 60 + Math.round(s.getMinutes() / 15) * 15);
      setWake(e.getHours() * 60 + Math.round(e.getMinutes() / 15) * 15);
    }
    setOpen(true);
  };

  const save = async () => {
    // Wake-up is this morning; bedtime is last night if it's later in the day than wake-up.
    const wakeDate = new Date();
    wakeDate.setHours(Math.floor(wake / 60) % 24, wake % 60, 0, 0);
    const bedDate = new Date(wakeDate);
    bedDate.setHours(Math.floor(bed / 60) % 24, bed % 60, 0, 0);
    if (bedDate >= wakeDate) {
      bedDate.setDate(bedDate.getDate() - 1);
    }
    setBusy(true);
    setError(undefined);
    try {
      await Bepro.logSleep(bedDate.getTime(), wakeDate.getTime());
      setOpen(false);
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  if (!open) {
    return (
      <Button
        label={day?.sleepSource === 'inferred' ? 'Correct last night’s sleep' : 'Enter last night’s sleep'}
        kind="ghost"
        onPress={openEditor}
      />
    );
  }
  const duration = ((wake - bed + 1440) % 1440) / 60;
  return (
    <Card>
      <Text style={[styles.cardTitle, { color: c.text }]}>Last night</Text>
      <TimeStepper label="Fell asleep" value={bed} onChange={setBed} />
      <TimeStepper label="Woke up" value={wake} onChange={setWake} />
      <Muted style={{ marginVertical: space.sm }}>{duration.toFixed(1)} hours. Manual entries override the estimate.</Muted>
      {error ? <ErrorBanner message={error} /> : null}
      <View style={styles.row}>
        <Button label="Save" onPress={save} busy={busy} style={{ flex: 1 }} />
        <Button label="Cancel" kind="ghost" onPress={() => setOpen(false)} />
      </View>
    </Card>
  );
}

function TimeStepper({ label, value, onChange }: { label: string; value: number; onChange: (v: number) => void }) {
  const c = useTheme();
  const shift = (delta: number) => onChange((value + delta + 1440) % 1440);
  const hh = String(Math.floor(value / 60)).padStart(2, '0');
  const mm = String(value % 60).padStart(2, '0');
  return (
    <View style={[styles.stepper, { borderColor: c.border }]}>
      <Text style={{ color: c.muted, fontSize: font.small, marginBottom: space.xs }}>{label}</Text>
      <View style={styles.stepperRow}>
        <Button label="−1h" small kind="ghost" onPress={() => shift(-60)} />
        <Button label="−15" small kind="ghost" onPress={() => shift(-15)} />
        <Text style={{ color: c.text, fontSize: font.title, fontWeight: '700', minWidth: 60, textAlign: 'center' }}>
          {hh}:{mm}
        </Text>
        <Button label="+15" small kind="ghost" onPress={() => shift(15)} />
        <Button label="+1h" small kind="ghost" onPress={() => shift(60)} />
      </View>
    </View>
  );
}

function Chip({ label, active, onPress }: { label: string; active: boolean; onPress: () => void }) {
  const c = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected: active }}
      onPress={onPress}
      style={[styles.chip, { borderColor: active ? c.primary : c.border, backgroundColor: active ? c.primarySoft : 'transparent' }]}>
      <Text style={{ color: active ? c.primary : c.text, fontSize: font.small, fontWeight: active ? '600' : '400' }}>{label}</Text>
    </Pressable>
  );
}


const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, marginBottom: space.md },
  tile: {
    flexBasis: '48%',
    flexGrow: 1,
    borderRadius: radius.lg,
    borderWidth: StyleSheet.hairlineWidth,
    padding: space.md,
    minHeight: 92,
  },
  tileLabel: { fontSize: font.caption, fontWeight: '600' },
  tileValue: { fontSize: font.heading, fontWeight: '700', marginTop: 4 },
  tileDetail: { fontSize: font.caption, marginTop: 2 },
  cardTitle: { fontSize: font.title, fontWeight: '600', marginBottom: space.sm },
  big: { fontSize: font.display, fontWeight: '700', marginVertical: space.xs },
  row: { flexDirection: 'row', gap: space.sm, marginTop: space.md },
  chips: { flexDirection: 'row', gap: space.sm, marginBottom: space.md, flexWrap: 'wrap' },
  chip: { borderWidth: 1, borderRadius: radius.pill, paddingHorizontal: space.md, paddingVertical: 6 },
  moods: { flexDirection: 'row', gap: space.xs, marginBottom: space.md },
  mood: { flex: 1, alignItems: 'center', borderWidth: 1, borderRadius: radius.md, paddingVertical: space.sm },
  input: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: space.md,
    paddingVertical: 10,
    fontSize: font.body,
    marginBottom: space.md,
  },
  stepper: { paddingVertical: space.sm, borderBottomWidth: StyleSheet.hairlineWidth },
  stepperRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 4 },
});
