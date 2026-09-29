import { post } from './contract';
import { APP_NAME, STOP_LABEL } from './branding';
document.title = `${APP_NAME} · 停止`;
document.getElementById('stop-label')!.textContent = STOP_LABEL;
document.getElementById('stop')!.onclick = () => post({ type: 'stop' });
