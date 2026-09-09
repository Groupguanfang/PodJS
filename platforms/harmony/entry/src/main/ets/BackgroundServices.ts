import { BackgroundJournalError, BackgroundRecord, BackgroundTask } from './BackgroundJournal';
import { BackgroundSchedules } from './BackgroundSchedules';
import { ServiceHandler, ServiceReply, ServiceRequest } from './ServicePump';

class RegisterArgs { task: BackgroundTask = new BackgroundTask(); }
class IdArgs { id: string = ''; }
class Failure { code: string = ''; }
class Status { id: string = ''; state: string = ''; result?: string; }
function validId(value: string): boolean { return typeof value === 'string' && /^[A-Za-z0-9_.:-]{1,128}$/.test(value); }
function status(record: BackgroundRecord | null): Status {
  if (!record) throw new BackgroundJournalError('not_found');
  const value = new Status(); value.id = record.task.id;
  value.state = record.phase === 'pending' ? 'scheduled' : record.phase === 'cancelling' ? 'running' : record.phase;
  if (record.result) { value.result = record.result.status; if (value.result === 'retry' && !record.retrySuppressed) value.state = 'scheduled'; }
  return value;
}
/** Runs behind AuthorizedServices. Caller paths, identities and grants are
 * deliberately not forwarded; approval comes from the installed package. */
export class BackgroundServices implements ServiceHandler {
  private active: Map<number, ServiceRequest> = new Map();
  constructor(private delegate: ServiceHandler, private schedules: () => Promise<BackgroundSchedules>) {}
  handle(request: ServiceRequest, complete: (reply: ServiceReply) => void): void {
    if (!['background.register', 'background.cancel', 'background.status'].includes(request.method)) {
      this.delegate.handle(request, complete); return;
    }
    this.active.set(request.id, request); this.run(request, complete);
  }
  cancel(id: number): void { this.active.delete(id); this.delegate.cancel(id); }
  private async run(request: ServiceRequest, complete: (reply: ServiceReply) => void): Promise<void> {
    const current = (): boolean => this.active.get(request.id) === request;
    const reply = new ServiceReply();
    try {
      let task = new BackgroundTask(); let id = '';
      if (request.method === 'background.register') {
        const input = (request.args as RegisterArgs)?.task;
        if (!input || !validId(input.id) || !validId(input.handler) || !Number.isSafeInteger(input.earliestAt) || input.earliestAt < 0 ||
            (input.requiresNetwork !== undefined && typeof input.requiresNetwork !== 'boolean')) throw new BackgroundJournalError('invalid_argument');
        if (input.intervalMs !== undefined) throw new BackgroundJournalError('unsupported');
        task.id = input.id; task.handler = input.handler; task.earliestAt = input.earliestAt;
        task.requiresNetwork = input.requiresNetwork ?? false; task.payload = input.payload ?? null;
        task = JSON.parse(JSON.stringify(task)) as BackgroundTask;
      } else { id = (request.args as IdArgs)?.id; if (!validId(id)) throw new BackgroundJournalError('invalid_argument'); }
      const schedules = await this.schedules(); if (!current()) return;
      if (request.method === 'background.register') reply.value = status(await schedules.register(task, current));
      else if (request.method === 'background.cancel') await schedules.cancel(id, current);
      else reply.value = status(await schedules.status(id));
      reply.ok = true;
    } catch (error) {
      const code = (error as Failure)?.code;
      reply.code = ['invalid_argument', 'unsupported', 'not_found', 'busy', 'resource_exhausted', 'conflict'].includes(code) ? code : 'host_error';
      reply.message = 'Background operation failed';
    }
    if (!current()) return;
    this.active.delete(request.id); complete(reply);
  }
}
