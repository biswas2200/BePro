import React from 'react';
import Svg, { Circle, Defs, LinearGradient, Path, Rect, Stop } from 'react-native-svg';

// Same geometry as scripts/make_logo.py (108-unit canvas). Ring = your day, orange dot = the one
// signal that matters, centre = you.
const ARC = 'M73.331,45.795 A21,21 0 1,1 55.099,33.029';

export function Logo({ size = 40 }: { size?: number }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 108 108">
      <Defs>
        <LinearGradient id="bepro-bg" x1="0" y1="0" x2="1" y2="1">
          <Stop offset="0" stopColor="#6C62DA" />
          <Stop offset="1" stopColor="#3B3290" />
        </LinearGradient>
      </Defs>
      <Rect width={108} height={108} rx={26} fill="url(#bepro-bg)" />
      <Path d={ARC} fill="none" stroke="#FFFFFF" strokeWidth={7.5} strokeLinecap="round" transform="translate(54 54) scale(1.35) translate(-54 -54)" />
      <Circle cx={66.045} cy={36.798} r={5.6} fill="#F07A45" transform="translate(54 54) scale(1.35) translate(-54 -54)" />
      <Circle cx={54} cy={54} r={6.2} fill="#FFFFFF" transform="translate(54 54) scale(1.35) translate(-54 -54)" />
    </Svg>
  );
}
