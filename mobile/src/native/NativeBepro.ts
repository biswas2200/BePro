import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

/**
 * Bridge to the Kotlin side (android/app/src/main/java/app/bepro/bridge/BeproModule.kt).
 * Collection, buffering and upload all happen natively so they keep working when the app is closed.
 */

export type DeviceConfig = {
  serverUrl: string | null;
  deviceId: string;
  paired: boolean;
  userId: string | null;
};

export type PermissionStatus = {
  usageAccess: boolean;
  calendar: boolean;
  activityRecognition: boolean;
  /** 'available' | 'not_installed' | 'unsupported' */
  healthConnect: string;
  healthConnectGranted: boolean;
  stepSensor: boolean;
  ignoringBatteryOptimizations: boolean;
  isMiui: boolean;
  androidVersion: number;
};

export type SyncState = {
  lastCollectAt: number;
  lastSyncAt: number;
  pendingEvents: number;
  lastError: string | null;
};

export type SyncResult = {
  collected: number;
  uploaded: number;
  pendingEvents: number;
  error: string | null;
};

export type ActiveFocus = {
  startedAt: number;
  plannedMinutes: number;
};

export interface Spec extends TurboModule {
  // Pairing
  pair(serverUrl: string, secret: string): Promise<DeviceConfig>;
  getConfig(): Promise<DeviceConfig>;
  /** Returns a valid JWT, re-authenticating with the stored secret if needed (or if forced). */
  getAccessToken(forceRefresh: boolean): Promise<string>;
  unpair(): Promise<void>;

  // Permissions and phone settings
  getPermissionStatus(): Promise<PermissionStatus>;
  openUsageAccessSettings(): void;
  /** Opens MIUI's Autostart list. Resolves false if this isn't MIUI or the screen is missing. */
  openAutostartSettings(): Promise<boolean>;
  /** Opens the per-app battery saver screen (MIUI) or app details as a fallback. */
  openBatterySettings(): Promise<boolean>;
  requestIgnoreBatteryOptimizations(): void;
  requestHealthConnect(): Promise<boolean>;
  openHealthConnectInstall(): void;
  /** Re-reads permissions and (re)schedules background work. Call after any setup change. */
  refreshBackgroundWork(): void;

  // Collection
  syncNow(): Promise<SyncResult>;
  getSyncState(): Promise<SyncState>;
  logMood(score: number, note: string): Promise<void>;
  logSleep(startMs: number, endMs: number): Promise<void>;
  startFocus(plannedMinutes: number): Promise<ActiveFocus>;
  /** Stops the running session and records it. Resolves with the minutes recorded (0 if none). */
  stopFocus(): Promise<number>;
  cancelFocus(): Promise<void>;
  getActiveFocus(): Promise<ActiveFocus | null>;

  /** Downloads the CSV export and opens the share sheet. */
  exportCsv(): Promise<void>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('Bepro');
