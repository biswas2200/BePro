import React, { useState } from 'react';
import { View } from 'react-native';
import { api } from '../api/client';
import type { DigestResult } from '../api/types';
import { InsightCard } from '../components/InsightCard';
import { Button, Card, ErrorBanner, Muted, Screen, SectionTitle } from '../components/ui';
import { useAsync } from '../hooks/useAsync';
import { space } from '../theme';
import * as fmt from '../utils/format';

export function InsightsScreen() {
  const history = useAsync(() => api.insightHistory(30), []);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<DigestResult>();
  const [error, setError] = useState<string>();

  const runDigest = async (force: boolean) => {
    setRunning(true);
    setError(undefined);
    try {
      setResult(await api.runDigest(force));
      await history.reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRunning(false);
    }
  };

  const items = history.data ?? [];
  const active = items.filter(i => i.status === 'active');
  const past = items.filter(i => i.status !== 'active');

  return (
    <Screen title="Insights" subtitle="One or two changes a week, never ten" refreshing={history.loading && !!history.data} onRefresh={history.reload}>
      <Card>
        <Muted>
          The weekly digest runs every Sunday at 08:00 using the last 7 days. It needs at least 5 days with both sleep and phone-usage data.
        </Muted>
        {result ? (
          <Muted style={{ marginTop: space.sm }}>
            {result.status === 'generated'
              ? `New insights for the week of ${fmt.dayLabel(result.weekStart)}.`
              : `${result.status === 'failed' ? 'Failed' : 'Skipped'}: ${result.reason ?? ''}`}
          </Muted>
        ) : null}
        {error ? <ErrorBanner message={error} /> : null}
        <View style={{ flexDirection: 'row', gap: space.sm, marginTop: space.md }}>
          <Button label="Generate now" kind="secondary" onPress={() => runDigest(false)} busy={running} style={{ flex: 1 }} />
          {result?.status === 'skipped' && result.reason?.includes('already') ? (
            <Button label="Regenerate" kind="ghost" onPress={() => runDigest(true)} disabled={running} />
          ) : null}
        </View>
      </Card>

      {history.error ? <ErrorBanner message={history.error} onRetry={history.reload} /> : null}

      {active.length ? <SectionTitle>Active</SectionTitle> : null}
      {active.map(i => (
        <InsightCard key={i.id} insight={i} onChanged={() => history.reload()} />
      ))}

      {past.length ? <SectionTitle>History</SectionTitle> : null}
      {past.map(i => (
        <View key={i.id} style={{ opacity: i.status === 'dismissed' ? 0.6 : 1 }}>
          <InsightCard insight={i} />
        </View>
      ))}

      {!history.loading && items.length === 0 && !history.error ? (
        <Muted style={{ textAlign: 'center', marginTop: space.xl }}>No insights yet. They appear after the first full week of data.</Muted>
      ) : null}
    </Screen>
  );
}
