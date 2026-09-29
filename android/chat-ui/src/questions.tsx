import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { canAnswerQuestion, post, questionAnswers, questionComplete, QUESTION_TEXT_LIMIT, type PendingQuestion, type QuestionAnswer, type QuestionSnapshot, type indexQuestions } from './contract';
import { coalescedWriter } from './view-state';

export type QuestionDraft = { answers: QuestionAnswer[]; other: boolean[] };
export const QuestionContext = createContext<{ index: ReturnType<typeof indexQuestions>; snapshot: QuestionSnapshot; drafts: Map<string, QuestionDraft> } | null>(null);

function QuestionSummary({ question, status, answers, draft = false }: {
  question: PendingQuestion; status: string; answers: QuestionAnswer[]; draft?: boolean;
}) {
  const summary = answers.flatMap(answer => [...answer.selected, answer.text]).filter(Boolean).join(' · ');
  return <details key={status} className="question-card question-summary" aria-label="问题记录" data-request-id={question.requestId}>
    <summary><span>{status}</span><span className="question-answer-preview">{(summary || question.questions.map(item => item.header || item.question).join(' · ')).slice(0, 140)}</span><span className="chevron">⌄</span></summary>
    <dl>{question.questions.map(item => {
      const answer = answers.find(value => value.questionId === item.id);
      const text = [...(answer?.selected ?? []), answer?.text].filter(Boolean).join(' · ');
      return <div key={item.id}><dt>{item.question}</dt><dd>{text ? `${draft ? '保留草稿：' : ''}${text}` : '未回答'}</dd></div>;
    })}</dl>
  </details>;
}

