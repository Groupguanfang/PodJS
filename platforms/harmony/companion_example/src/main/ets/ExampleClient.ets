import { common } from '@kit.AbilityKit';
import preferences from '@ohos.data.preferences';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { NativeCompanionClient, CompanionPairings, openCompanionPairings, CompanionConnectionOwner } from '@podjs/companion';

export class ExampleClient {
  readonly client: NativeCompanionClient;
  readonly deviceId: string;
  readonly connection: CompanionConnectionOwner;
  constructor(context: common.UIAbilityContext, deviceId: string) {
    this.deviceId = deviceId; this.client = new NativeCompanionClient(context, 'podjs.companion.demo', deviceId);
    this.connection = new CompanionConnectionOwner(this.client);
  }
}
let current: ExampleClient | null = null;
let opening: Promise<ExampleClient> | null = null;
let pairings: CompanionPairings | null = null;
let openingPairings: Promise<CompanionPairings> | null = null;
async function initialize(context: common.UIAbilityContext): Promise<ExampleClient> {
  const store = await preferences.getPreferences(context, { name: 'podjs-companion-identity' });
  let id = await store.get('deviceId', '') as string;
  if (id === '') {
    const random = await cryptoFramework.createRandom().generateRandom(16);
    id = 'phone-'; for (const byte of random.data) id += byte.toString(16).padStart(2, '0');
    await store.put('deviceId', id);
  }
  if (!/^phone-[0-9a-f]{32}$/.test(id)) throw new Error('invalid saved companion identity');
  // A prior failed flush can leave the new ID in the preferences cache.
  // Reading it back is not evidence that it survived a process restart.
  await store.flush();
  current = new ExampleClient(context, id); return current;
}
export async function exampleClient(context: common.UIAbilityContext): Promise<ExampleClient> {
  if (current !== null) return current;
  if (opening === null) opening = initialize(context);
  try { return await opening; } finally { opening = null; }
}
export function closeExampleConnection(): void { if (current !== null) current.connection.disconnect(); }

/** The foreground app shares one lifetime pairing owner with future connections.
 * Navigating between tabs must not create another OS lease for the same app. */
export async function examplePairings(context: common.UIAbilityContext): Promise<CompanionPairings> {
  if (pairings !== null) return pairings;
  if (openingPairings === null) {
    openingPairings = exampleClient(context).then(async (client: ExampleClient) => {
      const owner = await openCompanionPairings(context, 'podjs.companion.demo', client.deviceId);
      pairings = owner; return owner;
    });
  }
  const pending = openingPairings;
  try { return await pending; } finally { if (openingPairings === pending) openingPairings = null; }
}
