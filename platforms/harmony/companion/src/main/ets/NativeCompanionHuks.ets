import huks from '@ohos.security.huks';

function options(inData?: Uint8Array): huks.HuksOptions {
  return {
    properties: [
      { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_HMAC },
      { tag: huks.HuksTag.HUKS_TAG_PURPOSE, value: huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_MAC },
      { tag: huks.HuksTag.HUKS_TAG_KEY_SIZE, value: 256 },
      { tag: huks.HuksTag.HUKS_TAG_DIGEST, value: huks.HuksKeyDigest.HUKS_DIGEST_SHA256 }
    ],
    inData: inData
  };
}

/** Low-level host keystore adapter, not pairing authorization. The pairing store
 * must allocate a fresh random 32-byte hex ID and durably bind it to identities
 * before exposing a signer. There is deliberately no key export operation. */
export class NativeCompanionHuks {
  private static busy: Set<string> = new Set<string>();

  private alias(id: string): string {
    if (!/^[0-9a-f]{64}$/.test(id)) throw new Error('invalid pairing key ID');
    return 'podjs.companion.v1.' + id;
  }

  async exists(id: string): Promise<boolean> {
    return huks.isKeyItemExist(this.alias(id), { properties: [] });
  }

  /** Never replace a previously provisioned key, including concurrent imports
   * in this process. The owner must also serialize provisioning across processes. */
  async importKey(id: string, key: Uint8Array): Promise<void> {
    const alias = this.alias(id);
    if (key.length !== 32 || !key.some((value: number) => value !== 0)) throw new Error('invalid pairing key');
    if (NativeCompanionHuks.busy.has(alias)) throw new Error('pairing key busy');
    NativeCompanionHuks.busy.add(alias);
    const temporary = key.slice();
    try {
      if (await this.exists(id)) throw new Error('pairing key already exists');
      await huks.importKeyItem(alias, options(temporary));
    } finally {
      temporary.fill(0);
      NativeCompanionHuks.busy.delete(alias);
    }
  }

  async remove(id: string): Promise<void> {
    const alias = this.alias(id);
    if (NativeCompanionHuks.busy.has(alias)) throw new Error('pairing key busy');
    NativeCompanionHuks.busy.add(alias);
    try {
      if (await this.exists(id)) await huks.deleteKeyItem(alias, { properties: [] });
    } finally { NativeCompanionHuks.busy.delete(alias); }
  }

  /** Revocation owner must reject late results and close active connections. */
  async sign(id: string, bytes: Uint8Array): Promise<Uint8Array> {
    const alias = this.alias(id);
    const input = bytes.slice();
    const session = await huks.initSession(alias, options());
    let finished = false;
    try {
      for (let offset = 0; offset < input.length; offset += 65536) {
        await huks.updateSession(session.handle, options(input.slice(offset, offset + 65536)));
      }
      const result = await huks.finishSession(session.handle, options(new Uint8Array(0)));
      finished = true;
      if (!result.outData || result.outData.length !== 32) throw new Error('invalid HUKS HMAC result');
      return result.outData.slice();
    } finally {
      if (!finished) {
        try { await huks.abortSession(session.handle, { properties: [] }); } catch (_) { /* Preserve signing failure. */ }
      }
    }
  }
}
