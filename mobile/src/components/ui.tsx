import React from 'react';
import {
  ActivityIndicator,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
  type StyleProp,
  type TextStyle,
  type ViewStyle,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { font, radius, space, useTheme } from '../theme';

export function Screen({
  title,
  subtitle,
  right,
  children,
  refreshing,
  onRefresh,
  fullScreen,
  leading,
}: {
  title: string;
  /** Shown left of the title, e.g. the logo. */
  leading?: React.ReactNode;
  subtitle?: string;
  right?: React.ReactNode;
  children: React.ReactNode;
  refreshing?: boolean;
  onRefresh?: () => void;
  /** No tab bar below: pad for the system navigation bar so the last item stays tappable. */
  fullScreen?: boolean;
}) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <ScrollView
      style={{ flex: 1, backgroundColor: c.bg }}
      contentContainerStyle={{ paddingTop: insets.top + space.lg, paddingHorizontal: space.lg, paddingBottom: space.xxl + (fullScreen ? insets.bottom : 0) }}
      refreshControl={
        onRefresh ? (
          <RefreshControl refreshing={!!refreshing} onRefresh={onRefresh} colors={[c.primary]} progressBackgroundColor={c.surface} />
        ) : undefined
      }>
      <View style={styles.header}>
        {leading ? <View style={{ marginRight: space.md }}>{leading}</View> : null}
        <View style={{ flex: 1 }}>
          <Text style={[styles.title, { color: c.text }]}>{title}</Text>
          {subtitle ? <Text style={[styles.subtitle, { color: c.muted }]}>{subtitle}</Text> : null}
        </View>
        {right}
      </View>
      {children}
    </ScrollView>
  );
}

export function Card({ children, style }: { children: React.ReactNode; style?: StyleProp<ViewStyle> }) {
  const c = useTheme();
  return <View style={[styles.card, { backgroundColor: c.surface, borderColor: c.border }, style]}>{children}</View>;
}

export function SectionTitle({ children, right }: { children: React.ReactNode; right?: React.ReactNode }) {
  const c = useTheme();
  return (
    <View style={styles.sectionRow}>
      <Text style={[styles.section, { color: c.muted }]}>{children}</Text>
      {right}
    </View>
  );
}

type ButtonKind = 'primary' | 'secondary' | 'ghost' | 'danger';

export function Button({
  label,
  onPress,
  kind = 'primary',
  disabled,
  busy,
  small,
  style,
}: {
  label: string;
  onPress: () => void;
  kind?: ButtonKind;
  disabled?: boolean;
  busy?: boolean;
  small?: boolean;
  style?: StyleProp<ViewStyle>;
}) {
  const c = useTheme();
  const palette: Record<ButtonKind, { bg: string; fg: string; border: string }> = {
    primary: { bg: c.primary, fg: c.onPrimary, border: c.primary },
    secondary: { bg: c.primarySoft, fg: c.primary, border: c.primarySoft },
    ghost: { bg: 'transparent', fg: c.primary, border: c.border },
    danger: { bg: 'transparent', fg: c.bad, border: c.bad },
  };
  const p = palette[kind];
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: disabled || busy }}
      onPress={onPress}
      disabled={disabled || busy}
      android_ripple={{ color: c.border }}
      style={({ pressed }) => [
        styles.button,
        small && styles.buttonSmall,
        { backgroundColor: p.bg, borderColor: p.border, opacity: disabled ? 0.5 : pressed ? 0.85 : 1 },
        style,
      ]}>
      {busy ? (
        <ActivityIndicator color={p.fg} size="small" />
      ) : (
        <Text style={[styles.buttonText, small && { fontSize: font.small }, { color: p.fg }]}>{label}</Text>
      )}
    </Pressable>
  );
}

export function Pill({ label, tone = 'neutral' }: { label: string; tone?: 'neutral' | 'good' | 'bad' | 'accent' | 'primary' }) {
  const c = useTheme();
  const tones = {
    neutral: { bg: c.surfaceAlt, fg: c.muted },
    good: { bg: c.goodSoft, fg: c.good },
    bad: { bg: c.badSoft, fg: c.bad },
    accent: { bg: c.accentSoft, fg: c.accent },
    primary: { bg: c.primarySoft, fg: c.primary },
  };
  const t = tones[tone];
  return (
    <View style={[styles.pill, { backgroundColor: t.bg }]}>
      <Text style={[styles.pillText, { color: t.fg }]}>{label}</Text>
    </View>
  );
}

export function Muted({ children, style }: { children: React.ReactNode; style?: StyleProp<TextStyle> }) {
  const c = useTheme();
  return <Text style={[{ color: c.muted, fontSize: font.small, lineHeight: 19 }, style]}>{children}</Text>;
}

export function Body({ children, style }: { children: React.ReactNode; style?: StyleProp<TextStyle> }) {
  const c = useTheme();
  return <Text style={[{ color: c.text, fontSize: font.body, lineHeight: 21 }, style]}>{children}</Text>;
}

export function ErrorBanner({ message, onRetry }: { message: string; onRetry?: () => void }) {
  const c = useTheme();
  return (
    <View style={[styles.banner, { backgroundColor: c.badSoft }]}>
      <Text style={{ color: c.bad, flex: 1, fontSize: font.small, lineHeight: 19 }}>{message}</Text>
      {onRetry ? <Button label="Retry" kind="ghost" small onPress={onRetry} /> : null}
    </View>
  );
}

export function Segmented<T extends string>({
  options,
  value,
  onChange,
}: {
  options: { value: T; label: string }[];
  value: T;
  onChange: (v: T) => void;
}) {
  const c = useTheme();
  return (
    <View style={[styles.segmented, { backgroundColor: c.surfaceAlt }]}>
      {options.map(o => {
        const active = o.value === value;
        return (
          <Pressable
            key={o.value}
            accessibilityRole="button"
            accessibilityState={{ selected: active }}
            onPress={() => onChange(o.value)}
            style={[styles.segment, active && { backgroundColor: c.surface, borderColor: c.border }]}>
            <Text style={{ color: active ? c.text : c.muted, fontWeight: active ? '600' : '400', fontSize: font.small }}>
              {o.label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', marginBottom: space.lg },
  title: { fontSize: font.heading, fontWeight: '700' },
  subtitle: { fontSize: font.small, marginTop: 2 },
  card: { borderRadius: radius.lg, borderWidth: StyleSheet.hairlineWidth, padding: space.lg, marginBottom: space.md },
  sectionRow: { flexDirection: 'row', alignItems: 'center', marginTop: space.lg, marginBottom: space.sm },
  section: { flex: 1, fontSize: font.caption, fontWeight: '700', letterSpacing: 0.8, textTransform: 'uppercase' },
  button: {
    minHeight: 44,
    paddingHorizontal: space.lg,
    borderRadius: radius.md,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonSmall: { minHeight: 34, paddingHorizontal: space.md, borderRadius: radius.sm },
  buttonText: { fontSize: font.body, fontWeight: '600' },
  pill: { paddingHorizontal: space.sm, paddingVertical: 3, borderRadius: radius.pill, alignSelf: 'flex-start' },
  pillText: { fontSize: font.caption, fontWeight: '600' },
  banner: { flexDirection: 'row', alignItems: 'center', gap: space.sm, padding: space.md, borderRadius: radius.md, marginBottom: space.md },
  segmented: { flexDirection: 'row', borderRadius: radius.md, padding: 3 },
  segment: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 7,
    borderRadius: radius.sm,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'transparent',
  },
});
