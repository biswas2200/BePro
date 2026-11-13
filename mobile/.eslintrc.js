module.exports = {
  root: true,
  extends: '@react-native',
  rules: {
    // Colours come from the light/dark theme at render time, so small inline style objects are expected.
    'react-native/no-inline-styles': 'off',
  },
};
