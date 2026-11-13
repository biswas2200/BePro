import React, { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { api } from '../api/client';
import type { Insight } from '../api/types';
import { font, radius, space, useTheme } from '../theme';
import { Button, Pill } from './ui';

export function InsightCard({ insight, onChanged }: { insight: Insight; onChanged?: (updated: Insight) => void }) {
  const c = useTheme();
  const [busy, setBusy] = useState<'dismissed' | 'actioned' | null>(null);
  const [error, setError] = useState<string>();
  const isAlert = insight.kind === 'alert';
  const isNotice = insight.kind === 'notice';
  const accent = isAlert ? c.bad : isNotice ? c.muted : c.primary;

  const update = async (status: 'dismissed' | 'actioned') => {
    setBusy(status);
    setError(undefined);
    try {
      onChanged?.(await api.setInsightStatus(insight.id, status));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  };

  return (
    <View style={[styles.card, { backgroundColor: c.surface, borderColor: c.border, borderLeftColor: accent }]}>
      <View style={styles.tags}>
        <Pill
          label={isAlert ? 'Alert' : isNotice ? 'Notice' : 'Weekly insight'}
          tone={isAlert ? 'bad' : isNotice ? 'neutral' : 'primary'}
        />
        <Pill label={insight.category} />
        {insight.status === 'actioned' ? <Pill label="Done" tone="good" /> : null}
      </View>
      <Text style={[styles.pattern, { color: c.text }]}>{insight.pattern}</Text>
      <Text style={[styles.evidence, { color: c.muted }]}>{insight.dataEvidence}</Text>
      <View style={[styles.action, { backgroundColor: c.surfaceAlt }]}>
        <Text style={[styles.actionLabel, { color: c.muted }]}>THIS WEEK</Text>
        <Text style={[styles.actionText, { color: c.text }]}>{insight.action}</Text>
      </View>
      {error ? <Text style={{ color: c.bad, fontSize: font.small, marginTop: space.sm }}>{error}</Text> : null}
      {insight.status === 'active' ? (
        <View style={styles.buttons}>
          <Button label="Done" small onPress={() => update('actioned')} busy={busy === 'actioned'} disabled={!!busy} style={{ flex: 1 }} />
          <Button label="Dismiss" small kind="ghost" onPress={() => update('dismissed')} busy={busy === 'dismissed'} disabled={!!busy} style={{ flex: 1 }} />
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    borderRadius: radius.lg,
    borderWidth: StyleSheet.hairlineWidth,
    borderLeftWidth: 4,
    padding: space.lg,
    marginBottom: space.md,
  },
  tags: { flexDirection: 'row', gap: space.xs, flexWrap: 'wrap', marginBottom: space.sm },
  pattern: { fontSize: font.title, fontWeight: '600', lineHeight: 23 },
  evidence: { fontSize: font.small, lineHeight: 19, marginTop: space.xs },
  action: { borderRadius: radius.md, padding: space.md, marginTop: space.md },
  actionLabel: { fontSize: 11, fontWeight: '700', letterSpacing: 0.8, marginBottom: 2 },
  actionText: { fontSize: font.body, lineHeight: 21 },
  buttons: { flexDirection: 'row', gap: space.sm, marginTop: space.md },
});
