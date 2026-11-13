import { useColorScheme } from 'react-native';

export type Palette = {
  bg: string;
  surface: string;
  surfaceAlt: string;
  text: string;
  muted: string;
  border: string;
  primary: string;
  primarySoft: string;
  onPrimary: string;
  accent: string;
  accentSoft: string;
  good: string;
  goodSoft: string;
  warn: string;
  bad: string;
  badSoft: string;
};

// Purple and orange come from the project plan's cover; neutrals are warm like its pages.
const light: Palette = {
  bg: '#F6F5F2',
  surface: '#FFFFFF',
  surfaceAlt: '#F0EEE9',
  text: '#1C1B22',
  muted: '#6B6A75',
  border: '#E6E3DD',
  primary: '#534AB7',
  primarySoft: '#EEEDFE',
  onPrimary: '#FFFFFF',
  accent: '#C4501F',
  accentSoft: '#FBEAE2',
  good: '#0F6E56',
  goodSoft: '#E1F3EC',
  warn: '#9A6A12',
  bad: '#B3261E',
  badSoft: '#FBE6E4',
};

const dark: Palette = {
  bg: '#121118',
  surface: '#1C1A24',
  surfaceAlt: '#25232F',
  text: '#F2F1F6',
  muted: '#A09EAD',
  border: '#2E2C39',
  primary: '#9D96F5',
  primarySoft: '#2A2650',
  onPrimary: '#15132B',
  accent: '#F08A5D',
  accentSoft: '#3A2219',
  good: '#4CC38A',
  goodSoft: '#173226',
  warn: '#E0A84A',
  bad: '#F2867A',
  badSoft: '#3A1D1A',
};

export const space = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 };
export const radius = { sm: 8, md: 12, lg: 16, pill: 999 };
export const font = {
  caption: 12,
  small: 13,
  body: 15,
  title: 17,
  heading: 22,
  display: 30,
};

export function useTheme(): Palette {
  return useColorScheme() === 'dark' ? dark : light;
}
