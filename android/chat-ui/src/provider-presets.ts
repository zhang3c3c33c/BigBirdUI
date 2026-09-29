import presets from '../../app/src/main/assets/provider-presets.json';

export const providerPresets = presets;
type Connection = { name: string; provider: string; api: string; baseUrl: string; apiKey?: string; hasKey?: boolean; presetId?: string };
const normalized = (url: string) => url.trim().replace(/\/+$/, '');
export function selectedPreset(connection: Connection) {
  if (connection.presetId === '') return '';
  return presets.find(p => normalized(p.baseUrl) === normalized(connection.baseUrl) && p.api === connection.api)?.id || '';
}
export function applyPreset<T extends Connection>(connection: T, id: string): T {
  const preset = presets.find(p => p.id === id);
  if (!preset) return { ...connection, provider: 'bbui', presetId: '' };
  const changedEndpoint = normalized(connection.baseUrl) !== normalized(preset.baseUrl);
  const name = !connection.name.trim() || presets.some(p => p.name === connection.name) ? preset.name : connection.name;
  return { ...connection, name, presetId: id, provider: preset.provider, api: preset.api, baseUrl: preset.baseUrl,
    ...(changedEndpoint ? { apiKey: '', hasKey: false, clearApiKey: true } : {}) };
}
