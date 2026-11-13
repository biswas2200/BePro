/* global jest */

// The Kotlin module doesn't exist under Jest; provide a stub with the same surface.
jest.mock('./src/native/NativeBepro', () => {
  const config = { serverUrl: null, deviceId: 'test-device-id', paired: false, userId: null };
  return {
    __esModule: true,
    default: {
      pair: jest.fn(async serverUrl => ({ ...config, serverUrl, paired: true, userId: 'me' })),
      getConfig: jest.fn(async () => config),
      getAccessToken: jest.fn(async () => 'token'),
      unpair: jest.fn(async () => undefined),
      getPermissionStatus: jest.fn(async () => ({
        usageAccess: false,
        calendar: false,
        activityRecognition: true,
        healthConnect: 'not_installed',
        healthConnectGranted: false,
        stepSensor: true,
        ignoringBatteryOptimizations: false,
        isMiui: true,
        androidVersion: 28,
      })),
      openUsageAccessSettings: jest.fn(),
      openAutostartSettings: jest.fn(async () => true),
      openBatterySettings: jest.fn(async () => true),
      requestIgnoreBatteryOptimizations: jest.fn(),
      requestHealthConnect: jest.fn(async () => false),
      openHealthConnectInstall: jest.fn(),
      refreshBackgroundWork: jest.fn(),
      syncNow: jest.fn(async () => ({ collected: 0, uploaded: 0, pendingEvents: 0, error: null })),
      getSyncState: jest.fn(async () => ({ lastCollectAt: 0, lastSyncAt: 0, pendingEvents: 0, lastError: null })),
      logMood: jest.fn(async () => undefined),
      logSleep: jest.fn(async () => undefined),
      startFocus: jest.fn(async plannedMinutes => ({ startedAt: Date.now(), plannedMinutes })),
      stopFocus: jest.fn(async () => 0),
      cancelFocus: jest.fn(async () => undefined),
      getActiveFocus: jest.fn(async () => null),
      exportCsv: jest.fn(async () => undefined),
    },
  };
});

// SafeAreaProvider renders nothing until it has measured insets; use the library's mock instead.
jest.mock('react-native-safe-area-context', () => require('react-native-safe-area-context/jest/mock').default);
