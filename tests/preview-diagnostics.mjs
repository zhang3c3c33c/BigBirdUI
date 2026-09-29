// Measures renderer requests and committed frame identities separately from image
// loads: a fresh capture with identical pixels does not fire another img.onload.
export async function startPreviewDiagnostics(page) {
  await page.evaluate(() => {
    window.previewDiagnostics?.stop();
    const samples = [], started = performance.now();
    let dropped = 0;
    const record = sample => {
      if (samples.length < 20000) samples.push({ ...sample, phase: window.iosPhase ?? 'preview' });
      else dropped++;
    };
    const metric = event => record(event.detail);
    const load = event => { if (event.target.matches?.('.phone-video img')) record({ kind: 'load', at: performance.now() }); };
    window.addEventListener('bbui-preview-metric', metric);
    document.addEventListener('load', load, true);
    window.previewDiagnostics = { samples, started, get dropped() { return dropped; },
      stop() { window.removeEventListener('bbui-preview-metric', metric); document.removeEventListener('load', load, true); } };
  });
}

export async function readPreviewDiagnostics(page) {
  return page.evaluate(() => {
    const { samples, started, dropped } = window.previewDiagnostics;
    const stats = values => {
      const sorted = [...values].sort((a, b) => a - b);
      return { count: values.length, mean: values.length ? values.reduce((a, b) => a + b, 0) / values.length : 0,
        p50: sorted[Math.floor(sorted.length * .5)] ?? 0, p95: sorted[Math.floor(sorted.length * .95)] ?? 0, max: sorted.at(-1) ?? 0 };
    };
    const phases = {};
    for (const phase of new Set(samples.map(sample => sample.phase))) {
      const group = samples.filter(sample => sample.phase === phase);
      const requests = group.filter(sample => sample.kind === 'request');
      const commits = group.filter(sample => sample.kind === 'commit');
      const loads = group.filter(sample => sample.kind === 'load');
      const rate = values => values.length > 1 ? (values.length - 1) * 1000 / (values.at(-1).at - values[0].at) : 0;
      phases[phase] = { requests: requests.length, nullFrames: requests.filter(sample => !sample.frameId && !sample.failed).length,
        failed: requests.filter(sample => sample.failed).length, committedFrames: commits.length, imageLoads: loads.length,
        requestFps: rate(requests), committedFps: rate(commits), changedImageFps: rate(loads),
        requestRttMs: stats(requests.map(sample => sample.durationMs)),
        responseToCommitMs: stats(commits.filter(sample => sample.receivedAt != null).map(sample => sample.at - sample.receivedAt)),
        commitIntervalMs: stats(commits.slice(1).map((sample, index) => sample.at - commits[index].at)) };
    }
    return { elapsedMs: performance.now() - started, dropped, phases };
  });
}
