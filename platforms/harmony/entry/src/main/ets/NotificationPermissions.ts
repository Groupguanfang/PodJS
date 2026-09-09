import { ServiceHandler, ServiceReply, ServiceRequest } from './ServicePump';

export interface NotificationPermissionApi {
  isEnabled(): Promise<boolean>;
  requestEnable(): Promise<void>;
}

export class NotificationPermissionError extends Error {
  constructor(public code: string) { super(code); }
}

/** OS authorization only. Install behind AuthorizedServices; this does not
 * grant notification.local or implement scheduling/push. Cancellation suppresses
 * results and a not-yet-opened prompt, but cannot dismiss an OS-owned dialog. */
export class NotificationPermissions implements ServiceHandler {
  private active: Map<number, ServiceRequest> = new Map();
  private prompt: Promise<void> | null = null;
  constructor(private api: NotificationPermissionApi) {}

  handle(request: ServiceRequest, complete: (reply: ServiceReply) => void): void {
    if (request.method !== 'notifications.status' && request.method !== 'notifications.requestPermission') {
      const reply = new ServiceReply(); reply.code = 'unsupported';
      reply.message = 'Unsupported Harmony notification service'; complete(reply); return;
    }
    this.active.set(request.id, request);
    this.run(request, complete);
  }
  cancel(id: number): void { this.active.delete(id); }

  private async run(request: ServiceRequest, complete: (reply: ServiceReply) => void): Promise<void> {
    const reply = new ServiceReply();
    try {
      let enabled = await this.api.isEnabled();
      if (this.active.get(request.id) !== request) return;
      if (!enabled && request.method === 'notifications.requestPermission') {
        if (this.prompt === null) {
          // Defer invocation so synchronous adapter errors also become rejections.
          const operation = Promise.resolve().then(() => this.openPrompt());
          this.prompt = operation;
          operation.then(() => { if (this.prompt === operation) this.prompt = null; },
            () => { if (this.prompt === operation) this.prompt = null; });
        }
        await this.prompt;
        if (this.active.get(request.id) !== request) return;
        enabled = await this.api.isEnabled();
      }
      reply.ok = true; reply.value = enabled ? 'granted' : 'denied';
    } catch (error) {
      const code = error instanceof NotificationPermissionError ? error.code : 'host_error';
      if (code === 'denied') { reply.ok = true; reply.value = 'denied'; }
      else { reply.code = code; reply.message = 'Notification permission operation failed'; }
    }
    if (this.active.get(request.id) !== request) return;
    this.active.delete(request.id); complete(reply);
  }
  private openPrompt(): Promise<void> {
    for (const request of this.active.values()) {
      if (request.method === 'notifications.requestPermission') return this.api.requestEnable();
    }
    return Promise.resolve();
  }
}
