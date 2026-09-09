import * as podjs from 'libpodjs_harmony.so';
import { common, bundleManager } from '@kit.AbilityKit';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { BackgroundExecution, BackgroundExecutionHost, BackgroundRun } from './BackgroundExecution';
import { BackgroundJournal, BackgroundOutcome, BackgroundRecord } from './BackgroundJournal';
import { NativeBackgroundStore } from './NativeBackgroundStore';
import { installedBackgroundPackage } from './NativeBackgroundPackage';

class NativeConfig {
  app_id: string = '';
  task_id: string = 'host-lease';
  source: string = "globalThis.backgroundHandler = () => 'failure'";
  budget_ms: number = 1;
  memory_bytes: number = 8388608;
  payload: Object | null = null;
  allowed_methods: string[] = [];
  kv_root?: string;
}
class PermissionFailure extends Error {
  code: string = 'background_permission_denied';
  constructor() { super('Installed background approval failed'); }
}
class NativeRun implements BackgroundRun {
  private context: common.Context;
  private appId: string;
  private handle: number;
  private prepared: boolean = false;
  constructor(context: common.Context, appId: string, handle: number) { this.context = context; this.appId = appId; this.handle = handle; }
  async prepare(record: BackgroundRecord): Promise<void> {
    if (this.handle < 0) throw new Error('Background handle closed');
    const approved = await installedBackgroundPackage(this.context);
    if (!approved.permits(record.sourceHash, record.grants)) throw new PermissionFailure();
    const source = await approved.resolve(record.task.handler);
    if (source.appId !== this.appId || source.sha256 !== record.sourceHash) throw new PermissionFailure();
    const config = new NativeConfig(); config.app_id = this.appId; config.task_id = record.task.id;
    config.source = source.source; config.payload = record.task.payload; config.budget_ms = record.budgetMs;
    config.allowed_methods = record.grants.slice(); config.kv_root = `${this.context.filesDir}/podjs`;
    podjs.backgroundConfigure(this.handle, JSON.stringify(config)); this.prepared = true;
  }
  async execute(): Promise<BackgroundOutcome> {
    if (!this.prepared || this.handle < 0) throw new Error('Background run is not approved');
    return JSON.parse(await podjs.backgroundExecute(this.handle)) as BackgroundOutcome;
  }
  cancel(): void { if (this.handle >= 0) podjs.backgroundCancel(this.handle); }
  close(): void { if (this.handle >= 0) { const handle = this.handle; this.handle = -1; podjs.backgroundClose(handle); } }
}
class NativeHost implements BackgroundExecutionHost {
  private context: common.Context;
  private appId: string;
  constructor(context: common.Context, appId: string) { this.context = context; this.appId = appId; }
  async openLease(): Promise<BackgroundRun> {
    const config = new NativeConfig(); config.app_id = this.appId;
    // This host constant is only a lease holder. execute() refuses until prepare
    // has installed the current verified handler under the same lease.
    const handle = await podjs.backgroundOpenLeased(JSON.stringify(config), this.context.filesDir);
    return new NativeRun(this.context, this.appId, handle);
  }
  async token(): Promise<string> {
    const random = await cryptoFramework.createRandom().generateRandom(16);
    let token = ''; for (const byte of random.data) token += byte.toString(16).padStart(2, '0'); return token;
  }
  now(): number { return Date.now(); }
}
export async function nativeBackgroundExecution(context: common.Context): Promise<BackgroundExecution> {
  const bundle = await bundleManager.getBundleInfoForSelf(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
  const journal = new BackgroundJournal(bundle.name, new NativeBackgroundStore(context),
    () => new Promise<void>(resolve => { setTimeout(resolve, 5); }));
  return new BackgroundExecution(journal, new NativeHost(context, bundle.name));
}
