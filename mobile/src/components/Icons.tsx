import React from 'react';
import Svg, { Circle, Path, Rect } from 'react-native-svg';

type IconProps = { color: string; size?: number };

// Simple 24px line icons drawn inline so we don't ship an icon font.

export function TodayIcon({ color, size = 24 }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth={1.8} strokeLinecap="round">
      <Circle cx={12} cy={12} r={8.5} />
      <Circle cx={12} cy={12} r={3} />
    </Svg>
  );
}

export function TimelineIcon({ color, size = 24 }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth={1.8} strokeLinecap="round">
      <Path d="M4 6h16M4 12h10M4 18h13" />
    </Svg>
  );
}

export function TrendsIcon({ color, size = 24 }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth={1.8} strokeLinecap="round">
      <Rect x={4} y={12} width={3.5} height={8} rx={1} />
      <Rect x={10.25} y={7} width={3.5} height={13} rx={1} />
      <Rect x={16.5} y={4} width={3.5} height={16} rx={1} />
    </Svg>
  );
}

export function InsightIcon({ color, size = 24 }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
      <Path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-3.6 10.8c.6.5 1 1.2 1 2V16h5.2v-.2c0-.8.4-1.5 1-2A6 6 0 0 0 12 3Z" />
    </Svg>
  );
}

export function SettingsIcon({ color, size = 24 }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth={1.8} strokeLinecap="round">
      <Path d="M4 7h10M18 7h2M4 17h4M12 17h8" />
      <Circle cx={16} cy={7} r={2} />
      <Circle cx={10} cy={17} r={2} />
    </Svg>
  );
}

export function CheckIcon({ color, size = 18 }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round">
      <Path d="M5 12.5l4.5 4.5L19 7.5" />
    </Svg>
  );
}

export function DotIcon({ color, size = 18 }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24">
      <Circle cx={12} cy={12} r={5} fill={color} />
    </Svg>
  );
}

export function ChevronIcon({ color, size = 20, left }: IconProps & { left?: boolean }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <Path d={left ? 'M15 5l-7 7 7 7' : 'M9 5l7 7-7 7'} />
    </Svg>
  );
}
