import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { space, useTheme } from '../theme';
import { InsightIcon, SettingsIcon, TimelineIcon, TodayIcon, TrendsIcon } from './Icons';

export type Tab = 'today' | 'timeline' | 'trends' | 'insights' | 'settings';

const TABS: { key: Tab; label: string; Icon: typeof TodayIcon }[] = [
  { key: 'today', label: 'Today', Icon: TodayIcon },
  { key: 'timeline', label: 'Timeline', Icon: TimelineIcon },
  { key: 'trends', label: 'Trends', Icon: TrendsIcon },
  { key: 'insights', label: 'Insights', Icon: InsightIcon },
  { key: 'settings', label: 'Settings', Icon: SettingsIcon },
];

/** A plain tab bar instead of a navigation library: five screens don't need a router. */
export function TabBar({ current, onChange }: { current: Tab; onChange: (t: Tab) => void }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <View style={[styles.bar, { backgroundColor: c.surface, borderTopColor: c.border, paddingBottom: Math.max(insets.bottom, 6) }]}>
      {TABS.map(({ key, label, Icon }) => {
        const active = key === current;
        const color = active ? c.primary : c.muted;
        return (
          <Pressable
            key={key}
            accessibilityRole="tab"
            accessibilityState={{ selected: active }}
            accessibilityLabel={label}
            onPress={() => onChange(key)}
            style={styles.tab}>
            <Icon color={color} size={22} />
            <Text style={[styles.label, { color, fontWeight: active ? '600' : '400' }]}>{label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  bar: { flexDirection: 'row', borderTopWidth: StyleSheet.hairlineWidth, paddingTop: 6 },
  tab: { flex: 1, alignItems: 'center', gap: 2, paddingVertical: space.xs, minHeight: 48 },
  label: { fontSize: 11 },
});
