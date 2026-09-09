package dev.podjs.runtime;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small, synchronous native services used by the PodJS bridge.
 *
 * <p>Methods and arguments are deliberately JSON shaped:
 * secure.get/set/delete use {@code key} and (for set) {@code value}; get returns
 * {@code {exists,value}}. crypto.random uses {@code bytes} and returns
 * {@code {bytesBase64}}. crypto.hash uses {@code dataBase64} and optional
 * {@code algorithm} (SHA-256 by default), returning {@code {digestBase64}}.
 * crypto.encrypt/decrypt use {@code inputBase64}; encrypt returns
 * {@code {nonceBase64,ciphertextBase64}} and decrypt takes those two fields.
 * file.read uses {@code path}, optional {@code offset} and {@code maxBytes}, and
 * returns {@code {dataBase64,offset,size,eof}}. file.write uses {@code path},
 * {@code dataBase64}, and optional {@code atomicReplace}; stat uses {@code path};
 * delete uses {@code path}, returning {@code {deleted}}. File chunks are capped
 * at 256 KiB and paths are relative to the app-private podjs/files directory.</p>
 */
public final class PodDeviceServices implements AutoCloseable {
    public static final int MAX_CHUNK_BYTES = 256 * 1024;
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String SECURE_ALIAS = "podjs.secure.v1";
    private static final String CRYPTO_ALIAS = "podjs.crypto.v1";
    private static final String SECURE_FILE = "secure.store";
    private static final byte[] AAD = "PodJS-device-services-v1".getBytes(StandardCharsets.UTF_8);

    private final File root;
    private final File secureFile;
    private final SecureRandom random = new SecureRandom();
    private boolean closed;

    public PodDeviceServices(Context context) {
        if (context == null) throw new IllegalArgumentException("context_required");
        File files = new File(context.getFilesDir(), "podjs/files");
        if (!files.exists() && !files.mkdirs()) throw new IllegalStateException("service_root_unavailable");
        try { root = files.getCanonicalFile(); secureFile = new File(root.getParentFile(), SECURE_FILE); }
        catch (IOException e) { throw new IllegalStateException("service_root_unavailable", e); }
    }

    public synchronized JSONObject execute(String method, JSONObject args) throws Exception {
        if (closed) throw new IllegalStateException("service_closed");
        if (method == null || args == null) throw new IllegalArgumentException("method_and_args_required");
        switch (method) {
            case "secure.get": return secureGet(args);
            case "secure.set": return secureSet(args);
            case "secure.delete": return secureDelete(args);
            case "crypto.random": return cryptoRandom(args);
            case "crypto.hash": return cryptoHash(args);
            case "crypto.encrypt": return cryptoEncrypt(args);
            case "crypto.decrypt": return cryptoDecrypt(args);
            case "crypto.rsaOaepSha256": return cryptoRsaOaepSha256(args);
            case "file.read": return fileRead(args);
            case "file.write": return fileWrite(args);
            case "file.stat": return fileStat(args);
            case "file.delete": return fileDelete(args);
            default: throw new IllegalArgumentException("unsupported_method:" + method);
        }
    }

    @Override public synchronized void close() { closed = true; }

    private JSONObject secureGet(JSONObject a) throws Exception {
        JSONObject values = readSecure(); String key = required(a, "key");
        JSONObject out = new JSONObject(); out.put("exists", values.has(key));
        if (values.has(key)) out.put("value", values.getString(key));
        return out;
    }
    private JSONObject secureSet(JSONObject a) throws Exception {
        String key = required(a, "key"); String value = required(a, "value");
        JSONObject values = readSecure(); values.put(key, value); writeSecure(values);
        return new JSONObject().put("ok", true);
    }
    private JSONObject secureDelete(JSONObject a) throws Exception {
        JSONObject values = readSecure(); boolean removed = values.remove(required(a, "key")) != null;
        if (removed) writeSecure(values); return new JSONObject().put("deleted", removed);
    }
    private JSONObject readSecure() throws Exception {
        if (!secureFile.isFile()) return new JSONObject();
        byte[] all = readAll(secureFile, MAX_CHUNK_BYTES + 64);
        if (all.length < 13) throw new IOException("secure_store_corrupt");
        return new JSONObject(new String(decryptWithKey(getKey(SECURE_ALIAS), all), StandardCharsets.UTF_8));
    }
    private void writeSecure(JSONObject values) throws Exception {
        byte[] encrypted = encryptWithKey(getKey(SECURE_ALIAS), values.toString().getBytes(StandardCharsets.UTF_8));
        atomicWrite(secureFile, encrypted);
    }

