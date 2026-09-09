export interface ServiceTransport {
  poll(): string | null;
  post(json: string): boolean;
}

export class ServiceRequest {
  t: string = '';
  version: number = 0;
  id: number = 0;
  method: string = '';
  args: Object = {};
}

export class ServiceReply {
  t: string = 'service.result';
  id: number = 0;
  ok: boolean = false;
  value: Object | null = null;
  code: string = '';
  message: string = '';
}

export interface ServiceHandler {
  handle(request: ServiceRequest, complete: (reply: ServiceReply) => void): void;
  cancel(id: number): void;
}

/** Foreground, bounded service transport. No timers and no guest execution.
 * The page owns the lifetime; a successful native post only means enqueued. */
export class ServicePump {
  private active: Map<number, ServiceRequest> = new Map();
  private replies: Map<number, string> = new Map();
  private closed: boolean = false;
  private pumping: boolean = false;

  constructor(private transport: ServiceTransport, private handler: ServiceHandler,
    private otherEffect: (raw: string) => void = () => {}) {}

  pump(): void {
    if (this.closed || this.pumping) return;
    this.pumping = true;
    try {
      this.flush();
      for (let i = 0; i < 64 && this.replies.size < 64; i++) {
        const raw = this.transport.poll();
        if (raw === null) break;
        let request: ServiceRequest;
        try { request = JSON.parse(raw) as ServiceRequest; } catch (_) { continue; }
        if (request !== null && typeof request === 'object' &&
            request.t !== 'service.request' && request.t !== 'service.cancel') {
          try { this.otherEffect(raw); } catch (_) { /* Do not stall unrelated requests. */ }
          continue;
        }
        if (request === null || typeof request !== 'object' ||
            (request.t !== 'service.request' && request.t !== 'service.cancel') ||
            !Number.isInteger(request.id) || request.id <= 0 || request.id > 2147483647) continue;
        if (request.t === 'service.cancel') {
          if (this.active.delete(request.id)) this.handler.cancel(request.id);
          this.replies.delete(request.id);
          continue;
        }
        // Do not let a duplicate resolve or cancel the original operation.
        if (this.active.has(request.id) || this.replies.has(request.id)) continue;
        if (this.active.size >= 64) {
          const busy = new ServiceReply(); busy.id = request.id;
          busy.code = 'busy'; busy.message = 'Too many host operations';
          this.replies.set(request.id, JSON.stringify(busy));
          this.flush(); continue;
        }
        this.active.set(request.id, request);
        if (request.version !== 1) {
          this.failure(request, 'unsupported', 'Unsupported service version');
        } else if (typeof request.method !== 'string' || !request.method ||
            request.args === null || typeof request.args !== 'object' || Array.isArray(request.args)) {
          this.failure(request, 'invalid_argument', 'Invalid service request');
        } else {
          try {
            this.handler.handle(request, (reply: ServiceReply) => this.complete(request, reply));
          } catch (_) { this.failure(request, 'host_error', 'Host operation failed'); }
        }
      }
      this.flush();
    } finally { this.pumping = false; }
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    const ids = Array.from(this.active.keys());
    this.active.clear(); this.replies.clear();
    for (const id of ids) this.handler.cancel(id);
  }

  private failure(request: ServiceRequest, code: string, message: string): void {
    const reply = new ServiceReply(); reply.code = code; reply.message = message;
    this.complete(request, reply);
  }

  private complete(request: ServiceRequest, reply: ServiceReply): void {
    if (this.closed || this.active.get(request.id) !== request || this.replies.has(request.id)) return;
    reply.t = 'service.result'; reply.id = request.id;
    let json: string;
    try { json = JSON.stringify(reply); }
    catch (_) { this.failure(request, 'host_error', 'Invalid host result'); return; }
    // Conservatively bound encoded size (at most 3 UTF-8 bytes per UTF-16 unit).
    if (json.length > 349000) { this.failure(request, 'resource_exhausted', 'Host result too large'); return; }
    this.replies.set(request.id, json);
    this.flush();
  }

  private flush(): void {
    for (const item of this.replies) {
      if (!this.transport.post(item[1])) break;
      this.replies.delete(item[0]); this.active.delete(item[0]);
    }
  }
}

/** No capability is enabled by installing this transport. */
export class UnsupportedServices implements ServiceHandler {
  handle(request: ServiceRequest, complete: (reply: ServiceReply) => void): void {
    const reply = new ServiceReply(); reply.code = 'unsupported';
    reply.message = 'Unsupported Harmony host service'; complete(reply);
  }
  cancel(id: number): void {}
}
