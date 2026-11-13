/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import App from '../App';

test('an unpaired phone opens the setup checklist', async () => {
  let tree: ReactTestRenderer.ReactTestRenderer | undefined;
  await ReactTestRenderer.act(async () => {
    tree = ReactTestRenderer.create(<App />);
  });
  const text = JSON.stringify(tree!.toJSON());
  expect(text).toContain('Set up Be PRO');
  expect(text).toContain('Usage access');
  // MIUI-specific steps show because the stub reports a Xiaomi phone.
  expect(text).toContain('Autostart');
});