// Native persistence survives WebView recreation. Streaming snapshots must not
// overwrite text entered since the last debounced save.
export function QuestionCard({ question, snapshot }: { question: PendingQuestion; snapshot: QuestionSnapshot }) {
  const drafts = useContext(QuestionContext)?.drafts;
  const draftKey = JSON.stringify([question.sessionId, question.runId, question.requestId]);
  const [answers, setAnswers] = useState(() => questionAnswers(question, drafts?.get(draftKey)?.answers ??
    (question.status === 'answered' ? question.answers : question.draft)));
  const [other, setOther] = useState(() => drafts?.get(draftKey)?.other ?? answers.map(answer => !answer.selected.length && !!answer.text));
  const [submitted, setSubmitted] = useState(false);
  const [accepted, setAccepted] = useState(false);
  const [error, setError] = useState('');
  const latest = useRef(answers);
  const submissionTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const live = useRef(false);
  const lastResult = useRef<string | undefined>(undefined);
  const answerable = canAnswerQuestion(snapshot, question);
  live.current = answerable && !submitted && !accepted;
  const identity = { sessionId: question.sessionId, runId: question.runId, requestId: question.requestId };
  useEffect(() => {
    if (!drafts) return;
    if (question.status === 'pending') {
      // Keep the same request's edit when its live fallback moves into the
      // streamed tool position before the native draft acknowledgement arrives.
      drafts.set(draftKey, { answers, other });
      if (drafts.size > 32) drafts.delete(drafts.keys().next().value!);
    } else drafts.delete(draftKey);
  }, [drafts, draftKey, question.status, answers, other]);
  const writer = useMemo(() => coalescedWriter<QuestionAnswer[]>(value => {
    if (live.current) post({ type: 'questionDraft', ...identity, answers: value });
  }), [question.sessionId, question.runId, question.requestId]);
  useEffect(() => {
    const result = snapshot.questionResult;
    if (!result || result.requestId !== question.requestId || result.sessionId !== question.sessionId || result.runId !== question.runId) return;
    const resultId = JSON.stringify(result);
    if (lastResult.current === resultId) return;
    lastResult.current = resultId;
    clearTimeout(submissionTimer.current);
    setAccepted(result.accepted);
    if (!result.accepted) { setSubmitted(false); setError(result.error || '回答未提交，请重试'); }
  }, [snapshot.questionResult]);
  useEffect(() => {
    if (!answerable) clearTimeout(submissionTimer.current);
    return () => clearTimeout(submissionTimer.current);
  }, [answerable]);
  const flush = writer.flush;
  useEffect(() => {
    const pageHide = () => flush();
    const visibility = () => { if (document.visibilityState === 'hidden') flush(); };
    window.addEventListener('pagehide', pageHide);
    document.addEventListener('visibilitychange', visibility);
    return () => {
      flush();
      window.removeEventListener('pagehide', pageHide);
      document.removeEventListener('visibilitychange', visibility);
    };
  }, [writer]);
  const update = (index: number, change: Partial<QuestionAnswer>) => {
    if (!live.current) return;
    const next = questionAnswers(question, latest.current.map((answer, i) => i === index ? { ...answer, ...change } : answer));
    latest.current = next;
    setAnswers(next);
    writer.schedule(next);
  };
  const submit = (cancelled = false) => {
    if (!live.current || (!cancelled && !questionComplete(question, latest.current))) return;
    flush();
    // Lock synchronously too, so a second click cannot race React's render.
    live.current = false;
    setError('');
    setAccepted(false);
    setSubmitted(true);
    const submittedAnswers = latest.current.map((answer, index) => !question.questions[index].multiSelect && answer.selected.length
      ? { ...answer, text: '' } : answer);
    // A missing bridge acknowledgement must not lock the user's saved answer
    // forever. Retry is explicit; native request identity prevents double answers.
    clearTimeout(submissionTimer.current);
    submissionTimer.current = setTimeout(() => {
      setSubmitted(false);
      setError('尚未收到提交确认，回答已保留，请重试');
    }, 10000);
    try {
      post({ type: 'answerQuestion', ...identity, answers: cancelled ? [] : submittedAnswers, cancelled });
    } catch {
      clearTimeout(submissionTimer.current);
      setSubmitted(false);
      setError('回答未提交，请重试');
    }
  };
  const status = question.status === 'answered' ? '已回答' : question.status === 'cancelled' ? '已取消回答'
    : question.status === 'interrupted' || !answerable ? '提问已中断' : accepted ? '已提交' : submitted ? '正在提交' : '等待回答';
  const disabled = !answerable || submitted || accepted;
  if (!answerable || accepted) return <QuestionSummary question={question}
    status={accepted && question.status === 'pending' && answerable ? '已提交，等待确认' : status}
    answers={question.status === 'answered' ? questionAnswers(question, question.answers ?? []) : latest.current}
    draft={!accepted && question.status !== 'answered'} />;
  return <form className="question-card" aria-label="回答问题" data-request-id={question.requestId} onSubmit={event => { event.preventDefault(); submit(); }}>
    <header><span>{status}</span></header>
    {question.questions.map((item, index) => <fieldset key={item.id} disabled={disabled}>
      <legend>{item.question}</legend>
      {item.options.length > 0 && <div className="question-options">{item.options.map(option => {
        const checked = answers[index]?.selected.includes(option.label) ?? false;
        return <label key={option.label} className={`question-option ${checked ? 'selected' : ''}`}>
          <input type={item.multiSelect ? 'checkbox' : 'radio'} name={`${question.requestId}-${item.id}`} value={option.label} checked={checked}
            onChange={() => {
              setOther(current => current.map((value, i) => i === index ? false : value));
              update(index, { selected: item.multiSelect
                ? checked ? answers[index].selected.filter(value => value !== option.label) : [...answers[index].selected, option.label]
                : [option.label] });
            }} />
          <span><strong>{option.label}</strong>{option.description && <small>{option.description}</small>}</span>
        </label>;
      })}
        {!item.multiSelect && <label className={`question-option ${other[index] ? 'selected' : ''}`}>
          <input type="radio" name={`${question.requestId}-${item.id}`} checked={other[index] ?? false} aria-label={`${item.header || item.question}：其他答案`}
            onChange={() => {
              setOther(current => current.map((value, i) => i === index ? true : value));
              update(index, { selected: [] });
            }} />
          <span><strong>其他答案</strong></span>
        </label>}
      </div>}
      {(!item.options.length || item.multiSelect || other[index]) && <textarea rows={2} maxLength={QUESTION_TEXT_LIMIT}
        aria-label={`${item.header || item.question}：${!item.options.length ? '答案' : item.multiSelect ? '补充或其他答案' : '其他答案'}`} placeholder={item.multiSelect ? '补充或其他答案' : '填写你的答案'}
        value={answers[index]?.text ?? ''} onChange={event => update(index, { text: event.target.value })} />}
    </fieldset>)}
    {error && answerable && <p className="question-error" role="alert">{error}</p>}
    {question.status === 'pending' && answerable && <footer>
      <button type="button" disabled={disabled} onClick={() => submit(true)}>取消回答</button>
      <button type="submit" disabled={disabled || !questionComplete(question, answers)}>{accepted ? '已提交' : submitted ? '正在提交' : '提交回答'}</button>
    </footer>}
  </form>;
}
