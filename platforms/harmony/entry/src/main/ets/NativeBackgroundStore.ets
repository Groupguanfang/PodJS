import * as podjs from 'libpodjs_harmony.so';
import { common } from '@kit.AbilityKit';
import { BackgroundStore } from './BackgroundJournal';

export class NativeBackgroundStore implements BackgroundStore {
  private filesDir: string;
  constructor(context: common.Context) { this.filesDir = context.filesDir; }
  read(): Promise<string | null> { return podjs.backgroundStoreRead(this.filesDir); }
  compareExchange(expected: string | null, desired: string): Promise<boolean> {
    return podjs.backgroundStoreCompareExchange(this.filesDir, expected, desired);
  }
}
