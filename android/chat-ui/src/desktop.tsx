import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Chat, ErrorBoundary } from './main';
import { initialSnapshot, post, type Snapshot } from './contract';
import './desktop.css';
import { semanticKey } from './input';
import { BrandMark } from './brand';

declare global { interface Window { Desktop: { invoke(operation: string, params?: unknown): Promise<any> } } }
type Frame = { frameId: string; screen: string; width: number; height: number; image: string; deviceId?: string; receivedAt?: number };
type DesktopSnapshot = Snapshot;
const call = (operation: string, params?: unknown) => window.Desktop.invoke(operation, params);
// No frame bytes or history are retained: an optional diagnostic listener owns its samples.
const previewMetric = (detail: object) => window.dispatchEvent(new CustomEvent('bbui-preview-metric', { detail }));
const isIos = (snapshot: Snapshot) => (snapshot.desktop?.devicePlatform ?? snapshot.capabilities?.devicePlatform) === 'ios';
const appleDevicesHelp = <a href="https://support.apple.com/guide/devices-windows/welcome/windows" target="_blank" rel="noopener noreferrer">Apple 设备安装指南</a>;
const reconnecting = (snapshot: Snapshot) => snapshot.desktop?.connectionStatus?.code === 'reconnecting';
const connectionInProgress = (snapshot: Snapshot) => ['connecting', 'preparing_support', 'reconnecting'].includes(snapshot.desktop?.connectionStatus?.code ?? '');
const connectionHelp = (snapshot: Snapshot) => snapshot.desktop?.connected
  ? `已连接${snapshot.desktop.deviceName ? ` · ${snapshot.desktop.deviceName}` : ''}`
  : reconnecting(snapshot) ? snapshot.desktop?.connectionStatus?.message || '正在重连'
  : connectionInProgress(snapshot) ? '正在连接'
  : ['disconnected', 'idle', 'not_connected', undefined].includes(snapshot.desktop?.connectionStatus?.code) ? '未连接'
  : snapshot.desktop?.connectionStatus?.message || '未连接';

