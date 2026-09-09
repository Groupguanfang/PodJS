package dev.podjs.runtime;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.ArrayList;

/**
 * Asynchronous, single-session native audio and speech services.
 *
 * <p>Methods are {@code audio.start} ({@code url}, optional {@code loop}),
 * {@code audio.pause}, {@code audio.resume}, {@code audio.stop}, and
 * {@code audio.state}. URLs may be http(s), or a relative path below the
 * private {@code filesDir/podjs/files} directory. TTS methods are
 * {@code tts.init} (optional {@code language}, BCP-47), {@code tts.speak}
 * ({@code text}, optional {@code language}), {@code tts.stop}, and
 * {@code tts.state}. Successful results contain {@code ok:true}; audio state
 * also contains {@code state}, {@code positionMs}, and {@code durationMs},
 * while TTS state contains {@code state} and {@code initialized}.</p>
 */
public final class PodMediaServices implements AutoCloseable {
    public interface Callback {
        void complete(JSONObject value);
        void fail(String code, String message);
    }

    private static final String AUDIO_IDLE = "idle";
    private static final String AUDIO_PREPARING = "preparing";
    private static final String AUDIO_PLAYING = "playing";
    private static final String AUDIO_PAUSED = "paused";
    private static final String AUDIO_STOPPED = "stopped";
    private static final String AUDIO_ERROR = "error";
    private static final String TTS_UNINITIALIZED = "uninitialized";
    private static final String TTS_READY = "ready";
    private static final String TTS_SPEAKING = "speaking";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audioManager;
    private final AudioFocusRequest focusRequest;
    private final File mediaRoot;
    private final ArrayList<Callback> ttsInitCallbacks = new ArrayList<>();
    private MediaPlayer player;
    private Callback pendingAudioCallback;
    private TextToSpeech tts;
    private String audioState = AUDIO_IDLE;
    private boolean ttsInitialized;
    private boolean ttsSpeaking;
    private volatile boolean closed;

    public PodMediaServices(Context context) {
        if (context == null) throw new IllegalArgumentException("context_required");
        this.context = context.getApplicationContext();
        File root = new File(this.context.getFilesDir(), "podjs/files");
        try {
            if (!root.exists() && !root.mkdirs()) throw new IOException("mkdir_failed");
            mediaRoot = root.getCanonicalFile();
        } catch (IOException e) { throw new IllegalStateException("service_root_unavailable", e); }
        audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(this::onAudioFocusChange).build();
    }

    /** Cancels the outstanding operation for a media kind, if any. */
    public void cancel(String method) {
        main.post(() -> {
            if ("audio.start".equals(method) && pendingAudioCallback != null) {
                Callback callback = pendingAudioCallback; pendingAudioCallback = null;
                callback.fail("cancelled", "Audio preparation cancelled"); releasePlayer(); audioState = AUDIO_STOPPED; abandonFocus();
            } else if ("tts.init".equals(method) && !ttsInitCallbacks.isEmpty()) {
                ArrayList<Callback> callbacks = new ArrayList<>(ttsInitCallbacks); ttsInitCallbacks.clear();
                for (Callback callback : callbacks) callback.fail("cancelled", "Text to speech initialization cancelled");
            }
        });
    }

    public void dispatch(String method, JSONObject args, Callback callback) {
        if (method == null || args == null || callback == null) throw new IllegalArgumentException("method_args_callback_required");
        main.post(() -> {
            if (closed) { callback.fail("service_closed", "Media service is closed"); return; }
            try {
                switch (method) {
                    case "audio.start": audioStart(args, callback); break;
                    case "audio.pause": audioPause(callback); break;
                    case "audio.resume": audioResume(callback); break;
                    case "audio.stop": audioStop(callback); break;
                    case "audio.state": callback.complete(audioState()); break;
                    case "tts.init": ttsInit(args, callback); break;
                    case "tts.speak": ttsSpeak(args, callback); break;
                    case "tts.stop": ttsStop(callback); break;
                    case "tts.state": callback.complete(ttsState()); break;
                    default: callback.fail("unsupported_method", "Unsupported media method: " + method);
                }
            } catch (Exception e) { callback.fail(errorCode(e), message(e)); }
        });
    }

