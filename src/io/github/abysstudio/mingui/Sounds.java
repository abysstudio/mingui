package io.github.abysstudio.mingui;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small synthesized sound effects (no audio files). Off unless turned on in Settings. */
final class Sounds {
    private static final int RATE = 44100;
    private static final ExecutorService PLAYER = Executors.newSingleThreadExecutor();

    private Sounds() {}

    static boolean on(Context c) {
        return Routine.prefs(c).getBoolean("sounds", false);
    }

    static void setOn(Context c, boolean on) {
        Routine.prefs(c).edit().putBoolean("sounds", on).apply();
    }

    /** Habit checked: a bright two-note chime. */
    static void tick(Context c) {
        play(c, new float[][]{{1318.5f, 70}, {1568f, 160}});
    }

    /** Habit unchecked: one soft low note. */
    static void untick(Context c) {
        play(c, new float[][]{{659.3f, 110}});
    }

    /** Routine complete: rising arpeggio. */
    static void celebrate(Context c) {
        play(c, new float[][]{{523.3f, 110}, {659.3f, 110}, {784f, 110}, {1046.5f, 380}});
    }

    /** Buddy level up: quick sparkly run. */
    static void levelUp(Context c) {
        play(c, new float[][]{{784f, 70}, {1046.5f, 70}, {1318.5f, 70}, {1568f, 300}});
    }

    /** Equip / small UI delight. */
    static void pop(Context c) {
        play(c, new float[][]{{987.8f, 60}, {1480f, 90}});
    }

    private static void play(Context c, final float[][] notes) {
        if (!on(c)) return;
        PLAYER.execute(() -> {
            int total = 0;
            for (float[] n : notes) total += (int) (RATE * n[1] / 1000f);
            int tail = RATE / 5; // let the last note ring out
            short[] pcm = new short[total + tail];
            int pos = 0;
            for (int k = 0; k < notes.length; k++) {
                float f = notes[k][0];
                int len = (int) (RATE * notes[k][1] / 1000f) + (k == notes.length - 1 ? tail : 0);
                for (int i = 0; i < len && pos + i < pcm.length; i++) {
                    double t = (double) i / RATE;
                    double env = Math.min(1, t / 0.004) * Math.exp(-t * 9); // fast attack, bell-like decay
                    double v = Math.sin(2 * Math.PI * f * t) + 0.25 * Math.sin(4 * Math.PI * f * t);
                    int idx = pos + i;
                    pcm[idx] = (short) Math.max(-32767, Math.min(32767, pcm[idx] + v * env * 0.3 * 32767));
                }
                pos += (int) (RATE * notes[k][1] / 1000f);
            }
            try {
                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(pcm.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                track.write(pcm, 0, pcm.length);
                track.play();
                Thread.sleep(pcm.length * 1000L / RATE + 50);
                track.release();
            } catch (Exception ignored) {
                // Sound is a nicety; never let it crash anything.
            }
        });
    }
}
