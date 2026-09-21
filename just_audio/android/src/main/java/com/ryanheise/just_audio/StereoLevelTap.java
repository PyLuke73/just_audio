package com.ryanheise.just_audio;

import android.media.AudioFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import io.flutter.plugin.common.BinaryMessenger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Tap sul PCM decodificato reale (via {@link TeeAudioProcessor}, non
 * {@code android.media.audiofx.Visualizer}): a differenza di
 * {@code Visualizer}, che su questo device forza un downmix mono, qui i due
 * canali restano separati fino al calcolo del livello.
 *
 * Calcola un RMS lineare (0..1) per canale su finestre di ~33ms (~30Hz) e lo
 * pubblica su un {@link EventChannel} Dart — nessuna UI ancora collegata a
 * questo checkpoint, solo verifica via log dei valori emessi.
 *
 * Supporta solo PCM 16 bit stereo per ora (il formato osservato nella
 * pipeline di just_audio per contenuti mp3/flac standard): altri formati
 * vengono ignorati silenziosamente (nessun evento emesso), coerente con
 * l'idea di non bloccare mai la riproduzione per un problema del meter.
 */
@UnstableApi
public class StereoLevelTap implements TeeAudioProcessor.AudioBufferSink {
    private static final String TAG = "StereoLevelTap";
    private static final long EMIT_INTERVAL_MS = 33;
    private static final long LOG_INTERVAL_MS = 1000;

    private final BetterEventChannel levelEventChannel;
    // TeeAudioProcessor.AudioBufferSink chiama flush()/handleBuffer() sul
    // thread di playback di ExoPlayer, mai sul main thread — verificato su
    // device reale: chiamare EventSink.success() direttamente da lì lancia
    // "Methods marked with @UiThread must be executed on the main thread"
    // (i metodi dei channel Flutter sono @UiThread). Il calcolo RMS resta
    // sul thread di background (puro, nessun problema); solo l'invio
    // dell'evento viene spostato sul main thread.
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int channelCount;
    private int encoding;

    private double sumLeftSquares = 0;
    private double sumRightSquares = 0;
    private long sampleCount = 0;
    private long lastEmitAtMs = 0;
    private long lastLogAtMs = 0;

    public StereoLevelTap(BinaryMessenger messenger, String id) {
        levelEventChannel = new BetterEventChannel(messenger, "com.ryanheise.just_audio.stereo_levels." + id);
    }

    @Override
    public void flush(int sampleRateHz, int channelCount, int encoding) {
        this.channelCount = channelCount;
        this.encoding = encoding;
        sumLeftSquares = 0;
        sumRightSquares = 0;
        sampleCount = 0;
        Log.d(TAG, "flush: sampleRateHz=" + sampleRateHz
            + " channelCount=" + channelCount + " encoding=" + encoding);
    }

    @Override
    public void handleBuffer(ByteBuffer buffer) {
        if (channelCount != 2 || encoding != AudioFormat.ENCODING_PCM_16BIT) {
            return;
        }
        ShortBuffer shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int frameCount = shorts.remaining() / 2;
        for (int i = 0; i < frameCount; i++) {
            double left = shorts.get(i * 2) / 32768.0;
            double right = shorts.get(i * 2 + 1) / 32768.0;
            sumLeftSquares += left * left;
            sumRightSquares += right * right;
        }
        sampleCount += frameCount;

        long now = System.currentTimeMillis();
        if (sampleCount > 0 && now - lastEmitAtMs >= EMIT_INTERVAL_MS) {
            lastEmitAtMs = now;
            double rmsLeft = Math.sqrt(sumLeftSquares / sampleCount);
            double rmsRight = Math.sqrt(sumRightSquares / sampleCount);
            sumLeftSquares = 0;
            sumRightSquares = 0;
            sampleCount = 0;

            final Map<String, Object> event = new HashMap<String, Object>();
            event.put("left", rmsLeft);
            event.put("right", rmsRight);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    levelEventChannel.success(event);
                }
            });

            if (now - lastLogAtMs >= LOG_INTERVAL_MS) {
                lastLogAtMs = now;
                Log.d(TAG, "levels: left=" + rmsLeft + " right=" + rmsRight);
            }
        }
    }

    public void dispose() {
        levelEventChannel.endOfStream();
    }
}