    private JSONObject cryptoRandom(JSONObject a) throws Exception {
        int n = a.optInt("bytes", 32); if (n < 0 || n > MAX_CHUNK_BYTES) throw new IllegalArgumentException("invalid_bytes");
        byte[] out = new byte[n]; random.nextBytes(out); return new JSONObject().put("bytesBase64", enc(out));
    }
    private JSONObject cryptoHash(JSONObject a) throws Exception {
        byte[] data = decodeInput(a); String algorithm = a.optString("algorithm", "SHA-256");
        if (!"SHA-256".equalsIgnoreCase(algorithm)) throw new IllegalArgumentException("unsupported_hash");
        return new JSONObject().put("algorithm", "SHA-256").put("digestBase64", enc(MessageDigest.getInstance("SHA-256").digest(data)));
    }
    private JSONObject cryptoRsaOaepSha256(JSONObject args) throws Exception {
        String pem=args.optString("publicKeyPem", "");
        if(pem.length()>8192 || !pem.startsWith("-----BEGIN PUBLIC KEY-----"))throw new IllegalArgumentException("invalid_public_key");
        String body=pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        java.security.interfaces.RSAPublicKey key=(java.security.interfaces.RSAPublicKey)java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.decode(body,Base64.DEFAULT)));
        int bits=key.getModulus().bitLength();if(bits<1024||bits>4096)throw new IllegalArgumentException("invalid_rsa_key_size");
        byte[] input=decode(args,"inputBase64");if(input.length>(bits+7)/8-66)throw new IllegalArgumentException("rsa_message_too_long");
        Cipher cipher=Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key,new javax.crypto.spec.OAEPParameterSpec("SHA-256","MGF1",java.security.spec.MGF1ParameterSpec.SHA256,javax.crypto.spec.PSource.PSpecified.DEFAULT));
        return new JSONObject().put("ciphertextBase64",enc(cipher.doFinal(input)));
    }
    private JSONObject cryptoEncrypt(JSONObject a) throws Exception {
        byte[] encrypted = encryptWithKey(getKey(CRYPTO_ALIAS), decodeInput(a));
        return new JSONObject().put("nonceBase64", enc(Arrays.copyOf(encrypted, 12))).put("ciphertextBase64", enc(Arrays.copyOfRange(encrypted, 12, encrypted.length)));
    }
    private JSONObject cryptoDecrypt(JSONObject a) throws Exception {
        byte[] nonce = decode(a, "nonceBase64"); if (nonce.length != 12) throw new IllegalArgumentException("invalid_nonce");
        byte[] ciphertext = decode(a, "ciphertextBase64"); byte[] all = new byte[nonce.length + ciphertext.length];
        System.arraycopy(nonce, 0, all, 0, nonce.length); System.arraycopy(ciphertext, 0, all, nonce.length, ciphertext.length);
        return new JSONObject().put("dataBase64", enc(decryptWithKey(getKey(CRYPTO_ALIAS), all)));
    }

    private JSONObject fileRead(JSONObject a) throws Exception {
        File f = safeFile(required(a, "path")); if (!f.isFile()) throw new IOException("file_not_found");
        long offset = a.optLong("offset", 0); if (offset < 0 || offset > f.length()) throw new IllegalArgumentException("invalid_offset");
        int max = bounded(a.optInt("maxBytes", MAX_CHUNK_BYTES)); byte[] out = new byte[max]; int n;
        try (FileInputStream in = new FileInputStream(f)) { if (offset > 0 && in.skip(offset) != offset) throw new IOException("seek_failed"); n = in.read(out); }
        if (n < 0) n = 0; return new JSONObject().put("dataBase64", enc(Arrays.copyOf(out, n))).put("offset", offset).put("size", f.length()).put("eof", offset + n >= f.length());
    }
    private JSONObject fileWrite(JSONObject a) throws Exception {
        File f = safeFile(required(a, "path")); byte[] data = decode(a, "dataBase64"); if (data.length > MAX_CHUNK_BYTES) throw new IllegalArgumentException("chunk_too_large");
        File parent = f.getParentFile(); if (!parent.exists() && !parent.mkdirs()) throw new IOException("parent_unavailable");
        if (a.optBoolean("atomicReplace", true)) atomicWrite(f, data); else try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); out.getFD().sync(); }
        return new JSONObject().put("size", f.length());
    }
    private JSONObject fileStat(JSONObject a) throws Exception {
        File f = safeFile(required(a, "path")); return new JSONObject().put("exists", f.exists()).put("file", f.isFile()).put("directory", f.isDirectory()).put("size", f.isFile() ? f.length() : 0).put("modified", f.exists() ? f.lastModified() : 0);
    }
    private JSONObject fileDelete(JSONObject a) throws Exception { File f = safeFile(required(a, "path")); return new JSONObject().put("deleted", f.isFile() && f.delete()); }

    private File safeFile(String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid_path");
        File f = new File(root, path).getCanonicalFile(); String base = root.getPath() + File.separator;
        if (!f.getPath().startsWith(base)) throw new SecurityException("path_outside_root"); return f;
    }
    private static int bounded(int n) { if (n < 0 || n > MAX_CHUNK_BYTES) throw new IllegalArgumentException("invalid_max_bytes"); return n; }
    private static String required(JSONObject a, String k) { String v = a.optString(k, null); if (v == null) throw new IllegalArgumentException("missing_" + k); return v; }
    private static byte[] decodeInput(JSONObject a) { return a.has("dataBase64") ? decode(a, "dataBase64") : required(a, "data").getBytes(StandardCharsets.UTF_8); }
    private static byte[] decode(JSONObject a, String k) { try { byte[] b = Base64.decode(required(a, k), Base64.DEFAULT); if (b.length > MAX_CHUNK_BYTES) throw new IllegalArgumentException("chunk_too_large"); return b; } catch (IllegalArgumentException e) { throw new IllegalArgumentException("invalid_base64", e); } }
    private static String enc(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    private SecretKey getKey(String alias) throws Exception { KeyStore ks = KeyStore.getInstance(KEYSTORE); ks.load(null); if (!ks.containsAlias(alias)) { KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE); kg.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); kg.generateKey(); } return ((KeyStore.SecretKeyEntry) ks.getEntry(alias, null)).getSecretKey(); }
    private byte[] encryptWithKey(SecretKey key, byte[] input) throws Exception { Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key); c.updateAAD(AAD); byte[] body = c.doFinal(input); byte[] nonce = c.getIV(); if (nonce == null || nonce.length != 12) throw new IOException("invalid_keystore_iv"); byte[] all = new byte[nonce.length + body.length]; System.arraycopy(nonce, 0, all, 0, nonce.length); System.arraycopy(body, 0, all, nonce.length, body.length); return all; }
    private static byte[] decryptWithKey(SecretKey key, byte[] all) throws Exception { Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(all, 12))); c.updateAAD(AAD); return c.doFinal(Arrays.copyOfRange(all, 12, all.length)); }
    private static byte[] readAll(File f, int limit) throws IOException { try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] b = new byte[8192]; int n, total = 0; while ((n = in.read(b)) >= 0) { total += n; if (total > limit) throw new IOException("secure_store_too_large"); out.write(b, 0, n); } return out.toByteArray(); } }
    private static void atomicWrite(File target, byte[] data) throws IOException { File tmp = new File(target.getParentFile(), target.getName() + ".tmp-" + System.nanoTime()); try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); out.getFD().sync(); } try { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); } finally { if (tmp.exists()) tmp.delete(); } }
}