    private void audioStart(JSONObject args, Callback callback) throws Exception {
        String source = args.optString("url", null);
        if (source == null || source.isEmpty()) throw new IllegalArgumentException("missing_url");
        if (pendingAudioCallback != null) { pendingAudioCallback.fail("cancelled", "Audio preparation replaced"); pendingAudioCallback = null; }
        releasePlayer();
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        player.setLooping(args.optBoolean("loop", false));
        final boolean[] callbackSent = {false};
        player.setOnCompletionListener(mp -> { audioState = AUDIO_STOPPED; abandonFocus(); });
        player.setOnErrorListener((mp, what, extra) -> {
            audioState = AUDIO_ERROR; abandonFocus();
            if (!callbackSent[0]) { callbackSent[0] = true; pendingAudioCallback = null; callback.fail("media_error", "MediaPlayer error " + what + "/" + extra); }
            return true;
        });
        player.setOnPreparedListener(mp -> {
            if (closed || player != mp) return;
            audioState = AUDIO_PLAYING;
            if (!requestFocus()) { audioState = AUDIO_ERROR; pendingAudioCallback = null; callbackSent[0] = true; releasePlayer(); callback.fail("audio_focus_denied", "Audio focus was denied"); return; }
            mp.start();
            if (!callbackSent[0]) {
                callbackSent[0] = true;
                pendingAudioCallback = null;
                try { complete(callback, new JSONObject().put("ok", true).put("state", audioState)); }
                catch (Exception e) { callback.fail("media_error", message(e)); }
            }
        });
        audioState = AUDIO_PREPARING;
        if (source.startsWith("http://") || source.startsWith("https://")) player.setDataSource(source);
        else player.setDataSource(context, Uri.fromFile(safeFile(source)));
        player.prepareAsync();
        pendingAudioCallback = callback;
    }

