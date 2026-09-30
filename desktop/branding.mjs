import path from 'node:path';

export const APP_NAME = '大鸟手机助手';
export const PRODUCT_VERSION = '0.1.0-preview.4';

export function configureAppIdentity(app, env = process.env) {
  // Keep both encrypted settings and Chromium Local State in the existing profile.
  const data = env.BBUI_DESKTOP_DATA || path.join(app.getPath('appData'), 'BBUI');
  app.setName(APP_NAME);
  app.setPath('userData', data);
  app.setPath('sessionData', data);
}