function App() {
  const [snapshot, setSnapshot] = useState<DesktopSnapshot>(initialSnapshot);
  const [settings, setSettings] = useState<string | null>(null), [collapsed, setCollapsed] = useState(false);
  const [entered, setEntered] = useState(false);
  const ready = snapshot.desktop?.configured === true && snapshot.desktop?.connected === true;
  useEffect(() => {
    const message = (event: Event) => { const value = (event as CustomEvent).detail; if (value?.type === 'snapshot') {
      setSnapshot(value);
      if (!value.desktop?.configured || (!value.desktop?.connected && !reconnecting(value))) setEntered(false);
    } };
    const open = () => setSettings('device'), preview = () => setCollapsed(false);
    window.addEventListener('bbui-message', message); window.addEventListener('bbui-settings', open); window.addEventListener('bbui-preview', preview);
    post({ type: 'ready' });
    document.documentElement.style.setProperty('--desktop-left', localStorage.getItem('desktop-left') || '245px');
    document.documentElement.style.setProperty('--desktop-right', localStorage.getItem('desktop-right') || '340px');
    return () => { window.removeEventListener('bbui-message', message); window.removeEventListener('bbui-settings', open); window.removeEventListener('bbui-preview', preview); };
  }, []);
  return <>{entered && (ready || reconnecting(snapshot))
    ? <Chat previewControl={{ expanded: !collapsed, onToggle: () => setCollapsed(!collapsed) }} aside={<><Resize side="left" /><Phone snapshot={snapshot} collapsed={collapsed} /></>} />
    : <Welcome snapshot={snapshot} settings={setSettings} start={() => { if (ready) setEntered(true); }} />}
    {settings && <Settings snapshot={snapshot} initialTab={settings} close={() => setSettings(null)} />}</>;
}
function Welcome({ snapshot, settings, start }: { snapshot: DesktopSnapshot; settings(tab: string): void; start(): void }) {
  const connected = snapshot.desktop?.connected === true, configured = snapshot.desktop?.configured === true;
  return <main className="desktop-welcome" aria-label="首次使用设置"><section className="welcome-setup">
    <BrandMark className="setup-mark" />
    <h1>开始使用 BBUI</h1>
    <div className="setup-card"><div><h2>手机控制</h2><p role="status">{connectionHelp(snapshot)}</p>
      {!connected && isIos(snapshot) && snapshot.desktop?.connectionStatus?.code === 'driver_missing' && <p>{appleDevicesHelp}</p>}</div>
      <button onClick={() => settings('device')}>连接手机</button>
    </div>
    <div className="setup-card"><div><h2>模型</h2><p>{configured ? '默认模型已配置' : '添加供应商并选择模型'}</p></div>
      <button onClick={() => settings('models')}>{configured ? '管理模型' : '添加模型'}</button>
    </div>
    {(snapshot.isRunning || ['manual', 'taking_over', 'resuming'].includes(snapshot.control?.mode ?? ''))
      && <button className="setup-stop" onClick={() => post({ type: 'stop' })}>停止任务</button>}
    <footer><p>手机操作在本地执行。任务文字和必要截图会发送至所选模型 API。</p>
      <button className="setup-start" disabled={!connected || !configured} onClick={start}>开始使用</button></footer>
  </section></main>;
}
function Resize({ side }: { side: 'left' | 'right' }) {
  return <div className={`column-resize resize-${side}`} role="separator" aria-label="调整栏宽" onPointerDown={event => {
    event.currentTarget.setPointerCapture(event.pointerId);
  }} onPointerMove={event => {
    if (!event.currentTarget.hasPointerCapture(event.pointerId)) return;
    const size = Math.min(side === 'left' ? 380 : 700, Math.max(200, side === 'left' ? event.clientX : innerWidth - event.clientX));
    document.documentElement.style.setProperty(`--desktop-${side}`, size + 'px'); localStorage.setItem(`desktop-${side}`, size + 'px');
  }} />;
}
function Phone({ snapshot, collapsed }: { snapshot: DesktopSnapshot; collapsed: boolean }) {
  const [selectedScreen, setScreen] = useState('main'), [latestFrame, setFrame] = useState<Frame | null>(null), [error, setError] = useState('');
  const [previewError, setPreviewError] = useState('');
  const [busy, setBusy] = useState(false);
  const [windowHidden, setWindowHidden] = useState(document.hidden);
  const framePending = useRef(false);
  const deviceId = snapshot.desktop?.deviceId ?? snapshot.desktop?.devicePlatform ?? 'android';
  const screens = isIos(snapshot) ? [{ 屏幕会话: 'main', 显示屏编号: 0 }] : snapshot.desktop?.screens?.length ? snapshot.desktop.screens : [{ 屏幕会话: 'main', 显示屏编号: 0 }];
  const screen = screens.some(item => item.屏幕会话 === selectedScreen) ? selectedScreen : screens[0].屏幕会话;
  const readOnly = screens.find(item => item.屏幕会话 === screen)?.readOnly === true;
  const frame = latestFrame?.screen === screen && latestFrame.deviceId === deviceId ? latestFrame : null;
  useLayoutEffect(() => {
    if (frame) previewMetric({ kind: 'commit', at: performance.now(), receivedAt: frame.receivedAt, frameId: frame.frameId });
  }, [frame]);
  useEffect(() => { if (selectedScreen !== screen) setScreen(screen); }, [screen, selectedScreen]);
  const image = useRef<HTMLImageElement>(null), down = useRef<{ x: number; y: number; time: number; frame: Frame; controlId?: string } | null>(null);
  const mode = snapshot.control?.mode ?? 'idle', manual = mode === 'manual' && !readOnly && !windowHidden && snapshot.desktop?.connected === true && !previewError;
  const supports = (operation: string, params: { 键名?: string } = {}) =>
    (!snapshot.capabilities?.phoneOperations || snapshot.capabilities.phoneOperations.includes(operation)) &&
    (operation !== '按键' || !snapshot.capabilities?.phoneKeys || snapshot.capabilities.phoneKeys.includes(params.键名!));
  const navigation = ['返回', '主页', '最近任务'].filter(key => (!isIos(snapshot) || key !== '返回') && supports('按键', { 键名: key }));
  const keyboard = useRef<HTMLTextAreaElement>(null), inputBusy = useRef(false), composing = useRef(false);
  const textTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const textTarget = useRef<{ screen: string; controlId?: string } | null>(null);
  const live = useRef({ screen, frame, manual, collapsed, deviceId, controlId: snapshot.control?.id });
  live.current = { screen, frame, manual, collapsed, deviceId, controlId: snapshot.control?.id };
  useEffect(() => {
    down.current = null;
    clearTimeout(textTimer.current);
    if (keyboard.current?.value || composing.current) setError('控制目标已变化，未发送文字已取消。');
    if (keyboard.current) keyboard.current.value = '';
    composing.current = false; textTarget.current = null;
  }, [screen, deviceId, frame?.width, frame?.height, snapshot.control?.id, manual, collapsed]);
  useEffect(() => {
    const visibility = () => setWindowHidden(document.hidden);
    document.addEventListener('visibilitychange', visibility);
    return () => document.removeEventListener('visibilitychange', visibility);
  }, []);
  useEffect(() => () => clearTimeout(textTimer.current), []);
  const transitioning = mode === 'taking_over' || mode === 'resuming';
  const canResume = snapshot.control?.canResume === true;
  const controlLabel = mode === 'taking_over' ? '正在交接' : mode === 'resuming' ? '正在恢复'
    : manual ? (canResume ? '交给 AI 继续' : '结束操作')
    : (mode === 'stopped' || mode === 'error') && canResume ? '继续任务' : '我来操作';
  useEffect(() => {
    let canceled = false, afterFrameId: string | undefined, timer: ReturnType<typeof setTimeout>;
    const fps = Math.max(1, Math.min(60, snapshot.capabilities?.previewFps ?? (isIos(snapshot) ? 15 : 30)));
    const refresh = async () => {
      if (canceled || document.hidden) return;
      if (framePending.current) { clearTimeout(timer); timer = setTimeout(refresh, 1000 / fps); return; }
      framePending.current = true;
      const started = performance.now();
      let failed = false;
      let receivedFrameId: string | undefined;
      try {
        const value: Frame | null = await call('frame', { screen, afterFrameId });
        receivedFrameId = value?.frameId;
        if (!canceled) {
          if (value) { afterFrameId = value.frameId; setFrame({ ...value, deviceId, receivedAt: performance.now() }); }
          setPreviewError('');
        }
      } catch (e) { failed = true; if (!canceled) setPreviewError(String(e)); }
      finally {
        framePending.current = false;
        const at = performance.now();
        previewMetric({ kind: 'request', at, started, durationMs: at - started, frameId: receivedFrameId, failed, canceled });
      }
      if (!canceled && !document.hidden) {
        clearTimeout(timer);
        timer = setTimeout(refresh, failed ? 500 : Math.max(0, 1000 / fps - (performance.now() - started)));
      }
    };
    const visibility = () => { clearTimeout(timer); if (!document.hidden) void refresh(); };
    if (!collapsed && snapshot.desktop?.connected) {
      document.addEventListener('visibilitychange', visibility);
      void refresh();
    }
    return () => { canceled = true; clearTimeout(timer); document.removeEventListener('visibilitychange', visibility); setFrame(null); };
  }, [screen, deviceId, collapsed, snapshot.desktop?.connected, snapshot.capabilities?.previewFps]);
  async function input(operation: string, params = {}, source = frame, controlId = snapshot.control?.id) {
    const current = live.current;
    if (!source || inputBusy.current || !current.manual || current.collapsed || document.hidden || source.deviceId !== current.deviceId || source.screen !== current.screen || controlId !== current.controlId || source.width !== current.frame?.width || source.height !== current.frame?.height || !supports(operation, params)) return false;
    inputBusy.current = true; setBusy(true); setError('');
    try { const result = await call('input', { operation, params, screen: source.screen, frameId: source.frameId, controlId, actionId: crypto.randomUUID() }); if (result.错误) throw new Error(result.错误); return true; }
    catch (e) {
      const pending = textTarget.current;
      const canceled = pending?.screen === source.screen && pending.controlId === controlId && Boolean(keyboard.current?.value);
      if (canceled) { clearTimeout(textTimer.current); keyboard.current!.value = ''; textTarget.current = null; }
      setError(`${String(e)}${canceled ? ' 后续文字已取消，请重新输入。' : ''}`); return false;
    } finally {
      inputBusy.current = false; setBusy(false);
      if (keyboard.current?.value && !composing.current) scheduleText();
    }
  }
  function scheduleText() {
    const current = live.current;
    if (!current.manual || current.collapsed || !keyboard.current?.value || composing.current) return;
    textTarget.current ??= { screen: current.screen, controlId: current.controlId };
    clearTimeout(textTimer.current);
    textTimer.current = setTimeout(() => void flushText(), 100);
  }
  async function flushText() {
    clearTimeout(textTimer.current);
    if (inputBusy.current || composing.current) return false;
    const target = textTarget.current, current = live.current, value = keyboard.current?.value;
    if (!value) return true;
    if (!target || !current.frame || !current.manual || current.collapsed || target.screen !== current.screen || target.controlId !== current.controlId) {
      if (keyboard.current) keyboard.current.value = '';
      textTarget.current = null; setError('控制目标已变化，未发送文字已取消。'); return false;
    }
    keyboard.current!.value = ''; textTarget.current = null;
    const sent = await input('输入内容', { 内容: value }, current.frame, target.controlId);
    if (!sent && live.current.screen === target.screen && live.current.controlId === target.controlId) {
      clearTimeout(textTimer.current);
      if (keyboard.current?.value) setError(previous => `${previous} 后续文字已取消，请重新输入。`);
      if (keyboard.current) keyboard.current.value = '';
      textTarget.current = null;
    }
    return sent;
  }
  async function keyboardAction(operation: string, params: object) {
    if (inputBusy.current) { setError('正在发送输入，请稍后再按此键。'); return; }
    const target = live.current;
    if (await flushText() && target.screen === live.current.screen && target.controlId === live.current.controlId) await input(operation, params, live.current.frame, target.controlId);
  }
  async function pointerInput(operation: string, params: object, source: Frame, controlId?: string) {
    if (await flushText()) await input(operation, params, source, controlId);
  }
  function point(event: React.PointerEvent) {
    if (!image.current || !frame) return null;
    const box = image.current.getBoundingClientRect();
    const scale = Math.min(box.width / frame.width, box.height / frame.height);
    const x = (event.clientX - box.left - (box.width - scale * frame.width) / 2) / scale;
    const y = (event.clientY - box.top - (box.height - scale * frame.height) / 2) / scale;
    return x >= 0 && y >= 0 && x < frame.width && y < frame.height ? { x: Math.min(frame.width - 1, Math.round(x)), y: Math.min(frame.height - 1, Math.round(y)) } : null;
  }
  return <aside className="phone-pane" hidden={collapsed}>
    <Resize side="right" />
    <header><span>手机画面</span><span className="phone-state">{readOnly ? '只读 · 由其他端管理' : manual ? '操作中' : snapshot.isRunning ? 'AI 正在执行' : mode === 'stopped' ? '已停止' : '实时预览'}</span></header>
    {screens.length > 1 && <div className="phone-tabs" role="tablist" aria-label="手机屏幕">{screens.map((item, index) => {
      const name = item.屏幕会话, label = item.external ? (item.label || `外部屏幕 ${item.显示屏编号}`) : name === 'main' ? '主屏' : `任务屏 ${screens.slice(0, index + 1).filter(s => s.屏幕会话 !== 'main' && !s.external).length}`;
      return <button key={name} role="tab" aria-label={name === 'main' ? label : `${label}（${name}）`} aria-selected={screen === name} tabIndex={screen === name ? 0 : -1}
        onClick={() => setScreen(name)} onKeyDown={event => {
          const next = event.key === 'ArrowRight' ? (index + 1) % screens.length : event.key === 'ArrowLeft' ? (index - 1 + screens.length) % screens.length : event.key === 'Home' ? 0 : event.key === 'End' ? screens.length - 1 : -1;
          if (next < 0) return;
          event.preventDefault(); setScreen(screens[next].屏幕会话);
          (event.currentTarget.parentElement?.children[next] as HTMLButtonElement).focus();
        }}>{label}</button>;
    })}</div>}
    {!snapshot.desktop?.connected ? <div className="phone-empty"><span>{connectionHelp(snapshot)}</span></div>
      : <div className="phone-video" tabIndex={0} aria-label={manual ? '人工控制手机' : '只读手机画面'} onKeyDown={event => {
        if (!manual || composing.current || event.nativeEvent.isComposing) return;
        const action = semanticKey(event.key, snapshot.capabilities?.primaryModifier === 'Meta' ? event.metaKey : event.ctrlKey);
        if (action) { event.preventDefault(); event.stopPropagation(); if (supports(action.operation, action.params) && !(isIos(snapshot) && action.params.键名 === '返回')) void keyboardAction(action.operation, action.params); }
        else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() !== 'v') event.preventDefault();
      }} onPointerDown={event => { const p = point(event); if (manual && p && frame && !inputBusy.current) { event.preventDefault(); keyboard.current?.focus({ preventScroll: true }); event.currentTarget.setPointerCapture(event.pointerId); down.current = { ...p, time: Date.now(), frame, controlId: snapshot.control?.id }; } }}
      onPointerCancel={() => { down.current = null; }} onLostPointerCapture={() => { down.current = null; }}
      onPointerUp={event => { const start = down.current; down.current = null; const end = point(event); if (!start || !end) return;
        if (start.frame.screen !== frame?.screen || start.frame.width !== frame.width || start.frame.height !== frame.height) return;
        const ms = Math.min(2000, Date.now() - start.time);
        if (Math.hypot(end.x - start.x, end.y - start.y) > 15) void pointerInput('滑动', { 起点: [start.x, start.y], 终点: [end.x, end.y], 时间: Math.max(100, ms) }, start.frame, start.controlId);
        else void pointerInput(ms > 500 ? '长按' : '点击', { 位置: [end.x, end.y], ...(ms > 500 ? { 时间: ms } : {}) }, start.frame, start.controlId);
      }}>{frame && <img ref={image} src={frame.image} draggable={false} alt={`${screen} 实时画面`} />}
        <textarea ref={keyboard} className="phone-keyboard" aria-label="手机键盘输入" tabIndex={-1} disabled={!manual} autoComplete="off" autoCorrect="off" spellCheck={false}
          onCompositionStart={() => { composing.current = true; textTarget.current = { screen, controlId: snapshot.control?.id }; clearTimeout(textTimer.current); }}
          onCompositionEnd={() => { composing.current = false; scheduleText(); }} onInput={scheduleText} onBlur={scheduleText}
          onPaste={event => { event.preventDefault(); if (!manual) return; if (composing.current) { setError('请先完成输入法选字，再粘贴文字。'); return; }
            event.currentTarget.value += event.clipboardData.getData('text/plain'); scheduleText(); }} />
      </div>}
    {!readOnly && <div className="phone-controls"><button className="phone-primary" disabled={!snapshot.desktop?.connected || !snapshot.control || transitioning} onClick={() => post({
      type: manual ? (canResume ? 'resumeTask' : 'endManual') : (mode === 'stopped' || mode === 'error') && canResume ? 'resumeTask' : 'takeOver', controlId: snapshot.control!.id,
    })}>{controlLabel}</button></div>}
    {manual && <div className="phone-navigation">{navigation.map(key => <button disabled={busy} key={key} title={key} aria-label={key} onClick={() => void keyboardAction('按键', { 键名: key })}>
      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden="true">
        {key === '返回' ? <path d="M17 4 5 12l12 8Z" /> : key === '主页' ? <circle cx="12" cy="12" r="8" /> : <rect x="5" y="5" width="14" height="14" rx="1.5" />}
      </svg>
    </button>)}</div>}
    {previewError && snapshot.desktop?.connected && <p className="desktop-error" role="status" title={previewError}>画面已断开 · 正在重试</p>}
    {error && <p className="desktop-error" role="alert">{error}</p>}
  </aside>;
}