    private void audioPause(Callback callback) throws Exception {
        requirePlayer(); if (player.isPlaying()) { player.pause(); audioState = AUDIO_PAUSED; }
        callback.complete(audioState());
    }
    private void audioResume(Callback callback) throws Exception {
        requirePlayer(); if (!requestFocus()) { audioState = AUDIO_ERROR; callback.fail("audio_focus_denied", "Audio focus was denied"); return; }
        player.start(); audioState = AUDIO_PLAYING; callback.complete(audioState());
    }
    private void audioStop(Callback callback) throws Exception {
        if (pendingAudioCallback != null) { pendingAudioCallback.fail("cancelled", "Audio preparation stopped"); pendingAudioCallback = null; }
        if (player != null) { if (!AUDIO_PREPARING.equals(audioState)) try { player.stop(); } catch (IllegalStateException ignored) {} releasePlayer(); }
        audioState = AUDIO_STOPPED; abandonFocus(); callback.complete(audioState());
    }
    private void requirePlayer() { if (player == null || AUDIO_PREPARING.equals(audioState)) throw new IllegalStateException("audio_not_ready"); }
    private JSONObject audioState() {
        try { return new JSONObject().put("ok", true).put("state", audioState)
                .put("positionMs", player == null ? 0 : safePosition()).put("durationMs", player == null ? 0 : safeDuration()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private int safePosition() { try { return player.getCurrentPosition(); } catch (IllegalStateException e) { return 0; } }
    private int safeDuration() { try { return player.getDuration(); } catch (IllegalStateException e) { return 0; } }

    private void ttsInit(JSONObject args, Callback callback) {
        if (tts != null) { if (!ttsInitialized) ttsInitCallbacks.add(callback); else callback.complete(ttsState()); return; }
        ttsInitCallbacks.add(callback);
        String language = args.optString("language", "");
        tts = new TextToSpeech(context, status -> main.post(() -> {
            if (closed || tts == null) return;
            if (status != TextToSpeech.SUCCESS) { tts = null; finishTtsInit("tts_unavailable", "Text to speech initialization failed"); return; }
            Locale locale = language.isEmpty() ? Locale.getDefault() : Locale.forLanguageTag(language);
            int result = tts.setLanguage(locale);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                finishTtsInit("tts_language_unavailable", "Text to speech language is unavailable"); return;
            }
            ttsInitialized = true; ArrayList<Callback> callbacks = new ArrayList<>(ttsInitCallbacks); ttsInitCallbacks.clear();
            for (Callback pending : callbacks) pending.complete(ttsState());
        }));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            public void onStart(String id) { main.post(() -> { ttsSpeaking = true; }); }
            public void onDone(String id) { main.post(() -> { ttsSpeaking = false; }); }
            public void onError(String id) { main.post(() -> { ttsSpeaking = false; }); }
        });
    }
    private void finishTtsInit(String code, String message) {
        ArrayList<Callback> callbacks = new ArrayList<>(ttsInitCallbacks); ttsInitCallbacks.clear();
        for (Callback pending : callbacks) pending.fail(code, message);
    }
    private void ttsSpeak(JSONObject args, Callback callback) throws Exception {
        if (!ttsInitialized || tts == null) throw new IllegalStateException("tts_not_initialized");
        String text = args.optString("text", null); if (text == null || text.isEmpty()) throw new IllegalArgumentException("missing_text");
        String language = args.optString("language", "");
        if (!language.isEmpty()) {
            int languageResult = tts.setLanguage(Locale.forLanguageTag(language));
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED)
                throw new IllegalArgumentException("tts_language_unavailable");
        }
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
        if (result != TextToSpeech.SUCCESS) throw new IllegalStateException("tts_speak_failed");
        ttsSpeaking = true; callback.complete(ttsState());
    }
    private void ttsStop(Callback callback) throws Exception { if (tts != null) tts.stop(); ttsSpeaking = false; callback.complete(ttsState()); }
    private JSONObject ttsState() {
        try { return new JSONObject().put("ok", true).put("initialized", ttsInitialized)
                .put("state", ttsSpeaking ? TTS_SPEAKING : ttsInitialized ? TTS_READY : TTS_UNINITIALIZED); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private File safeFile(String path) throws IOException {
        if (path.startsWith("/") || path.indexOf('\0') >= 0 || path.isEmpty()) throw new IllegalArgumentException("invalid_path");
        File f = new File(mediaRoot, path).getCanonicalFile();
        if (!f.getPath().startsWith(mediaRoot.getPath() + File.separator) || !f.isFile()) throw new SecurityException("invalid_media_path");
        return f;
    }
    private void complete(Callback c, JSONObject value) { if (!closed) c.complete(value); }
    private boolean requestFocus() { return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED; }
    private void abandonFocus() { audioManager.abandonAudioFocusRequest(focusRequest); }
    private void releasePlayer() { if (player != null) { try { player.reset(); } catch (Exception ignored) {} player.release(); player = null; } }
    private void onAudioFocusChange(int change) { main.post(() -> { if (player == null) return; if (change == AudioManager.AUDIOFOCUS_LOSS) { if (player.isPlaying()) player.pause(); audioState = AUDIO_PAUSED; abandonFocus(); } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { if (player.isPlaying()) player.pause(); audioState = AUDIO_PAUSED; } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { player.setVolume(.2f, .2f); } else if (change == AudioManager.AUDIOFOCUS_GAIN) player.setVolume(1f, 1f); }); }
    @Override public void close() { closed = true; main.post(() -> { if (pendingAudioCallback != null) { pendingAudioCallback.fail("service_closed", "Media service is closed"); pendingAudioCallback = null; } finishTtsInit("service_closed", "Media service is closed"); releasePlayer(); abandonFocus(); if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } ttsInitialized = false; ttsSpeaking = false; audioState = AUDIO_IDLE; }); }
    private static String errorCode(Exception e) { return e instanceof SecurityException ? "permission_denied" : e instanceof IllegalArgumentException ? "invalid_argument" : "media_error"; }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
