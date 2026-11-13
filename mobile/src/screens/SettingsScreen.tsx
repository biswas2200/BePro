import React, { useCallback, useEffect, useState } from 'react';
import { Alert, AppState, StyleSheet, Text, View } from 'react-native';
import { resetApiCache } from '../api/client';
import { Button, Card, ErrorBanner, Muted, Pill, Screen, SectionTitle } from '../components/ui';
import Bepro, { type DeviceConfig, type PermissionStatus, type SyncState } from '../native/NativeBepro';
import { font, space, useTheme } from '../theme';
import * as fmt from '../utils/format';

export function SettingsScreen({
  config,
  onOpenSetup,
  onUnpaired,
}: {
  config: DeviceConfig;
  onOpenSetup: () => void;
  onUnpaired: () => void;
}) {
  const c = useTheme();
  const [sync, setSync] = useState<SyncState>();
  const [perms, setPerms] = useState<PermissionStatus>();
  const [syncing, setSyncing] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<string>();

  const load = useCallback(async () => {
    const [s, p] = await Promise.all([Bepro.getSyncState(), Bepro.getPermissionStatus()]);
    setSync(s);
    setPerms(p);
  }, []);

  useEffect(() => {
    load();
    const sub = AppState.addEventListener('change', s => s === 'active' && load());
    return () => sub.remove();
  }, [load]);

  const syncNow = async () => {
    setSyncing(true);
    setError(undefined);
    try {
      const r = await Bepro.syncNow();
      if (r.error) {
        setError(r.error);
      }
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSyncing(false);
    }
  };

  const exportCsv = async () => {
    setExporting(true);
    setError(undefined);
    try {
      await Bepro.exportCsv();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setExporting(false);
    }
  };

  const unpair = () =>
    Alert.alert('Unpair this phone?', 'Background collection stops and unsent data on this phone is deleted. Data already on the server is kept.', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Unpair',
        style: 'destructive',
        onPress: async () => {
          await Bepro.unpair();
          resetApiCache();
          onUnpaired();
        },
      },
    ]);

  const healthy = perms?.usageAccess && sync && Date.now() - sync.lastCollectAt < 60 * 60 * 1000;

  return (
    <Screen title="Settings">
      {error ? <ErrorBanner message={error} /> : null}

      <SectionTitle right={<Pill label={healthy ? 'Collecting' : 'Check setup'} tone={healthy ? 'good' : 'accent'} />}>
        Background sync
      </SectionTitle>
      <Card>
        <Row label="Last collected" value={fmt.ago(sync?.lastCollectAt)} />
        <Row label="Last uploaded" value={fmt.ago(sync?.lastSyncAt)} />
        <Row label="Waiting to upload" value={`${sync?.pendingEvents ?? 0} events`} />
        {sync?.lastError ? <Muted style={{ color: c.bad, marginTop: space.sm }}>{sync.lastError}</Muted> : null}
        <Muted style={{ marginTop: space.sm }}>
          Runs about every 15 minutes. If “Last collected” is hours old, MIUI is blocking it: re-check Autostart and Battery saver in the setup checklist.
        </Muted>
        <Button label="Sync now" kind="secondary" onPress={syncNow} busy={syncing} style={{ marginTop: space.md }} />
      </Card>

      <SectionTitle>Setup</SectionTitle>
      <Card>
        <Row label="Usage access" value={perms?.usageAccess ? 'On' : 'Off'} bad={!perms?.usageAccess} />
        <Row label="Activity / steps" value={perms?.activityRecognition ? (perms.stepSensor ? 'On' : 'On (no sensor)') : 'Off'} />
        <Row label="Calendar" value={perms?.calendar ? 'On' : 'Off'} />
        <Row label="Health Connect" value={perms?.healthConnectGranted ? 'On' : perms?.healthConnect === 'available' ? 'Off' : 'Not installed'} />
        <Row label="Battery optimisation" value={perms?.ignoringBatteryOptimizations ? 'Ignored' : 'Active'} bad={!perms?.ignoringBatteryOptimizations} />
        <Button label="Open setup checklist" kind="ghost" onPress={onOpenSetup} style={{ marginTop: space.md }} />
      </Card>

      <SectionTitle>Data</SectionTitle>
      <Card>
        <Muted>Everything is stored on your own server. Export downloads the last 90 days of raw events as CSV.</Muted>
        <Button label="Export CSV" kind="secondary" onPress={exportCsv} busy={exporting} style={{ marginTop: space.md }} />
      </Card>

      <SectionTitle>Device</SectionTitle>
      <Card>
        <Row label="Server" value={config.serverUrl ?? '—'} />
        <Row label="User" value={config.userId ?? '—'} />
        <Row label="Device ID" value={config.deviceId.slice(0, 8)} />
        <Row label="Android" value={`API ${perms?.androidVersion ?? '—'}${perms?.isMiui ? ' · MIUI' : ''}`} />
        <Button label="Unpair this phone" kind="danger" onPress={unpair} style={{ marginTop: space.md }} />
      </Card>
    </Screen>
  );
}

function Row({ label, value, bad }: { label: string; value: string; bad?: boolean }) {
  const c = useTheme();
  return (
    <View style={styles.row}>
      <Text style={{ color: c.muted, fontSize: font.small }}>{label}</Text>
      <Text numberOfLines={1} style={{ color: bad ? c.bad : c.text, fontSize: font.small, fontWeight: '600', flexShrink: 1, marginLeft: space.md }}>
        {value}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 8 },
});
