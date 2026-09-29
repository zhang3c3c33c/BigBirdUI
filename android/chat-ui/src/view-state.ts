/** Coalesce bridge writes without letting sustained editing/scrolling defer them forever.
 * Values carry their session/request identity; flush never reads the current page. */
export function coalescedWriter<T>(write: (value: T) => void, delay = 250, maxWait = 1000) {
  let pending: T | undefined;
  let tail: ReturnType<typeof setTimeout> | undefined;
  let ceiling: ReturnType<typeof setTimeout> | undefined;
  const flush = () => {
    clearTimeout(tail); clearTimeout(ceiling); tail = undefined; ceiling = undefined;
    if (pending === undefined) return;
    const value = pending; pending = undefined; write(value);
  };
  return {
    schedule(value: T) {
      pending = value;
      clearTimeout(tail); tail = setTimeout(flush, delay);
      ceiling ??= setTimeout(flush, maxWait);
    },
    flush,
    cancel() { pending = undefined; clearTimeout(tail); clearTimeout(ceiling); tail = undefined; ceiling = undefined; },
  };
}
import type { Command, Snapshot } from './contract';


type ViewState = Extract<Command, { type: 'viewState' }>;
/** Sending clears a native draft before its ACK reaches the WebView. Do not let
 * scroll events save the still-visible submitted text over that clear. */
export function viewStateSync(write: (value: ViewState) => void) {
  const writer = coalescedWriter(write);
  const latest = new Map<string, ViewState>();
  const pending = new Map<string, { sessionId: string; text: string; edited: boolean }>();
  const clearAccepted = new Map<string, boolean>();
  return {
    flush: writer.flush,
    save(value: ViewState) {
      latest.set(value.sessionId, value);
      const submissions = [...pending.values()].filter(item => item.sessionId === value.sessionId);
      for (const item of submissions) if (item.text !== value.text) item.edited = true;
      const newest = submissions.at(-1);
      if (newest && !newest.edited && newest.text === value.text) return;
      writer.schedule(value);
    },
    begin(id: string, value: ViewState) {
      writer.flush();
      latest.set(value.sessionId, value);
      pending.set(id, { sessionId: value.sessionId, text: value.text, edited: false });
    },
    settle(result: Snapshot['submissionResult']) {
      if (!result) return;
      const submission = pending.get(result.id);
      if (!submission || submission.sessionId !== result.sessionId) return;
      pending.delete(result.id);
      const value = latest.get(result.sessionId);
      const newerPending = [...pending.values()].some(item => item.sessionId === result.sessionId);
      const clear = result.accepted && !submission.edited && !newerPending && value?.text === submission.text;
      clearAccepted.set(result.id, clear);
      if (clearAccepted.size > 64) clearAccepted.delete(clearAccepted.keys().next().value!);
      if (!value || newerPending || submission.edited) return;
      // Identity and coordinates belong to the original session, even if its
      // ACK arrived while a different conversation is selected.
      const saved = clear ? { ...value, text: '' } : value;
      writer.flush();
      latest.set(result.sessionId, saved); write(saved);
      return saved;
    },
    shouldClear(id: string) { return clearAccepted.get(id) === true; },
  };
}
