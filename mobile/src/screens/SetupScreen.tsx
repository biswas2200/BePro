import React, { useCallback, useEffect, useState } from 'react';
import { AppState, PermissionsAndroid, Platform, StyleSheet, Text, TextInput, View } from 'react-native';
import { resetApiCache } from '../api/client';
import { CheckIcon, DotIcon } from '../components/Icons';
import { Body, Button, Card, ErrorBanner, Muted, Pill, Screen, SectionTitle } from '../components/ui';
import Bepro, { type DeviceConfig, type PermissionStatus, type SyncResult } from '../native/NativeBepro';
import { font, radius, space, useTheme } from '../theme';
import { Logo } from '../components/Logo';

/**
 * Pairing + the permission checklist. The checklist is the important part on a Redmi: without
 * Usage access, Autostart and "No restrictions" battery mode, MIUI quietly stops data collection.
 */
export function SetupScreen({ config, onDone }: { config: DeviceConfig; onDone: (config: DeviceConfig) => void }) {
  const c = useTheme();
  const [current, setCurrent] = useState(config);
  const [serverUrl, setServerUrl] = useState(config.serverUrl ?? 'http://localhost:8080');
  const [secret, setSecret] = useState('');
  const [pairing, setPairing] = useState(false);
  const [pairError, setPairError] = useState<string>();
  const [status, setStatus] = useState<PermissionStatus>();
  const [sync, setSync] = useState<SyncResult>();
  const [syncing, setSyncing] = useState(false);
  const [miuiOpened, setMiuiOpened] = useState({ autostart: false, battery: false });

  const refresh = useCallback(async () => {
    setStatus(await Bepro.getPermissionStatus());
  }, []);

  useEffect(() => {
    refresh();
    // Most steps send the user to a system screen; re-check when they come back.
    const sub = AppState.addEventListener('change', s => {
      if (s === 'active') {
        refresh();
      }
    });
    return () => sub.remove();
  }, [refresh]);

  const pair = async () => {
    setPairing(true);
    setPairError(undefined);
    try {
      const next = await Bepro.pair(serverUrl.trim(), secret);
      resetApiCache();
      setCurrent(next);
      setSecret('');
    } catch (e) {
      setPairError(e instanceof Error ? e.message : String(e));
    } finally {
      setPairing(false);
    }
  };

  const requestRuntime = async (permission: (typeof PermissionsAndroid.PERMISSIONS)[keyof typeof PermissionsAndroid.PERMISSIONS]) => {
    await PermissionsAndroid.request(permission);
    await refresh();
    Bepro.refreshBackgroundWork();
  };

  const testSync = async () => {
    setSyncing(true);
    try {
      setSync(await Bepro.syncNow());
    } catch (e) {
      setSync({ collected: 0, uploaded: 0, pendingEvents: 0, error: e instanceof Error ? e.message : String(e) });
    } finally {
      setSyncing(false);
    }
  };

  const android = Number(Platform.Version);
  const s = status;

  return (
    <Screen fullScreen leading={<Logo size={48} />} title="Set up Be PRO" subtitle="About 3 minutes. Do these once, in order.">
      <SectionTitle>1 · Connect to your server</SectionTitle>
      <Card>
        {current.paired ? (
          <View style={styles.rowCenter}>
            <CheckIcon color={c.good} />
            <View style={{ flex: 1 }}>
              <Body>Paired with {current.serverUrl}</Body>
              <Muted>Signed in as “{current.userId}”. Device {current.deviceId.slice(0, 8)}</Muted>
            </View>
          </View>
        ) : (
          <>
            <Muted>
              The address of your Be PRO backend. For a laptop over USB run{' '}
              <Text style={styles.code}>adb reverse tcp:8080 tcp:8080</Text> and keep http://localhost:8080. For Cloud Run,
              paste its https:// URL.
            </Muted>
            <TextInput
              value={serverUrl}
              onChangeText={setServerUrl}
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="url"
              placeholder="https://bepro-xxxx.a.run.app"
              placeholderTextColor={c.muted}
              style={[styles.input, { color: c.text, borderColor: c.border, backgroundColor: c.surfaceAlt }]}
            />
            <TextInput
              value={secret}
              onChangeText={setSecret}
              autoCapitalize="none"
              autoCorrect={false}
              secureTextEntry
              placeholder="Pairing secret (BEPRO_DEVICE_SECRET)"
              placeholderTextColor={c.muted}
              style={[styles.input, { color: c.text, borderColor: c.border, backgroundColor: c.surfaceAlt }]}
            />
            {pairError ? <ErrorBanner message={pairError} /> : null}
            <Button label="Pair this phone" onPress={pair} busy={pairing} disabled={!serverUrl || !secret} />
          </>
        )}
      </Card>

      <SectionTitle>2 · Permissions</SectionTitle>
      <Card>
        <Step
          done={!!s?.usageAccess}
          title="Usage access"
          required
          detail="Screen time, app usage, unlocks, and sleep estimates all come from this. Find Be PRO in the list and turn it on."
          action={<Button label="Open" small onPress={() => Bepro.openUsageAccessSettings()} />}
        />
        <Step
          done={!!s?.activityRecognition}
          title="Physical activity"
          detail={
            android >= 29
              ? 'Walking/running detection and step counting.'
              : s?.activityRecognition
                ? 'Granted automatically on Android 9.'
                : 'Needs Google Play services, which this phone doesn’t seem to have.'
          }
          action={
            android >= 29 && !s?.activityRecognition ? (
              <Button label="Allow" small onPress={() => requestRuntime(PermissionsAndroid.PERMISSIONS.ACTIVITY_RECOGNITION)} />
            ) : null
          }
        />
        <Step
          done={!!s?.stepSensor}
          title="Step counter"
          detail={s?.stepSensor ? 'Hardware step counter found.' : 'No step-counter sensor on this phone. Steps will come from Health Connect only.'}
          info
        />
        <Step
          done={!!s?.calendar}
          title="Calendar (optional)"
          detail='Events titled “Focus”, “Deep work” or “Study” count as focus sessions. Only the keyword is uploaded.'
          action={!s?.calendar ? <Button label="Allow" small kind="secondary" onPress={() => requestRuntime(PermissionsAndroid.PERMISSIONS.READ_CALENDAR)} /> : null}
        />
        {android >= 33 ? (
          <Step
            done={false}
            title="Notifications"
            detail="Weekly digest and alerts."
            action={<Button label="Allow" small kind="secondary" onPress={() => requestRuntime(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS)} />}
          />
        ) : null}
        <Step
          done={!!s?.healthConnectGranted}
          title="Health Connect (optional)"
          detail={
            s?.healthConnect === 'unsupported'
              ? 'Not supported on this Android version.'
              : 'Only useful if a band or another app writes sleep/steps to Health Connect.'
          }
          last
          action={
            s?.healthConnect === 'not_installed' ? (
              <Button label="Install" small kind="secondary" onPress={() => Bepro.openHealthConnectInstall()} />
            ) : s?.healthConnect === 'available' && !s.healthConnectGranted ? (
              <Button label="Allow" small kind="secondary" onPress={async () => { await Bepro.requestHealthConnect(); refresh(); }} />
            ) : null
          }
        />
      </Card>

      <SectionTitle>3 · Keep MIUI from killing it</SectionTitle>
      <Card>
        {s?.isMiui ? (
          <>
            <Step
              done={miuiOpened.autostart}
              manual
              title="Autostart"
              detail="Turn Be PRO on in the Autostart list. Without it, collection stops whenever the app is swiped away."
              action={
                <Button
                  label="Open"
                  small
                  onPress={async () => {
                    await Bepro.openAutostartSettings();
                    setMiuiOpened(m => ({ ...m, autostart: true }));
                  }}
                />
              }
            />
            <Step
              done={miuiOpened.battery}
              manual
              title="Battery saver → No restrictions"
              detail="In the screen that opens, choose “No restrictions”."
              action={
                <Button
                  label="Open"
                  small
                  onPress={async () => {
                    await Bepro.openBatterySettings();
                    setMiuiOpened(m => ({ ...m, battery: true }));
                  }}
                />
              }
            />
          </>
        ) : null}
        <Step
          done={!!s?.ignoringBatteryOptimizations}
          title="Ignore battery optimisation"
          detail="Lets the 15-minute job run on time."
          action={!s?.ignoringBatteryOptimizations ? <Button label="Allow" small onPress={() => Bepro.requestIgnoreBatteryOptimizations()} /> : null}
        />
        <Step
          done={false}
          manual
          last
          title="Lock in recents"
          detail="Open the recent-apps screen, long-press Be PRO and tap the lock. MIUI then won't clear it."
          info
        />
      </Card>

      <SectionTitle>4 · Test it</SectionTitle>
      <Card>
        <Muted>Collects what Android already recorded (up to 7 days back) and uploads it.</Muted>
        {sync ? (
          <View style={[styles.result, { backgroundColor: sync.error ? c.badSoft : c.goodSoft }]}>
            <Text style={{ color: sync.error ? c.bad : c.good, fontSize: font.small, lineHeight: 19 }}>
              {sync.error
                ? sync.error
                : `Collected ${sync.collected} events, uploaded ${sync.uploaded}. ${sync.pendingEvents} waiting.`}
            </Text>
          </View>
        ) : null}
        <Button label="Run a sync now" kind="secondary" onPress={testSync} busy={syncing} disabled={!current.paired} style={{ marginTop: space.md }} />
      </Card>

      <Button
        label={current.paired ? 'Done' : 'Pair first to continue'}
        onPress={() => onDone(current)}
        disabled={!current.paired || !s?.usageAccess}
        style={{ marginTop: space.lg }}
      />
      {current.paired && !s?.usageAccess ? (
        <>
          <Muted style={{ textAlign: 'center', marginTop: space.sm }}>
            Usage access is needed for screen time, app usage and sleep. If you've turned it on and this still shows off, continue anyway.
          </Muted>
          <Button label="Continue anyway" kind="ghost" small onPress={() => onDone(current)} style={{ marginTop: space.sm, alignSelf: 'center' }} />
        </>
      ) : null}
    </Screen>
  );
}