type DeviceChoice = { serial: string; label: string; devicePlatform?: 'android' | 'ios'; deviceId?: string };
function DeviceConnection({ snapshot, saved, connected }: { snapshot: Snapshot; saved: any; connected(change: object): void }) {
  const actualTarget = snapshot.desktop?.connected === true || connectionInProgress(snapshot);
  const [platform, setPlatform] = useState<'android' | 'ios'>((actualTarget ? snapshot.desktop?.devicePlatform : saved.devicePlatform) ?? 'android');
  const [serial, setSerial] = useState(saved.serial ?? ''), [devices, setDevices] = useState<DeviceChoice[]>([]);
  const [refreshing, setRefreshing] = useState(false), [working, setWorking] = useState<'' | 'connect' | 'disconnect'>(''), [error, setError] = useState('');
  const [hasRefreshed, setHasRefreshed] = useState(false), [driverMissing, setDriverMissing] = useState(false);
  const [open, setOpen] = useState(false), [highlight, setHighlight] = useState(0);
  const refreshGeneration = useRef(0), listId = useId();
  const active = snapshot.desktop?.connected === true;
  const queued = snapshot.isRunning || !!snapshot.runningSessionId || !!snapshot.queue?.length || snapshot.sessions?.some(session => session.running || session.queued > 0);
  const connecting = working === 'connect' || connectionInProgress(snapshot);
  const locked = Boolean(working || connecting);
  const targetLocked = queued || active || locked;
  const selected = devices.find(device => device.serial === serial);
  useEffect(() => () => { refreshGeneration.current++; }, []);
  useEffect(() => { if (actualTarget && snapshot.desktop?.devicePlatform) setPlatform(snapshot.desktop.devicePlatform); }, [actualTarget, snapshot.desktop?.devicePlatform]);
  useEffect(() => {
    if (!queued || actualTarget || platform === (saved.devicePlatform ?? 'android')) return;
    refreshGeneration.current++; setPlatform(saved.devicePlatform ?? 'android'); setSerial(saved.serial ?? '');
    setDevices([]); setOpen(false); setRefreshing(false); setHasRefreshed(false);
  }, [queued, actualTarget, platform, saved.devicePlatform, saved.serial]);
  function select(index: number) {
    if (targetLocked || !devices[index]) return;
    setSerial(devices[index].serial); setHighlight(index); setOpen(false);
  }
  async function refresh() {
    const generation = ++refreshGeneration.current;
    setRefreshing(true); setError(''); setDriverMissing(false); setOpen(false);
    try {
      const result = await call('devices', { devicePlatform: platform });
      if (generation !== refreshGeneration.current) return;
      const choices: DeviceChoice[] = (Array.isArray(result) ? result : result.devices).filter((device: DeviceChoice) => (device.devicePlatform ?? 'android') === platform);
      setDevices(choices); setHasRefreshed(true);
      const next = choices.find(device => device.serial === (queued ? saved.serial : serial)) ?? (!queued && choices.length === 1 ? choices[0] : undefined);
      setSerial(next?.serial ?? ''); setHighlight(Math.max(0, choices.findIndex(device => device.serial === next?.serial)));
      setError(result.errors?.map((item: { message: string }) => item.message).join('\n') ?? '');
      setDriverMissing(result.errors?.some((item: { code?: string }) => item.code === 'driver_missing') ?? false);
    } catch (reason) { if (generation === refreshGeneration.current) { setDevices([]); setSerial(''); setHasRefreshed(true); setError(String(reason)); } }
    finally { if (generation === refreshGeneration.current) setRefreshing(false); }
  }
  return <div className="device-connection">
    <div className="device-platform" role="group" aria-label="手机类型">{(['android', 'ios'] as const).map(value => <button key={value} disabled={targetLocked} aria-pressed={platform === value} onClick={() => {
      if (platform === value) return;
      refreshGeneration.current++; setPlatform(value); setSerial(''); setDevices([]); setOpen(false); setRefreshing(false); setHasRefreshed(false); setError(''); setDriverMissing(false);
    }}>{value === 'ios' ? 'iPhone' : 'Android'}</button>)}</div>
    <div className="device-select-row">
      <button disabled={active || locked || refreshing} onClick={() => void refresh()}>{refreshing ? '正在刷新…' : '刷新设备'}</button>
      <div className="device-select" onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false); }}>
        <button type="button" role="combobox" aria-label="设备" aria-controls={listId} aria-expanded={open}
          aria-activedescendant={open && devices[highlight] ? `${listId}-${highlight}` : undefined} disabled={targetLocked || refreshing || !devices.length}
          onClick={() => setOpen(!open)} onKeyDown={event => {
            if (['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' ', 'Escape'].includes(event.key)) event.preventDefault();
            if (event.key === 'Escape') setOpen(false);
            else if (event.key === 'Enter' || event.key === ' ') { if (open) select(highlight); else setOpen(true); }
            else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
              setOpen(true); setHighlight(index => open ? (index + (event.key === 'ArrowDown' ? 1 : -1) + devices.length) % devices.length : Math.max(0, devices.findIndex(device => device.serial === serial)));
            } else if (event.key === 'Home' || event.key === 'End') { setOpen(true); setHighlight(event.key === 'Home' ? 0 : devices.length - 1); }
          }}><span>{actualTarget ? snapshot.desktop?.deviceName || saved.deviceName || saved.serial : selected?.label || (devices.length ? '选择设备' : hasRefreshed ? '未找到设备' : '请先刷新设备')}</span><span aria-hidden="true">⌄</span></button>
        {open && <div id={listId} role="listbox" aria-label="可用设备">{devices.map((device, index) => <button type="button" role="option" id={`${listId}-${index}`} key={device.serial}
          data-device-id={device.deviceId ?? `${platform}:${device.serial}`} aria-selected={serial === device.serial} data-highlighted={highlight === index} tabIndex={-1}
          onMouseDown={event => event.preventDefault()} onMouseEnter={() => setHighlight(index)} onClick={() => select(index)}>{device.label}</button>)}</div>}
      </div>
    </div>
    <div className="device-connect-row">
      <button className="primary" disabled={locked || (active && queued) || (!active && (!selected || refreshing || (queued && (serial !== saved.serial || platform !== (saved.devicePlatform ?? 'android')))))} onClick={async () => {
        setWorking(active ? 'disconnect' : 'connect'); setError(''); setDriverMissing(false);
        try {
          if (active) await call('disconnect');
          else if (selected) { await call('connect', { devicePlatform: platform, serial: selected.serial, label: selected.label }); connected({ devicePlatform: platform, serial: selected.serial, deviceName: selected.label }); }
        } catch (reason) {
          setError(String(reason));
          if (!active) {
            try {
              const current = await call('settings');
              connected({ devicePlatform: current.devicePlatform ?? 'android', serial: current.serial ?? '', deviceName: current.deviceName ?? '' });
            } catch { /* Keep the connection failure visible if settings cannot be read. */ }
          }
        }
        finally { setWorking(''); }
      }}>{working === 'disconnect' ? '正在断开…' : connecting ? '正在连接…' : active ? '断开' : '连接'}</button>
      <span role={error ? 'alert' : 'status'} className={error ? 'desktop-error' : undefined}>{error || (working === 'disconnect' ? '正在断开' : connectionInProgress(snapshot) ? connectionHelp(snapshot) : connecting ? '正在连接' : connectionHelp(snapshot))}</span>
    </div>
    {platform === 'ios' && (driverMissing || snapshot.desktop?.connectionStatus?.code === 'driver_missing') && <p>{appleDevicesHelp}</p>}
  </div>;
}

