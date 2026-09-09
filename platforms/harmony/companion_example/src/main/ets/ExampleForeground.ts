/** Transient invitation owners register here; no credentials are deleted. */
const owners: Set<() => void> = new Set();
export function onExampleBackground(cancel: () => void): () => void {
  owners.add(cancel);
  return () => { owners.delete(cancel); };
}
export function cancelExampleForeground(): void {
  for (const cancel of Array.from(owners)) {
    try { cancel(); } catch (_) {}
  }
}