function Step({
  done,
  title,
  detail,
  action,
  required,
  manual,
  info,
  last,
}: {
  done: boolean;
  title: string;
  detail: string;
  action?: React.ReactNode;
  required?: boolean;
  manual?: boolean;
  info?: boolean;
  last?: boolean;
}) {
  const c = useTheme();
  return (
    <View style={[styles.step, !last && { borderBottomColor: c.border, borderBottomWidth: StyleSheet.hairlineWidth }]}>
      <View style={styles.stepIcon}>{done ? <CheckIcon color={c.good} /> : <DotIcon color={required ? c.accent : c.border} size={14} />}</View>
      <View style={{ flex: 1 }}>
        <View style={styles.rowCenter}>
          <Text style={{ color: c.text, fontSize: font.body, fontWeight: '600' }}>{title}</Text>
          {required && !done ? <Pill label="Required" tone="accent" /> : null}
          {manual && !info ? <Pill label={done ? 'Opened' : 'Manual'} /> : null}
        </View>
        <Muted style={{ marginTop: 2 }}>{detail}</Muted>
      </View>
      {action ? <View style={{ marginLeft: space.sm }}>{action}</View> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  rowCenter: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  input: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: space.md,
    paddingVertical: 10,
    fontSize: font.body,
    marginTop: space.md,
    marginBottom: space.xs,
  },
  code: { fontFamily: 'monospace' },
  step: { flexDirection: 'row', alignItems: 'center', paddingVertical: space.md },
  stepIcon: { width: 26, alignItems: 'flex-start' },
  result: { borderRadius: radius.md, padding: space.md, marginTop: space.md },
});
