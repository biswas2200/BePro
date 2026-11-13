import React, { useState } from 'react';
import { type LayoutChangeEvent, StyleSheet, Text, View } from 'react-native';
import Svg, { Line, Rect, Text as SvgText } from 'react-native-svg';
import { font, useTheme } from '../theme';

export type Bar = { label: string; value: number | null; highlight?: boolean };

/**
 * Lightweight SVG bar chart: no animation, no gestures, cheap to render on a Snapdragon 625.
 * Missing values (null) render as a faint stub so gaps in tracking are visible.
 */
export function BarChart({
  bars,
  height = 160,
  target,
  format,
  color,
  labelEvery = 1,
}: {
  bars: Bar[];
  height?: number;
  target?: number;
  format: (v: number) => string;
  color?: string;
  labelEvery?: number;
}) {
  const c = useTheme();
  const [width, setWidth] = useState(0);
  const barColor = color ?? c.primary;
  const values = bars.map(b => b.value ?? 0);
  const max = Math.max(...values, target ?? 0, 1);
  const chartTop = 18;
  const chartBottom = height - 20;
  const plotHeight = chartBottom - chartTop;
  const slot = bars.length > 0 ? width / bars.length : 0;
  const barWidth = Math.max(3, Math.min(28, slot * 0.62));
  const y = (v: number) => chartBottom - (v / max) * plotHeight;
  const peak = values.indexOf(Math.max(...values));

  return (
    <View onLayout={(e: LayoutChangeEvent) => setWidth(e.nativeEvent.layout.width)}>
      {width > 0 ? (
        <Svg width={width} height={height}>
          <Line x1={0} x2={width} y1={chartBottom} y2={chartBottom} stroke={c.border} strokeWidth={1} />
          {target != null ? (
            <>
              <Line x1={0} x2={width} y1={y(target)} y2={y(target)} stroke={c.muted} strokeWidth={1} strokeDasharray="4 4" />
              <SvgText x={width - 2} y={y(target) - 4} fill={c.muted} fontSize={10} textAnchor="end">
                {format(target)}
              </SvgText>
            </>
          ) : null}
          {bars.map((b, i) => {
            const x = i * slot + (slot - barWidth) / 2;
            const v = b.value;
            const top = v == null ? chartBottom - 2 : y(v);
            return (
              <React.Fragment key={i}>
                <Rect
                  x={x}
                  y={top}
                  width={barWidth}
                  height={Math.max(2, chartBottom - top)}
                  rx={Math.min(4, barWidth / 2)}
                  fill={v == null ? c.border : b.highlight ? c.accent : barColor}
                />
                {i === peak && v != null && v > 0 ? (
                  <SvgText x={x + barWidth / 2} y={top - 5} fill={c.text} fontSize={10} textAnchor="middle" fontWeight="600">
                    {format(v)}
                  </SvgText>
                ) : null}
                {i % labelEvery === 0 ? (
                  <SvgText x={x + barWidth / 2} y={height - 5} fill={c.muted} fontSize={10} textAnchor="middle">
                    {b.label}
                  </SvgText>
                ) : null}
              </React.Fragment>
            );
          })}
        </Svg>
      ) : (
        <View style={{ height }} />
      )}
      {bars.every(b => b.value == null || b.value === 0) ? (
        <View style={[StyleSheet.absoluteFill, { alignItems: 'center', justifyContent: 'center' }]} pointerEvents="none">
          <Text style={{ color: c.muted, fontSize: font.small }}>No data yet</Text>
        </View>
      ) : null}
    </View>
  );
}
