import React, { useEffect, useState } from 'react';
import { ActivityIndicator, BackHandler, StatusBar, StyleSheet, View, useColorScheme } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { Logo } from './src/components/Logo';
import { TabBar, type Tab } from './src/components/TabBar';
import Bepro, { type DeviceConfig } from './src/native/NativeBepro';
import { InsightsScreen } from './src/screens/InsightsScreen';
import { SettingsScreen } from './src/screens/SettingsScreen';
import { SetupScreen } from './src/screens/SetupScreen';
import { TimelineScreen } from './src/screens/TimelineScreen';
import { TodayScreen } from './src/screens/TodayScreen';
import { TrendsScreen } from './src/screens/TrendsScreen';
import { useTheme } from './src/theme';

function Root() {
  const c = useTheme();
  const [config, setConfig] = useState<DeviceConfig>();
  const [showSetup, setShowSetup] = useState(false);
  const [tab, setTab] = useState<Tab>('today');

  useEffect(() => {
    Bepro.getConfig().then(cfg => {
      setConfig(cfg);
      setShowSetup(!cfg.paired);
    });
  }, []);

  // Hardware back: leave the setup checklist, or return to Today, before exiting the app.
  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (showSetup && config?.paired) {
        setShowSetup(false);
        return true;
      }
      if (!showSetup && tab !== 'today') {
        setTab('today');
        return true;
      }
      return false;
    });
    return () => sub.remove();
  }, [showSetup, tab, config]);

  if (!config) {
    return (
      <View style={[styles.center, { backgroundColor: c.bg }]}>
        <Logo size={88} />
        <ActivityIndicator color={c.primary} style={{ marginTop: 24 }} />
      </View>
    );
  }

  if (showSetup) {
    return (
      <SetupScreen
        config={config}
        onDone={cfg => {
          setConfig(cfg);
          setShowSetup(false);
        }}
      />
    );
  }

  // Only the active tab is mounted, which keeps memory low on 3 GB phones.
  return (
    <View style={{ flex: 1, backgroundColor: c.bg }}>
      <View style={{ flex: 1 }}>
        {tab === 'today' && <TodayScreen />}
        {tab === 'timeline' && <TimelineScreen />}
        {tab === 'trends' && <TrendsScreen />}
        {tab === 'insights' && <InsightsScreen />}
        {tab === 'settings' && (
          <SettingsScreen
            config={config}
            onOpenSetup={() => setShowSetup(true)}
            onUnpaired={() => {
              setConfig({ ...config, paired: false, serverUrl: null, userId: null });
              setTab('today');
              setShowSetup(true);
            }}
          />
        )}
      </View>
      <TabBar current={tab} onChange={setTab} />
    </View>
  );
}

export default function App() {
  const dark = useColorScheme() === 'dark';
  return (
    <SafeAreaProvider>
      <StatusBar barStyle={dark ? 'light-content' : 'dark-content'} />
      <Root />
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
