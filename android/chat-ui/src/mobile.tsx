import { createRoot } from 'react-dom/client';
import { Chat, ErrorBoundary } from './main';
createRoot(document.getElementById('root')!).render(<ErrorBoundary><Chat /></ErrorBoundary>);
