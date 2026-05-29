// --- FILE: src/main/java/com/leaf/game/core/AudioManager.java ---
package com.leaf.game.core;

import com.leaf.game.util.Camera;
import org.joml.Vector3f;
import org.lwjgl.openal.*;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.openal.EXTEfx.*;

/**
 * OpenAL-backed audio engine.
 *
 * ── Why OpenAL (and why a single audio thread) ────────────────────────────────
 * OpenAL is a real game-audio engine: hardware/driver low-latency playback, a
 * reusable pool of "sources" (no per-event allocation, no line-exhaustion like
 * javax.sound), native 3-D positioning + Doppler, and — via the EFX extension —
 * reverb and filtering for "hearing environments".
 *
 * An OpenAL context must be driven from one thread at a time. We therefore own a
 * SINGLE dedicated daemon thread that performs every AL call. Public methods just
 * enqueue a tiny command onto that thread and return immediately, so the game
 * loop NEVER blocks on audio. Commands run in FIFO order, which also guarantees
 * preload() finishes before any play() that was requested after it.
 *
 * ── Public API (unchanged from the old Clip implementation) ───────────────────
 *   warmup()                         – initialise the engine (call once at boot)
 *   preload(name)                    – decode a file into an AL buffer
 *   play(name) / play(name, vol)     – fire-and-forget 2-D one-shot
 *   playAt(name, pos, cam, range)    – positional 3-D one-shot
 *   playContinuous(name[, vol])      – start a loop
 *   stopContinuous(name)             – stop a loop
 *   setContinuousVolume(name, vol)   – live-update a loop's volume
 *
 * Files are read from /audios/&lt;name&gt;.wav (preferred) or .mp3. They are decoded
 * to 16-bit PCM via the Java Sound SPI, then uploaded once to an OpenAL buffer.
 *
 * NOTE: 3-D positioning only affects MONO sounds — OpenAL plays stereo buffers
 * "flat" (no attenuation/pan). Keep positional SFX mono.
 */
public class AudioManager {

    // ── Tunables ────────────────────────────────────────────────────────────
    private static final int POOL_SIZE = 28;   // simultaneous one-shot voices

