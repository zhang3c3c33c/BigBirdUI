import { createContext, useContext, useId, useRef, useState } from 'react';
import { ThreadListPrimitive, ThreadListItemPrimitive, useAuiState } from '@assistant-ui/react';
import { post, type Snapshot } from './contract';

type Dialog = { id: string; title: string; mode: 'rename' | 'delete' };
const Context = createContext<{ snapshot: Snapshot; search: string; open: (value: Dialog) => void; close: () => void }>(null!);
function SessionRow() {
  const id = useAuiState(s => s.threadListItem.id);
  const menuId = useId();
  const menu = useRef<HTMLDivElement>(null);
  const { snapshot, search, open, close } = useContext(Context);
  const item = snapshot.sessions?.find(s => s.id === id);
  if (!item || !item.title.toLocaleLowerCase().includes(search.toLocaleLowerCase())) return null;
  return <ThreadListItemPrimitive.Root className={`session-row ${id === snapshot.sessionId ? 'selected' : ''}`}>
    <ThreadListItemPrimitive.Trigger className="session-select" onClick={close}>
      <span className="session-title">{item.pinned && <svg aria-label="已置顶" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.7"><path d="m9 3 6 0-1 6 4 4v2H6v-2l4-4-1-6ZM12 15v7" /></svg>}<span><ThreadListItemPrimitive.Title fallback="新会话" /></span></span>
      <small>{snapshot.pendingQuestion?.sessionId === id && snapshot.pendingQuestion.status === 'pending' ? '等待回答' : item.running ? '执行中' : item.queued ? `排队中 · ${item.queued}` : item.state === 'error' ? '回复出错' : item.state === 'stopped' ? '已停止' : ''}</small>
    </ThreadListItemPrimitive.Trigger>
    <button className="session-more" aria-label={`会话菜单 ${item.title}`} aria-haspopup="menu" popoverTarget={menuId} onClick={e => {
      const rect = e.currentTarget.getBoundingClientRect();
      if (menu.current) {
        menu.current.style.left = `${Math.max(8, Math.min(rect.right - 168, window.innerWidth - 176))}px`;
        menu.current.style.top = `${Math.max(8, Math.min(rect.bottom + 4, window.innerHeight - 156))}px`;
      }
    }}>⋯</button>
    <div ref={menu} id={menuId} popover="auto" role="menu" aria-label={`会话操作 ${item.title}`} className="session-menu">
      <button role="menuitem" onClick={() => { menu.current?.hidePopover(); post({ type: 'pinSession', sessionId: id, pinned: !item.pinned }); }}>{item.pinned ? '取消置顶' : '置顶'}</button>
      <button role="menuitem" onClick={() => { menu.current?.hidePopover(); open({ id, title: item.title, mode: 'rename' }); }}>重命名</button>
      <button role="menuitem" className="danger" onClick={() => { menu.current?.hidePopover(); open({ id, title: item.title, mode: 'delete' }); }}>删除</button>
    </div>
  </ThreadListItemPrimitive.Root>;
}
export function SessionSidebar({ snapshot, expanded, close }: { snapshot: Snapshot; expanded: boolean; close: () => void }) {
  const [search, setSearch] = useState('');
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [name, setName] = useState('');
  const open = (value: Dialog) => { setName(value.title); setDialog(value); };
  const queued = snapshot.queue ?? [];
  return <Context.Provider value={{ snapshot, search, open, close }}>
    {expanded && <button className="sidebar-scrim" aria-label="关闭侧边栏" onClick={close} />}
    <aside className={`session-sidebar ${expanded ? 'expanded' : ''}`} aria-label="会话管理">
      <header><strong>会话</strong><button className="sidebar-close" onClick={close} aria-label="关闭会话栏">×</button></header>
      <ThreadListPrimitive.Root>
        <ThreadListPrimitive.New className="new-session" disabled={!snapshot.sessionsReady} onClick={close}>＋ 新会话</ThreadListPrimitive.New>
        <input className="session-search" aria-label="搜索会话标题" placeholder="搜索会话标题" value={search} onChange={e => setSearch(e.target.value)} />
        <div className="session-list"><ThreadListPrimitive.Items components={{ ThreadListItem: SessionRow }} /></div>
      </ThreadListPrimitive.Root>
      <footer className="sidebar-footer"><button onClick={() => { close(); post({ type: 'openSettings' }); }}>设置</button></footer>
    </aside>
    {dialog && <div className="session-dialog-backdrop"><form role="dialog" aria-modal="true" aria-label={dialog.mode === 'rename' ? '重命名会话' : '删除会话'} className="session-dialog" onSubmit={e => {
      e.preventDefault();
      if (dialog.mode === 'rename') post({ type: 'renameSession', sessionId: dialog.id, title: name.trim() });
      else post({ type: 'deleteSession', sessionId: dialog.id });
      setDialog(null);
    }}>
      <h2>{dialog.mode === 'rename' ? '重命名会话' : '删除会话'}</h2>
      {dialog.mode === 'rename' ? <input autoFocus aria-label="会话标题" maxLength={200} value={name} onChange={e => setName(e.target.value)} />
        : <p>删除“{dialog.title}”及其聊天记录？此操作无法撤销。
          {(snapshot.runningSessionId === dialog.id || (snapshot.control?.sessionId === dialog.id && snapshot.control.mode !== 'idle')) &&
            (snapshot.control?.mode === 'manual' || snapshot.control?.mode === 'taking_over'
              ? ' 人工操作与当前任务将结束，队列将暂停。' : ' 当前任务将停止，队列将暂停。')}
          {queued.some(q => q.sessionId === dialog.id) && ' 此会话的排队任务也将取消。'}</p>}
      <footer><button type="button" onClick={() => setDialog(null)}>取消</button><button disabled={dialog.mode === 'rename' && !name.trim()} type="submit">{dialog.mode === 'rename' ? '保存' : '删除'}</button></footer>
    </form></div>}
  </Context.Provider>;
}
