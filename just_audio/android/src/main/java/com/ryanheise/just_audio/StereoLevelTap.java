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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tap sul PCM decodificato reale (via {@link TeeAudioProcessor}, non
 * {@code android.media.audiofx.Visualizer}): a differenza di
 * {@code Visualizer}, che su questo device forza un downmix mono, qui i due
 * canali restano separati fino al calcolo del livello.
 *
 * Emette due cose, alla stessa cadenza (vedi {@link #EMIT_INTERVAL_MS} e
 * {@link #WAVEFORM_EMIT_INTERVAL_MS}, tenute allineate su richiesta
 * dell'utente — un ritmo più alto per l'oscilloscopio/FFT era stato provato
 * ma risultava percepito come "troppo veloce" rispetto al VU meter):
 * <ul>
 *   <li>RMS lineare (0..1) per canale (VU meter) — evento su
 *       {@code stereo_levels.<id>};
 *   <li>una forma d'onda mono (media L+R) ricampionata a
 *       {@link #WAVEFORM_POINTS} punti fissi — evento su
 *       {@code waveform.<id>}, usata lato Dart sia per un oscilloscopio sia
 *       come input per una FFT (calcolata in Dart, non qui: {@code
 *       WAVEFORM_POINTS} è già una potenza di 2 apposta).
 * </ul>
 *
 * Supporta solo PCM 16 bit stereo per ora (il formato osservato nella
 * pipeline di just_audio per contenuti mp3/flac standard): altri formati
 * vengono ignorati silenziosamente (nessun evento emesso), coerente con
 * l'idea di non bloccare mai la riproduzione per un problema del meter.
 */
@UnstableApi
public class StereoLevelTap implements TeeAudioProcessor.AudioBufferSink {
    private static final String TAG = "StereoLevelTap";
    private static final long EMIT_INTERVAL_MS = 33; // VU meter, ~30Hz
    private static final long WAVEFORM_EMIT_INTERVAL_MS = 33; // oscilloscopio/FFT, allineato al VU meter (~30Hz)
    private static final long LOG_INTERVAL_MS = 1000;
    // Potenza di 2: comoda per una FFT lato Dart sullo stesso buffer, anche
    // se questa classe non la calcola. Cap generoso sull'accumulatore
    // grezzo (~185ms a 44.1kHz) — non dovrebbe mai saturare entro una
    // finestra di emissione di 33ms, il guard in handleBuffer scarta
    // l'eccedenza invece di andare in overflow se mai succedesse.
    private static final int WAVEFORM_POINTS = 256;
    private static final int WAVEFORM_ACCUM_CAP = 8192;

    private final BetterEventChannel levelEventChannel;
    private final BetterEventChannel waveformEventChannel;
    private final float[] waveformAccum = new float[WAVEFORM_ACCUM_CAP];
    private int waveformAccumCount = 0;
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
    private long lastWaveformEmitAtMs = 0;
    private long lastLogAtMs = 0;

    public StereoLevelTap(BinaryMessenger messenger, String id) {
        levelEventChannel = new BetterEventChannel(messenger, "com.ryanheise.just_audio.stereo_levels." + id);
        waveformEventChannel = new BetterEventChannel(messenger, "com.ryanheise.just_audio.waveform." + id);
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
            if (waveformAccumCount < WAVEFORM_ACCUM_CAP) {
                waveformAccum[waveformAccumCount++] = (float) ((left + right) / 2.0);
            }
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

        if (waveformAccumCount > 0 && now - lastWaveformEmitAtMs >= WAVEFORM_EMIT_INTERVAL_MS) {
            lastWaveformEmitAtMs = now;
            final List<Double> waveform = resampleWaveform();
            waveformAccumCount = 0;
            final Map<String, Object> waveformEvent = new HashMap<String, Object>();
            waveformEvent.put("samples", waveform);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    waveformEventChannel.success(waveformEvent);
                }
            });
        }
    }

    /**
     * Ricampiona {@link #waveformAccum} (primi {@link #waveformAccumCount}
     * campioni validi) a esattamente {@link #WAVEFORM_POINTS} punti, per
     * media a bucket (non semplice decimazione): più fedele alla forma
     * d'onda reale, evita di scartare picchi che cadrebbero tra due
     * campioni scelti a caso.
     */
    private List<Double> resampleWaveform() {
        List<Double> result = new ArrayList<Double>(WAVEFORM_POINTS);
        for (int i = 0; i < WAVEFORM_POINTS; i++) {
            int start = (int) ((long) i * waveformAccumCount / WAVEFORM_POINTS);
            int end = (int) ((long) (i + 1) * waveformAccumCount / WAVEFORM_POINTS);
            if (end <= start) end = Math.min(start + 1, waveformAccumCount);
            double sum = 0;
            int n = 0;
            for (int j = start; j < end; j++) {
                sum += waveformAccum[j];
                n++;
            }
            result.add(n > 0 ? sum / n : 0.0);
        }
        return result;
    }

    public void dispose() {
        levelEventChannel.endOfStream();
        waveformEventChannel.endOfStream();
    }
}