    // ── The single audio thread ───────────────────────────────────────────────
    private static final ExecutorService AUDIO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "openal-audio");
        t.setDaemon(true);
        return t;
    });

    // ── AL handles (only touched on the audio thread) ─────────────────────────
    private static long device  = 0L;
    private static long context = 0L;
    private static boolean ready = false;

    private static int[] pool;                                  // one-shot voices
    private static final Map<String, Integer> buffers    = new HashMap<>(); // name → AL buffer
    private static final Map<String, Integer> buffersM   = new HashMap<>(); // mono-downmixed buffers (for loop panning)
    private static final Map<String, Integer> loops       = new HashMap<>(); // name → looping source
    private static final Map<String, Float>   loopBase    = new HashMap<>(); // name → requested gain
    private static final java.util.Set<String> duckExempt = new java.util.HashSet<>(); // loops immune to ducking

    private static float masterGain = 1.0f;
    private static float duckGain   = 1.0f;  // 1 = no duck, lowers non-exempt loops

    // Debug telemetry (read with getDebugStats()).
    private static volatile long voicesPlayed = 0L;
    private static volatile long voicesStolen = 0L;

    // ── EFX (reverb + muffle) ─────────────────────────────────────────────────
    public static final int ENV_NONE = 0, ENV_CAVE = 1, ENV_HALL = 2, ENV_UNDERWATER = 3;
    private static boolean efx = false;
    private static int auxSlot, reverbEffect, lowpass;
    private static float muffleAmount = 0f;

    private static volatile boolean initStarted = false;

    // ── Decoded PCM holder ────────────────────────────────────────────────────
    private record Pcm(int channels, int sampleRate, byte[] data) {}

    // ── Init ────────────────────────────────────────────────────────────────

    /** Idempotently kick off engine initialisation on the audio thread. */
    private static synchronized void ensureInit() {
        if (initStarted) return;
        initStarted = true;
        AUDIO.submit(AudioManager::initAL);
    }

    private static void initAL() {
        try {
            device = alcOpenDevice((ByteBuffer) null);
            if (device == 0L) { System.err.println("[Audio] No OpenAL device."); return; }

            ALCCapabilities alcCaps = ALC.createCapabilities(device);
            context = alcCreateContext(device, (java.nio.IntBuffer) null);
            if (context == 0L) { System.err.println("[Audio] Could not create OpenAL context."); return; }
            alcMakeContextCurrent(context);
            AL.createCapabilities(alcCaps);

            // Physically-based distance falloff for positional sounds.
            alDistanceModel(AL11.AL_INVERSE_DISTANCE_CLAMPED);

            // Listener defaults (overwritten each frame once the game wires it up).
            alListener3f(AL_POSITION, 0f, 0f, 0f);
            alListener3f(AL_VELOCITY, 0f, 0f, 0f);
            float[] ori = {0f, 0f, -1f, 0f, 1f, 0f}; // forward = -Z, up = +Y
            alListenerfv(AL_ORIENTATION, ori);

            initEfx(); // reverb + low-pass, if the driver supports it

            // One-shot voice pool.
            pool = new int[POOL_SIZE];
            for (int i = 0; i < POOL_SIZE; i++) {
                int s = alGenSources();
                alSourcef(s, AL_GAIN, 1f);
                alSourcei(s, AL_SOURCE_RELATIVE, AL_TRUE); // default 2-D (head-relative)
                routeSource(s);
                pool[i] = s;
            }

            ready = true;
        } catch (Throwable t) {
            System.err.println("[Audio] OpenAL init failed: " + t.getMessage());
        }
    }

    /** Call once during game init. Prepares the OpenAL engine. */
    public static void warmup() {
        ensureInit();
    }

    // ── EFX setup (audio thread only) ─────────────────────────────────────────

    private static void initEfx() {
        try {
            efx = alcIsExtensionPresent(device, "ALC_EXT_EFX");
            if (!efx) { System.out.println("[Audio] EFX not available — running dry."); return; }

            auxSlot      = alGenAuxiliaryEffectSlots();
            reverbEffect = alGenEffects();
            alEffecti(reverbEffect, AL_EFFECT_TYPE, AL_EFFECT_REVERB);

            lowpass = alGenFilters();
            alFilteri(lowpass, AL_FILTER_TYPE, AL_FILTER_LOWPASS);
            alFilterf(lowpass, AL_LOWPASS_GAIN,   1f);
            alFilterf(lowpass, AL_LOWPASS_GAINHF, 1f);

            applyEnvironment(ENV_NONE);
        } catch (Throwable t) {
            efx = false;
            System.err.println("[Audio] EFX init failed: " + t.getMessage());
        }
    }

    /** Configure the reverb effect + wet level for a named environment. */
    private static void applyEnvironment(int env) {
        if (!efx) return;
        // density, diffusion, gain, gainHF, decayTime, wetLevel(slot gain)
        float density, diffusion, gain, gainHF, decay, wet;
        switch (env) {
            case ENV_CAVE       -> { density=1f;   diffusion=1f;   gain=0.32f; gainHF=0.55f; decay=2.9f; wet=0.65f; }
            case ENV_HALL       -> { density=1f;   diffusion=0.9f; gain=0.32f; gainHF=0.80f; decay=1.6f; wet=0.45f; }
            case ENV_UNDERWATER -> { density=0.4f; diffusion=1f;   gain=0.30f; gainHF=0.10f; decay=1.5f; wet=0.55f; }
            default /* NONE */  -> { density=1f;   diffusion=1f;   gain=0.32f; gainHF=0.89f; decay=1.0f; wet=0.0f;  }
        }
        alEffectf(reverbEffect, AL_REVERB_DENSITY,     density);
        alEffectf(reverbEffect, AL_REVERB_DIFFUSION,   diffusion);
        alEffectf(reverbEffect, AL_REVERB_GAIN,        gain);
        alEffectf(reverbEffect, AL_REVERB_GAINHF,      gainHF);
        alEffectf(reverbEffect, AL_REVERB_DECAY_TIME,  decay);
        alAuxiliaryEffectSloti(auxSlot, AL_EFFECTSLOT_EFFECT, reverbEffect);
        alAuxiliaryEffectSlotf(auxSlot, AL_EFFECTSLOT_GAIN, wet);
    }

    /** Attach the reverb send + (if muffled) the low-pass to a source. */
    private static void routeSource(int s) {
        if (!efx) return;
        AL11.alSource3i(s, AL_AUXILIARY_SEND_FILTER, auxSlot, 0, AL_FILTER_NULL);
        alSourcei(s, AL_DIRECT_FILTER, muffleAmount > 0f ? lowpass : AL_FILTER_NULL);
    }

    private static void rerouteAll() {
        if (!efx) return;
        if (pool != null) for (int s : pool) routeSource(s);
        for (int s : loops.values()) routeSource(s);
    }

    // ── Loading ────────────────────────────────────────────────────────────

    private static InputStream openStream(String name) {
        InputStream is = AudioManager.class.getResourceAsStream("/audios/" + name + ".wav");
        if (is == null) is = AudioManager.class.getResourceAsStream("/audios/" + name + ".mp3");
        return is;
    }

    /** Decode a sound file to 16-bit PCM (handles WAV, and MP3 if an SPI codec is present). */
    private static Pcm decode(String name) {
        try {
            InputStream raw = openStream(name);
            if (raw == null) { System.err.println("[Audio] Missing file: " + name); return null; }

            AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(raw));
            AudioFormat fmt = ais.getFormat();

            // Normalise to little-endian signed 16-bit PCM (what OpenAL expects).
            if (fmt.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                    || fmt.getSampleSizeInBits() != 16
                    || fmt.isBigEndian()) {
                AudioFormat target = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        fmt.getSampleRate(), 16, fmt.getChannels(),
                        fmt.getChannels() * 2, fmt.getSampleRate(), false);
                ais = AudioSystem.getAudioInputStream(target, ais);
                fmt = target;
            }
            byte[] pcm = ais.readAllBytes();
            return new Pcm(fmt.getChannels(), (int) fmt.getSampleRate(), pcm);
        } catch (Exception e) {
            System.err.println("[Audio] Decode failed for '" + name + "': " + e.getMessage());
            return null;
        }
    }

    /** Decode + upload a sound to an OpenAL buffer (runs on the audio thread). */
    private static Integer ensureBuffer(String name) {
        Integer existing = buffers.get(name);
        if (existing != null) return existing;

        Pcm pcm = decode(name);
        if (pcm == null) return null;

        int format = (pcm.channels() == 1) ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
        ByteBuffer bb = MemoryUtil.memAlloc(pcm.data().length);
        bb.put(pcm.data()).flip();
        int buffer = alGenBuffers();
        alBufferData(buffer, format, bb, pcm.sampleRate());
        MemoryUtil.memFree(bb);

        buffers.put(name, buffer);
        return buffer;
    }

    /** Mix a stereo 16-bit PCM down to mono by averaging L+R samples. */
    private static Pcm downmixToMono(Pcm p) {
        if (p.channels() == 1) return p;
        byte[] src = p.data();
        int frames = src.length / 4; // 2 channels × 2 bytes
        byte[] dst = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            int o = i * 4;
            short l = (short)(((src[o+1] & 0xFF) << 8) | (src[o]   & 0xFF));
            short r = (short)(((src[o+3] & 0xFF) << 8) | (src[o+2] & 0xFF));
            int mono = (l + r) >> 1;
            dst[i*2]   = (byte)(mono       & 0xFF);
            dst[i*2+1] = (byte)((mono >> 8) & 0xFF);
        }
        return new Pcm(1, p.sampleRate(), dst);
    }

    /** Like ensureBuffer but always produces a mono AL buffer — required for loop panning. */
    private static Integer ensureBufferMono(String name) {
        Integer existing = buffersM.get(name);
        if (existing != null) return existing;
        Pcm raw = decode(name);
        if (raw == null) return null;
        Pcm mono = downmixToMono(raw);
        ByteBuffer bb = MemoryUtil.memAlloc(mono.data().length);
        bb.put(mono.data()).flip();
        int buf = alGenBuffers();
        alBufferData(buf, AL_FORMAT_MONO16, bb, mono.sampleRate());
        MemoryUtil.memFree(bb);
        buffersM.put(name, buf);
        return buf;
    }

    /** Decode a sound once at startup so the first play has no IO cost. */
    public static void preload(String name) {
        ensureInit();
        AUDIO.submit(() -> { if (ready) ensureBuffer(name); });
    }

    // ── Voice acquisition ──────────────────────────────────────────────────

    /** Grab a free one-shot source, or steal the quietest one if all are busy. */
    private static int acquireSource() {
        voicesPlayed++;
        for (int s : pool) {
            if (alGetSourcei(s, AL_SOURCE_STATE) != AL_PLAYING) return s;
        }
        // All busy → steal the quietest voice (least important: distant/faded).
        int best = pool[0];
        float bestGain = Float.MAX_VALUE;
        for (int s : pool) {
            float g = alGetSourcef(s, AL_GAIN);
            if (g < bestGain) { bestGain = g; best = s; }
        }
        voicesStolen++;
        alSourceStop(best);
        return best;
    }

    /** Recompute a loop's gain from its base × master × (duck unless exempt). */
    private static void applyLoopGain(String name) {
        Integer s = loops.get(name);
        if (s == null) return;
        float base = loopBase.getOrDefault(name, 1f);
        float duck = duckExempt.contains(name) ? 1f : duckGain;
        alSourcef(s, AL_GAIN, base * masterGain * duck);
    }

    // ── One-shots ────────────────────────────────────────────────────────────

    public static void play(String name) { play(name, 1.0f, 1.0f); }

    public static void play(String name, float volume) { play(name, volume, 1.0f); }

    /**
     * One-shot with explicit pitch (1.0 = normal). Pitch < 1 lowers, > 1 raises.
     * OpenAL pitch-shifts in real time — something javax.sound could never do.
     */
    public static void play(String name, float volume, float pitch) {
        ensureInit();
        final float vol = Math.max(0f, volume);
        final float pit = Math.max(0.1f, pitch);
        AUDIO.submit(() -> {
            if (!ready) return;
            Integer buf = ensureBuffer(name);
            if (buf == null) return;
            int s = acquireSource();
            alSourcei(s, AL_BUFFER, 0);                 // detach previous
            alSourcei(s, AL_LOOPING, AL_FALSE);
            alSourcei(s, AL_SOURCE_RELATIVE, AL_TRUE);  // 2-D, no attenuation
            alSource3f(s, AL_POSITION, 0f, 0f, 0f);
            alSource3f(s, AL_VELOCITY, 0f, 0f, 0f);
            alSourcef(s, AL_PITCH, pit);
            alSourcef(s, AL_GAIN, vol * masterGain);
            alSourcei(s, AL_BUFFER, buf);
            routeSource(s);
            alSourcePlay(s);
        });
    }

    /**
     * One-shot with subtle random pitch + volume jitter so repeated events
     * (footsteps, block breaks, hits) never sound machine-gunned.
     * Typical: variance 0.12 → ±12% pitch, ±8% volume.
     */
    public static void playVaried(String name, float baseVolume, float variance) {
        float pitch = 1f + (float) (Math.random() * 2 - 1) * variance;
        float vol   = baseVolume * (1f - (float) Math.random() * variance * 0.6f);
        play(name, vol, pitch);
    }

    public static void playVaried(String name) { playVaried(name, 1.0f, 0.12f); }

    /** 3-D positional one-shot. Attenuates/pans with distance from the listener. */
    public static void playAt(String name, Vector3f sourcePos, Camera camera, float maxRange) {
        playAt(name, sourcePos, (Vector3f) null, maxRange);
    }

    /**
     * 3-D positional one-shot with an optional source velocity for the Doppler
     * effect (approaching sources rise in pitch, receding ones fall — handled
     * natively by OpenAL from the source + listener velocities).
     */
    public static void playAt(String name, Vector3f sourcePos, Vector3f sourceVel, float maxRange) {
        ensureInit();
        final float px = sourcePos.x, py = sourcePos.y, pz = sourcePos.z;
        final float vx = sourceVel != null ? sourceVel.x : 0f;
        final float vy = sourceVel != null ? sourceVel.y : 0f;
        final float vz = sourceVel != null ? sourceVel.z : 0f;
        AUDIO.submit(() -> {
            if (!ready) return;
            Integer buf = ensureBuffer(name);
            if (buf == null) return;
            int s = acquireSource();
            alSourcei(s, AL_BUFFER, 0);
            alSourcei(s, AL_LOOPING, AL_FALSE);
            alSourcei(s, AL_SOURCE_RELATIVE, AL_FALSE); // 3-D, world-positioned
            alSource3f(s, AL_POSITION, px, py, pz);
            alSource3f(s, AL_VELOCITY, vx, vy, vz);
            alSourcef(s, AL_PITCH, 1f);
            alSourcef(s, AL_GAIN, masterGain);
            alSourcef(s, AL_REFERENCE_DISTANCE, Math.max(1f, maxRange * 0.15f));
            alSourcef(s, AL_MAX_DISTANCE, maxRange);
            alSourcef(s, AL_ROLLOFF_FACTOR, 1f);
            alSourcei(s, AL_BUFFER, buf);
            routeSource(s);
            alSourcePlay(s);
        });
    }

    // ── Listener (the player's "ears") ───────────────────────────────────────

    /**
     * Update the listener every frame so 3-D sounds pan/attenuate correctly and
     * Doppler works. Pass the player's world velocity for Doppler; pass zero if
     * you don't track it yet.
     */
    public static void updateListener(Camera camera, Vector3f velocity) {
        if (camera == null) return;
        final float px = camera.position.x, py = camera.position.y, pz = camera.position.z;
        Vector3f f = camera.getLookDirection();
        final float fx = f.x, fy = f.y, fz = f.z;
        final float vx = velocity != null ? velocity.x : 0f;
        final float vy = velocity != null ? velocity.y : 0f;
        final float vz = velocity != null ? velocity.z : 0f;
        ensureInit();
        AUDIO.submit(() -> {
            if (!ready) return;
            alListener3f(AL_POSITION, px, py, pz);
            alListener3f(AL_VELOCITY, vx, vy, vz);
            alListenerfv(AL_ORIENTATION, new float[]{fx, fy, fz, 0f, 1f, 0f});
        });
    }

    /** Doppler intensity (0 = off, 1 = realistic, >1 = exaggerated arcade feel). */
    public static void setDopplerFactor(float factor) {
        ensureInit();
        final float ff = Math.max(0f, factor);
        AUDIO.submit(() -> { if (ready) AL10.alDopplerFactor(ff); });
    }

    // ── Hearing environments ──────────────────────────────────────────────────

    /** Switch the reverb character: ENV_NONE / ENV_CAVE / ENV_HALL / ENV_UNDERWATER. */
    public static void setEnvironment(int env) {
        ensureInit();
        AUDIO.submit(() -> { if (efx) applyEnvironment(env); });
    }

    /**
     * Muffle everything you hear (0 = clear, 1 = heavily damped) — for being
     * underwater, behind a wall, or the wind-rush of fast flight. Rolls off the
     * high frequencies via a listener-wide low-pass.
     */
    public static void setListenerMuffle(float amount) {
        ensureInit();
        final float a = Math.max(0f, Math.min(1f, amount));
        AUDIO.submit(() -> {
            if (!efx) return;
            muffleAmount = a;
            alFilterf(lowpass, AL_LOWPASS_GAIN,   1f - 0.30f * a);  // 1.0 → 0.70
            alFilterf(lowpass, AL_LOWPASS_GAINHF, 1f - 0.95f * a);  // 1.0 → 0.05
            rerouteAll();
        });
    }

    // ── Continuous loops ───────────────────────────────────────────────────

    public static void playContinuous(String name) { playContinuous(name, 1.0f); }

    public static void playContinuous(String name, float volume) {
        ensureInit();
        final float vol = Math.max(0f, volume);
        AUDIO.submit(() -> {
            if (!ready || loops.containsKey(name)) return;
            Integer buf = ensureBufferMono(name); // mono so setLoopPan (AL_POSITION) actually pans
            if (buf == null) return;
            int s = alGenSources();                     // dedicated source (never stolen)
            alSourcei(s, AL_LOOPING, AL_TRUE);
            alSourcei(s, AL_SOURCE_RELATIVE, AL_TRUE);
            alSource3f(s, AL_POSITION, 0f, 0f, 0f);
            alSourcef(s, AL_ROLLOFF_FACTOR, 0f);        // panning without distance attenuation
            alSourcef(s, AL_PITCH, 1f);
            alSourcei(s, AL_BUFFER, buf);
            routeSource(s);
            loops.put(name, s);
            loopBase.put(name, vol);
            applyLoopGain(name);
            alSourcePlay(s);
        });
    }

    public static void stopContinuous(String name) {
        ensureInit();
        AUDIO.submit(() -> {
            Integer s = loops.remove(name);
            loopBase.remove(name);
            if (s != null) { alSourceStop(s); alDeleteSources(s); }
        });
    }

    public static void setContinuousVolume(String name, float volume) {
        ensureInit();
        final float vol = Math.max(0f, volume);
        AUDIO.submit(() -> {
            if (!ready) return;
            loopBase.put(name, vol);
            if (!loops.containsKey(name)) {
                // Not yet playing → start it at this volume (matches old behaviour).
                Integer buf = ensureBufferMono(name); // mono for panning
                if (buf == null) return;
                int src = alGenSources();
                alSourcei(src, AL_LOOPING, AL_TRUE);
                alSourcei(src, AL_SOURCE_RELATIVE, AL_TRUE);
                alSource3f(src, AL_POSITION, 0f, 0f, 0f);
                alSourcef(src, AL_ROLLOFF_FACTOR, 0f);   // panning without distance attenuation
                alSourcei(src, AL_BUFFER, buf);
                routeSource(src);
                loops.put(name, src);
                applyLoopGain(name);
                alSourcePlay(src);
            } else {
                applyLoopGain(name);
            }
        });
    }

    /**
     * Pan a looping source left/right relative to the listener.
     * panX = 0 → centred, +1 → hard right, −1 → hard left.
     * Only affects the horizontal pan; distance attenuation is disabled on
     * loop sources so the volume stays constant regardless of pan position.
     * Silently ignored if the loop hasn't started yet.
     */
    public static void setLoopPan(String name, float panX) {
        ensureInit();
        final float px = Math.max(-1f, Math.min(1f, panX));
        AUDIO.submit(() -> {
            if (!ready) return;
            Integer s = loops.get(name);
            if (s != null) alSource3f(s, AL_POSITION, px, 0f, 0f);
        });
    }

    // ── Global controls ──────────────────────────────────────────────────────

    /** Master volume 0..1, applied on top of every per-sound gain. */
    public static void setMasterGain(float g) {
        ensureInit();
        final float gg = Math.max(0f, g);
        AUDIO.submit(() -> {
            masterGain = gg;
            if (ready) for (String n : loops.keySet()) applyLoopGain(n);
        });
    }

    /**
     * Duck (lower) all looping sounds except those marked exempt, e.g. dip
     * ambient beds while wind dominates during fast flight. 1 = no duck, 0 = silent.
     */
    public static void setDuck(float gain) {
        ensureInit();
        final float gg = Math.max(0f, Math.min(1f, gain));
        AUDIO.submit(() -> {
            duckGain = gg;
            if (ready) for (String n : loops.keySet()) applyLoopGain(n);
        });
    }

    /** Mark a loop (e.g. "wind_bed") immune to ducking so it stays at full level. */
    public static void setDuckExempt(String name, boolean exempt) {
        ensureInit();
        AUDIO.submit(() -> {
            if (exempt) duckExempt.add(name); else duckExempt.remove(name);
            applyLoopGain(name);
        });
    }

    /** Debug line: how many voices have played and how many had to be stolen. */
    public static String getDebugStats() {
        return "voices played=" + voicesPlayed + " stolen=" + voicesStolen
                + " activeLoops=" + loops.size();
    }

    /** Release all OpenAL resources. Optional — daemon thread dies with the JVM. */
    public static void shutdown() {
        AUDIO.submit(() -> {
            if (pool != null) for (int s : pool) alDeleteSources(s);
            for (int s : loops.values()) { alSourceStop(s); alDeleteSources(s); }
            for (int b : buffers.values()) alDeleteBuffers(b);
            loops.clear(); buffers.clear();
            if (efx) {
                alDeleteAuxiliaryEffectSlots(auxSlot);
                alDeleteEffects(reverbEffect);
                alDeleteFilters(lowpass);
            }
            if (context != 0L) { alcMakeContextCurrent(0L); alcDestroyContext(context); }
            if (device != 0L) alcCloseDevice(device);
            ready = false;
        });
        AUDIO.shutdown();
    }
}
