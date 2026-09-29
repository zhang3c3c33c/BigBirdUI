import { post } from './contract';
document.getElementById('stop')!.onclick = () => post({ type: 'stop' });