function Settings({ close, snapshot, initialTab = 'device' }: { close(): void; snapshot: Snapshot; initialTab?: string }) {
  const [value, setValue] = useState<any>(null), [tab, setTab] = useState(initialTab), [message, setMessage] = useState('');
  const [memory, setMemory] = useState<any>(null);
  useEffect(() => { void call('settings').then(setValue).catch(e => setMessage(String(e))); }, []);
  async function act(operation: string, params?: unknown) { try { const result = await call(operation, params); setMessage('已完成'); return result; } catch (e) { setMessage(String(e)); return null; } }
  const update = (change: any) => setValue((previous: any) => ({ ...previous, ...change }));
  if (!value) return <div className="settings-backdrop"><section className="settings"><button onClick={close}>关闭</button><p>{message || '正在载入设置…'}</p></section></div>;
  return <div className="settings-backdrop"><section className="settings" role="dialog" aria-label="设置"><header><h2>设置</h2><button onClick={close}>完成</button></header>
    <nav>{[['device', '设备'], ['models', '模型'], ['search', '搜索'], ['memory', '记忆'], ['data', '数据与诊断']].map(([key, name]) => <button aria-pressed={tab === key} key={key} onClick={() => setTab(key)}>{name}</button>)}</nav>
    <div className="settings-body">
    {tab === 'device' && <DeviceConnection snapshot={snapshot} saved={value} connected={update} />}
    {tab === 'models' && <>{value.connections.map((c: any, index: number) => {
      const change = (patch: any) => update({ connections: value.connections.map((item: any, i: number) => i === index ? { ...item, ...patch } : item) });
      return <fieldset key={c.id}><legend>{c.name || '模型连接'}</legend><label>名称<input value={c.name} onChange={e => change({ name: e.target.value })} /></label>
        <label>供应商<select value={c.provider} onChange={e => change({ provider: e.target.value })}>{['bbui', 'openai', 'anthropic', 'deepseek'].map(p => <option key={p}>{p}</option>)}</select></label>
        <label>API 协议<select value={c.api} onChange={e => change({ api: e.target.value })}>{['openai-completions', 'openai-responses', 'anthropic-messages'].map(p => <option key={p}>{p}</option>)}</select></label>
        <label>API 地址<input value={c.baseUrl} onChange={e => change({ baseUrl: e.target.value })} /></label><label>API 密钥<input type="password" autoComplete="off" value={c.apiKey} placeholder={c.hasKey ? '已保存，留空沿用' : '填写密钥'} onChange={e => change({ apiKey: e.target.value })} /></label>
        {c.models.map((m: any, n: number) => <div className="model-line" key={n}><input aria-label="模型 ID" value={m.id} onChange={e => change({ models: c.models.map((x: any, i: number) => i === n ? { ...x, id: e.target.value } : x) })} />
          <label className="check"><input type="checkbox" checked={m.input?.includes('image')} onChange={e => change({ models: c.models.map((x: any, i: number) => i === n ? { ...x, input: e.target.checked ? ['text', 'image'] : ['text'] } : x) })} />视觉</label>
          <button onClick={() => update({ defaultModel: { connectionId: c.id, modelId: m.id, thinkingLevel: '' } })}>{value.defaultModel?.connectionId === c.id && value.defaultModel?.modelId === m.id ? '默认' : '设为默认'}</button>
          <button onClick={() => void act('probe', { connectionId: c.id, modelId: m.id }).then(r => r && setMessage(JSON.stringify(r)))}>测试</button></div>)}
        <button onClick={() => change({ models: [...c.models, { id: '', input: ['text', 'image'] }] })}>添加模型</button>
        <button onClick={async () => { const models = await act('discover', { connectionId: c.id }); if (models) change({ models: models.map((m: any) => ({ ...m, input: ['text'] })) }); }}>发现模型</button>
        <button onClick={() => update({ connections: value.connections.filter((_: any, i: number) => i !== index) })}>删除连接</button></fieldset>;
    })}<button onClick={() => update({ connections: [...value.connections, { id: crypto.randomUUID(), name: '', provider: 'bbui', api: 'openai-completions', baseUrl: 'https://api.openai.com/v1', apiKey: '', models: [{ id: '', input: ['text', 'image'] }] }] })}>添加连接</button><p>先保存连接，再发现模型或测试。测试会向所选 API 发送一个短请求。</p></>}
    {tab === 'search' && <><label>搜索供应商<select value={value.tools.search.provider} onChange={e => update({ tools: { search: { ...value.tools.search, provider: e.target.value } } })}><option value="">未配置</option><option value="bocha">博查</option><option value="baidu">百度 AI 搜索</option></select></label><label>API 密钥<input type="password" value={value.tools.search.apiKey} placeholder={value.tools.search.hasKey ? '已保存，留空沿用' : ''} onChange={e => update({ tools: { search: { ...value.tools.search, apiKey: e.target.value } } })} /></label></>}
    {tab === 'memory' && <><button onClick={async () => setMemory(await act('memory', { action: 'read' }))}>载入最新记忆</button>{memory && <><textarea className="memory-editor" value={memory.content} onChange={e => setMemory({ ...memory, content: e.target.value })} /><button onClick={async () => { const result = await act('memory', { action: 'write', ...memory }); if (result) setMemory(result); }}>保存记忆</button></>}</>}
    {tab === 'data' && <><p>数据独立保存在当前 Windows 用户目录。导入会复制原文件。</p><button onClick={() => void act('importSessions')}>导入 Pi 会话</button><button onClick={async () => { const result = await act('importConfig'); if (result) update(result); }}>导入旧设备配置</button><button onClick={() => void act('diagnostics').then(r => r && setMessage(JSON.stringify(r, null, 2)))}>查看诊断</button></>}
    </div>{tab !== 'device' && <footer>{message && <p role="status">{message}</p>}<button className="primary" onClick={async () => { const result = await act('saveSettings', value); if (result) setValue(result); }}>保存设置</button></footer>}
  </section></div>;
}
createRoot(document.getElementById('root')!).render(<ErrorBoundary><App /></ErrorBoundary>);
