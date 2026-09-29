import { useEffect, useRef, useState } from 'react';
import { post, type ModelOption, type Snapshot } from './contract';
import { Panel } from './panels';

type SelectionRequest = { id: string; sessionId: string; stateId?: string };
export function useModelSelection(snapshot: Snapshot) {
  const [request, setRequest] = useState<SelectionRequest | null>(null);
  const requestRef = useRef<SelectionRequest | null>(null);
  const [error, setError] = useState<{ sessionId: string; text: string } | null>(null);
  useEffect(() => {
    if (!request) return;
    const result = snapshot.modelSelectionResult;
    if ((result?.id === request.id && result.sessionId === request.sessionId) ||
      (snapshot.stateId && request.stateId && snapshot.stateId !== request.stateId)) {
      if (result?.id === request.id && !result.accepted) setError({ sessionId: request.sessionId, text: result.error || '未能保存模型选择' });
      requestRef.current = null;
      setRequest(null);
    }
  }, [snapshot.modelSelectionResult, snapshot.stateId, request]);
  const select = (value: ModelOption | string) => {
    // Ref also closes the gap before React commits disabled buttons.
    if (requestRef.current || !snapshot.sessionsReady || !snapshot.sessionId) return;
    const next = { id: crypto.randomUUID(), sessionId: snapshot.sessionId, stateId: snapshot.stateId };
    requestRef.current = next; setRequest(next); setError(null);
    if (typeof value === 'string') post({ type: 'setThinkingLevel', requestId: next.id, sessionId: next.sessionId, thinkingLevel: value });
    else post({ type: 'selectModel', requestId: next.id, sessionId: next.sessionId, connectionId: value.connectionId, modelId: value.modelId });
  };
  return { pending: !!request, select, error: error?.sessionId === snapshot.sessionId ? error.text : '' };
}

export function ModelControls({ snapshot, selection }: { snapshot: Snapshot; selection: ReturnType<typeof useModelSelection> }) {
  const [panel, setPanel] = useState<'models' | 'thinking' | null>(null);
  useEffect(() => setPanel(null), [snapshot.sessionId]);
  const options = snapshot.modelOptions ?? [];
  const selected = snapshot.modelSelection;
  const model = options.find(item => item.connectionId === selected?.connectionId && item.modelId === selected.modelId);
  const repeatedName = model && options.some(item => item !== model && item.modelName === model.modelName);
  const label = model ? `${model.modelName}${repeatedName ? ` · ${model.connectionName}` : ''}` : selected ? '模型不可用' : '选择模型';
  const thinking = model?.thinkingLevels.find(level => level.id === selected?.thinkingLevel);
  const groups = new Map<string, ModelOption[]>();
  for (const option of options) groups.set(option.connectionId, [...(groups.get(option.connectionId) ?? []), option]);
  const disabled = !snapshot.sessionsReady || selection.pending;
  return <>
    <div className="model-controls" aria-label="回复模型" aria-busy={selection.pending}>
      <button className="model-chip" aria-label={`选择模型：${label}`} disabled={disabled} onClick={() => setPanel('models')}><span>{label}</span><span aria-hidden="true">⌄</span></button>
      {!!model?.thinkingLevels.length && <button className="thinking-chip" disabled={disabled} onClick={() => setPanel('thinking')}>思考：{thinking?.id || 'default'}<span aria-hidden="true">⌄</span></button>}
    </div>
    {selection.error && <div className="model-error" role="alert">{selection.error}</div>}
    {panel && <Panel title={panel === 'models' ? '选择模型' : '思考档位'} close={() => setPanel(null)} className="model-panel">
      {panel === 'models' ? <>
        {[...groups.entries()].map(([id, models]) => <section className="model-group" key={id} aria-label={models[0].connectionName}>
          <h3>{models[0].connectionName}</h3>
          {models.map(item => <button className="model-option" key={item.modelId} aria-pressed={item === model} disabled={disabled} onClick={() => { selection.select(item); setPanel(null); }}>
            <span><strong>{item.modelName}</strong>{item.modelName !== item.modelId && <small>{item.modelId}</small>}</span><span aria-hidden="true">{item === model ? '✓' : ''}</span>
          </button>)}
        </section>)}
        <button className="manage-models" onClick={() => { setPanel(null); post({ type: 'openSettings', page: 'models' }); }}>管理模型</button>
      </> : <div className="thinking-options">{[{ id: '', label: 'default' }, ...(model?.thinkingLevels ?? [])].map(level => <button className="model-option" key={level.id} aria-pressed={level.id === selected?.thinkingLevel} disabled={disabled} onClick={() => { selection.select(level.id); setPanel(null); }}><span>{level.id || 'default'}</span><span aria-hidden="true">{level.id === selected?.thinkingLevel ? '✓' : ''}</span></button>)}</div>}
    </Panel>}
  </>;
}
