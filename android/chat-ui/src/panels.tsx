import { useEffect, useRef, type ReactNode } from 'react';
import { post, taskPresentation, type Snapshot } from './contract';

// Native dialog provides focus trapping, Escape and restoration of focus.
export function Panel({ title, close, children, className = '' }: { title: string; close: () => void; children: ReactNode; className?: string }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => { const dialog = ref.current!; dialog.showModal(); return () => dialog.close(); }, []);
  return <dialog ref={ref} className={`detail-panel ${className}`} aria-label={title} onCancel={close} onClick={e => { if (e.target === ref.current) close(); }}>
    <div className="panel-body"><header><h2>{title}</h2><button aria-label={`关闭${title}`} onClick={close}>×</button></header>{children}</div>
  </dialog>;
}

export function TaskProgress({ snapshot }: { snapshot: Snapshot }) {
  const task = snapshot.task;
  const display = taskPresentation(snapshot);
  if (!task) return null;
  const steps = { pending: '待处理', in_progress: '进行中', completed: '已完成' };
  return <section className="task-progress" data-task-state={display.state}>
    <p className="progress-state">{snapshot.taskUpdatedThisRun === false ? '此前任务' : display.label}</p>
    <h3>{task.goal}</h3>
    {task.summary && <p>{task.summary}</p>}
    {!!task.steps?.length && <ol className="progress-steps">{task.steps.map(step => <li key={step.id} data-state={step.status}>
      <span className="step-mark" aria-hidden="true">{step.status === 'completed' ? '✓' : step.status === 'in_progress' ? '◌' : '○'}</span>
      <span>{step.title}</span><small>{steps[step.status]}</small>
    </li>)}</ol>}
    {task.question && <p className="progress-question">{task.question}</p>}
    {task.completionEvidence && <p>{task.completionEvidence}</p>}
  </section>;
}

export function QueuedSteerButton({ snapshot, item }: { snapshot: Snapshot; item: NonNullable<Snapshot['queue']>[number] }) {
  const control = snapshot.control;
  if (snapshot.desktop?.connected === false || !snapshot.isRunning || !control?.canSteer || control.mode !== 'running' || snapshot.pendingQuestion?.status === 'pending' ||
      snapshot.sessionId !== snapshot.runningSessionId || item.sessionId !== snapshot.runningSessionId || control.sessionId !== item.sessionId) return null;
  return <button className="queued-steer" aria-label={`立即补充 ${item.text}`} onClick={() => post({
    type: 'steerQueued', sessionId: item.sessionId, submissionId: item.id, controlId: control.id, runId: snapshot.runId,
  })}>立即补充</button>;
}

export function QueuePanel({ snapshot, close }: { snapshot: Snapshot; close: () => void }) {
  const queue = snapshot.queue ?? [];
  return <section className="queue-panel" aria-label="任务队列">
    {queue.length > 0 ? <>
      <div className="queue-controls"><span>{queue.length} 项{snapshot.queuePaused ? ' · 已暂停' : ''}</span>
        <button disabled={snapshot.queuePaused && snapshot.desktop?.connected === false} onClick={() => post({ type: snapshot.queuePaused ? 'resumeQueue' : 'pauseQueue' })}>{snapshot.queuePaused ? '继续队列' : '暂停队列'}</button>
      </div>
      <ol>{queue.map(item => <li key={item.id}>
        <button className="queued-task" onClick={() => { post({ type: 'selectSession', sessionId: item.sessionId }); close(); }}>
          <strong>{snapshot.sessions?.find(s => s.id === item.sessionId)?.title ?? '会话'}</strong><span>{item.text}</span>
        </button><QueuedSteerButton snapshot={snapshot} item={item} /><button aria-label={`取消排队 ${item.text}`} onClick={() => post({ type: 'cancelQueued', submissionId: item.id })}>取消</button>
      </li>)}</ol>
    </> : <p>暂无等待任务</p>}
  </section>;
}
