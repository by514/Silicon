package silicon.audio;

import arc.Core;
import arc.Events;
import arc.audio.Sound;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.Streams;
import arc.util.serialization.Json;
import mindustry.game.EventType;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Pattern;

import static mindustry.Vars.player;

/**
 * 音乐播放器核心（本机播放 + 曲目库 + 本地缓存 + 启用开关 + 持久化）。
 * <p>
 * - 曲目库：内置原版音乐（INTERNAL）/ 网络 URL / 本地磁盘路径（LOCAL）
 * - 所有曲目统一解析为本地弧音频 {@link arc.files.Fi} 后经 {@link Sound#createStream} 播放，
 *   从而支持 {@code Sound.at} 的 3D 定位（声源随播放者移动、听者按距离远近衰减）。
 * - 「本地缓存优先复用」：URL / 二进制共享写入 cache/music/，同 cacheHash 直接复用不再走网络。
 * - 「是否启用」总开关（双生效：本机不能播 + 不接收/不听别人）。
 * <p>
 * 本机作为 owner 时，声源原点取玩家自身位置；多人远程声源见 {@link MusicNetwork}，可叠加多个。
 */
public class MusicPlayer {
    public static final int LOOP_OFF = 0, LOOP_LIST = 1, LOOP_ONE = 2;

    private static final String CFG_TRACKS = "musicplayer.tracks";
    private static final String CFG_VOLUME = "musicplayer.volume";
    private static final String CFG_PITCH = "musicplayer.pitch";
    private static final String CFG_LOOP = "musicplayer.loopmode";
    private static final String CFG_ENABLED = "musicplayer.enabled";
    private static final String CFG_LAST = "musicplayer.lastIndex";

    private static final String[] INTERNAL_KEYS = {
        "game1","game2","game3","game4","game5","game6","game7","game8","game9",
        "boss1","boss2","fine","editor","menu","land","launch"
    };

    private static final Pattern EXT_WHITELIST = Pattern.compile("(?i)\\.(ogg|mp3|wav)$");
    private static final String CACHE_DIR = "music/";
    /** 声场衰减参考半径（格）：距离超过该值基本听不见 */
    static final float FALLOFF_RADIUS = 900f;
    /** Sound 3D 定位基础音量与衰减系数 */
    static final float BASE_VOLUME = 0.12f;

    private static final Seq<MusicTrack> tracks = new Seq<>();
    private static final Seq<Voice> voices = new Seq<>();
    private static final Json json = new Json();

    private static int current = -1;
    private static boolean enabled = true;
    private static float volume = 0.8f;
    private static float pitch = 1f;
    private static int loopMode = LOOP_LIST;

    private static boolean playing = false;
    private static int localVoiceId = -1;
    private static float lastBlip = 0;
    private static boolean initialized = false;

    /** 单个活跃声源句柄（本机 owner 或远程，可叠加）。远程按 ownerUuid 区分归属 */
    static class Voice {
        String ownerUuid;      // 远程播放者 uuid；本机本地播放时为 null
        String hash;
        boolean isLocalOwner;
        int voiceId;
        float lastX, lastY;
    }

    /** 远程播放者 owner → 其当前播放曲目 hash（mp-pos 到达时定位声源用） */
    private static final ObjectMap<String, String> ownerHash = new ObjectMap<>();

    static String ownerPlayingHash(String ownerUuid) {
        return ownerHash.get(ownerUuid);
    }

    private MusicPlayer() {}

    // ------------------------------------------------------------------
    // 初始化 / 持久化
    // ------------------------------------------------------------------

    public static void init() {
        if (initialized) return;
        initialized = true;

        enabled = Core.settings.getBool(CFG_ENABLED, true);
        volume = Core.settings.getFloat(CFG_VOLUME, 0.8f);
        pitch = Core.settings.getFloat(CFG_PITCH, 1f);
        loopMode = Core.settings.getInt(CFG_LOOP, LOOP_LIST);
        current = Core.settings.getInt(CFG_LAST, -1);
        loadTracks();

        Events.run(EventType.Trigger.update, MusicPlayer::update);
    }

    private static void loadTracks() {
        tracks.clear();
        String raw = Core.settings.getString(CFG_TRACKS, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                MusicTrack[] arr = json.fromJson(MusicTrack[].class, raw);
                if (arr != null) tracks.addAll(arr);
            } catch (Exception e) {
                tracks.clear();
            }
        }
    }

    private static void saveTracks() {
        Core.settings.put(CFG_TRACKS, json.toJson(tracks, MusicTrack[].class, MusicTrack.class));
    }

    // ------------------------------------------------------------------
    // 启用开关（双生效）
    // ------------------------------------------------------------------

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        Core.settings.put(CFG_ENABLED, enabled);
        if (!enabled) stopAll();
    }

    /** 网络接收侧入口：关闭时调用方可直接丢弃 */
    public static boolean canReceive() {
        return enabled;
    }

    public static void stopAll() {
        stopLocal();
        clearRemoteVoices();
    }

    // ------------------------------------------------------------------
    // 周期更新
    // ------------------------------------------------------------------

    private static void update() {
        if (!initialized || !enabled) return;
        tickLocal();
        refreshVolumes();
    }

    private static void tickLocal() {
        if (!playing || localVoiceId < 0) return;
        // 自然播完检测
        if (Core.audio.isPlaying(localVoiceId)) return;
        // 声音已停止（播完或非循环）→ 推进
        if (Time.time - lastBlip >= 60f) {
            lastBlip = Time.time;
            autoAdvance();
        }
    }

    private static void autoAdvance() {
        if (loopMode == LOOP_ONE) {
            stopLocal();
            beginPlayback(current);
            if (playing) bcast("play");
        } else if (loopMode == LOOP_LIST && tracks.size > 0) {
            if (advanceSafely(1)) bcast("next");
        } else {
            stopLocal();
            bcast("stop");
        }
    }

    private static boolean advanceSafely(int delta) {
        if (tracks.size == 0) return false;
        stopLocal();
        current = ((current + delta) % tracks.size + tracks.size) % tracks.size;
        Core.settings.put(CFG_LAST, current);
        beginPlayback(current);
        return playing;
    }

    /** 各声源按播放者当前位置刷新音量（距离衰减） */
    private static void refreshVolumes() {
        for (Voice v : voices) {
            if (v.voiceId < 0) continue;
            // 本机声源原点跟随玩家（自己永远在声源处 → 恒 0 位移全音量）；
            // 远程声源原点固定在 owner 位置，听者按「自己到 owner」衰减。
            if (v.isLocalOwner) {
                v.lastX = player.x;
                v.lastY = player.y;
            }
            float vol = calcListenVolume(v.lastX - player.x, v.lastY - player.y);
            Core.audio.setVolume(v.voiceId, vol);
        }
    }

    /** 监听者在 (dx,dy) 相对播放者位移处应听到的音量 */
    static float calcListenVolume(float dx, float dy) {
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float factor = clamp(1f - dist / FALLOFF_RADIUS, 0f, 1f);
        return volume * factor * BASE_VOLUME;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ------------------------------------------------------------------
    // 曲目库
    // ------------------------------------------------------------------

    public static Seq<MusicTrack> tracks() {
        return tracks;
    }

    public static int currentIndex() {
        return current;
    }

    public static MusicTrack currentTrack() {
        return (current >= 0 && current < tracks.size) ? tracks.get(current) : null;
    }

    public static MusicTrack trackAt(int index) {
        return (index >= 0 && index < tracks.size) ? tracks.get(index) : null;
    }

    /** 确保内置曲目存在（ClientLoadEvent 时调用，保证 Musics.* 已 load） */
    public static void ensureInternalTracks() {
        for (MusicTrack t : tracks) if (t.isInternal()) return;
        for (String key : INTERNAL_KEYS) {
            tracks.add(new MusicTrack(MusicTrack.INTERNAL, key, key, "int-" + key, "musicplayer.type.internal"));
        }
        saveTracks();
    }

    static int indexOfHash(String hash) {
        for (int i = 0; i < tracks.size; i++) {
            if (hash != null && hash.equals(tracks.get(i).cacheHash)) return i;
        }
        return -1;
    }

    public static MusicTrack trackByHash(String hash) {
        int i = indexOfHash(hash);
        return i >= 0 ? tracks.get(i) : null;
    }

    /** 添加自定义曲目（URL 或本地路径）；成功返回曲目，失败/重复返回对应曲目或 null */
    public static MusicTrack addTrack(int type, String source, String name) {
        if (source == null || source.trim().isEmpty()) return null;
        if (type != MusicTrack.URL && type != MusicTrack.LOCAL) return null;
        String src = source.trim();
        if (!EXT_WHITELIST.matcher(src).find()) return null;
        String hash = Strings.bytesToHex(sha256(src)).substring(0, 16);
        int dup = indexOfHash(hash);
        if (dup >= 0) return tracks.get(dup);
        String displayName = (name != null && !name.isEmpty()) ? name
                : (src.contains("/") ? src.substring(src.lastIndexOf('/') + 1) : src);
        MusicTrack t = new MusicTrack(type, displayName, src, hash,
                type == MusicTrack.URL ? "musicplayer.type.url" : "musicplayer.type.local");
        tracks.add(t);
        saveTracks();
        return t;
    }

    /** SHA-256 摘要（16 字节 → 32 hex 字符）供缓存 hash 使用 */
    private static byte[] sha256(String src) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return md.digest(src.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new byte[16];
        }
    }

    public static void removeTrack(int index) {
        if (index < 0 || index >= tracks.size) return;
        if (current == index) stopLocal();
        if (current > index) current--;
        tracks.remove(index);
        saveTracks();
    }

    // ------------------------------------------------------------------
    // 本地播放（播放器控制按钮针对本机 owner 声源）
    // ------------------------------------------------------------------

    public static boolean isPlaying() {
        return playing;
    }

    public static int currentVoiceId() {
        return localVoiceId;
    }

    public static void play(int index) {
        if (!enabled) return;
        if (index < 0 || index >= tracks.size) return;
        stopLocal();
        current = index;
        Core.settings.put(CFG_LAST, current);
        beginPlayback(current);
        if (playing) bcast("play");
    }

    public static void play() {
        if (current >= 0) play(current);
    }

    private static void beginPlayback(int index) {
        MusicTrack t = tracks.get(index);
        Fi file = resolveToPlayableFile(t);
        if (file == null || !file.exists()) {
            if (t != null && t.isUrl()) {
                // 本机 URL 曲目尚未下载：先下载到缓存，完成后在主线程重播
                final int target = index;
                MusicNetwork.fetchLocalThenPlay(t, () -> {
                    if (current == target && !playing) beginPlayback(target);
                });
                return;
            }
            SiliconLog.log("Cannot resolve " + (t == null ? "?" : t.name) + " to a local file");
            return;
        }
        try {
            Sound snd = Sound.createStream(file);
            int id = snd.at(player.x, player.y, calcListenVolume(0f, 0f), 0f);
            Core.audio.setLooping(id, loopMode == LOOP_ONE);
            Core.audio.setPitch(id, pitch);
            localVoiceId = id;
            playing = true;
            lastBlip = Time.time;
            unregisterLocalVoice();
            Voice v = new Voice();
            v.hash = t.cacheHash;
            v.isLocalOwner = true;
            v.voiceId = id;
            v.lastX = player.x;
            v.lastY = player.y;
            voices.add(v);
        } catch (Exception e) {
            SiliconLog.log("Failed to play " + t.name + ": " + e.getMessage());
            playing = false;
            localVoiceId = -1;
        }
    }

    private static void unregisterLocalVoice() {
        for (int i = voices.size - 1; i >= 0; i--) {
            if (voices.get(i).isLocalOwner) voices.remove(i);
        }
    }

    public static void toggle() {
        if (playing) pause();
        else resume();
    }

    public static void pause() {
        if (localVoiceId >= 0) Core.audio.setPaused(localVoiceId, true);
        playing = false;
        bcast("pause");
    }

    public static void resume() {
        if (!enabled) return;
        if (playing) return;
        if (localVoiceId >= 0) {
            Core.audio.setPaused(localVoiceId, false);
            playing = true;
            lastBlip = Time.time;
            bcast("resume");
        } else if (current >= 0) {
            beginPlayback(current);
            if (playing) bcast("play");
        }
    }

    public static void stopLocal() {
        if (localVoiceId >= 0) {
            Core.audio.stop(localVoiceId);
            localVoiceId = -1;
        }
        playing = false;
        unregisterLocalVoice();
    }

    /** 公开停止：停止本地并广播给其他玩家 */
    public static void stop() {
        stopLocal();
        bcast("stop");
    }

    public static void setVolume(float v) {
        volume = clamp(v, 0f, 1f);
        Core.settings.put(CFG_VOLUME, volume);
        if (localVoiceId >= 0) Core.audio.setVolume(localVoiceId, calcListenVolume(0f, 0f));
        refreshVolumes();
    }

    public static float volume() {
        return volume;
    }

    public static void setPitch(float p) {
        pitch = p;
        Core.settings.put(CFG_PITCH, pitch);
        if (localVoiceId >= 0) Core.audio.setPitch(localVoiceId, pitch);
        for (Voice v : voices) {
            if (v.voiceId >= 0) Core.audio.setPitch(v.voiceId, pitch);
        }
    }

    public static float pitch() {
        return pitch;
    }

    public static void setLoopMode(int mode) {
        loopMode = mode;
        Core.settings.put(CFG_LOOP, loopMode);
        if (localVoiceId >= 0) Core.audio.setLooping(localVoiceId, loopMode == LOOP_ONE);
    }

    public static int loopMode() {
        return loopMode;
    }

    public static void cycleLoopMode() {
        setLoopMode((loopMode + 1) % 3);
    }

    public static void next() {
        if (!enabled || tracks.size == 0) return;
        if (advanceSafely(1)) bcast("next");
    }

    public static void prev() {
        if (!enabled || tracks.size == 0) return;
        if (advanceSafely(-1)) bcast("next");
    }

    /** 广播本机播放状态变化给其他玩家（经 MusicNetwork） */
    private static void bcast(String op) {
        if (!initialized) return;
        MusicNetwork.notifyLocalChanged(op);
    }

    // ------------------------------------------------------------------
    // 曲目解析为可播放 Fi（本地缓存优先复用）
    // ------------------------------------------------------------------

    /** 解析为可播放的本地 Fi：内置→jar 内 music/；URL/LOCAL→命中缓存用缓存，否则返回 null 由网络层补齐 */
    public static Fi resolveToPlayableFile(MusicTrack t) {
        if (t == null) return null;
        if (t.isInternal()) {
            arc.audio.Music m = internalMusic(t.source);
            return m == null ? null : m.file;
        }
        Fi cached = cacheFileOf(t);
        if (cached != null && cached.exists()) return cached;
        if (t.isLocal()) {
            Fi abs = Core.files.absolute(t.source);
            if (abs.exists()) return abs;
            Fi loc = Core.files.local(t.source);
            if (loc.exists()) return loc;
            return null;
        }
        return null; // URL 未缓存由网络层下载
    }

    private static arc.audio.Music internalMusic(String key) {
        try {
            java.lang.reflect.Field f = mindustry.gen.Musics.class.getField(key);
            return (arc.audio.Music) f.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 本地缓存
    // ------------------------------------------------------------------

    public static Fi cacheFileOf(MusicTrack t) {
        return Core.files.cache(CACHE_DIR + t.cacheHash + ".ogg");
    }

    public static boolean hasCache(String hash) {
        return Core.files.cache(CACHE_DIR + hash + ".ogg").exists();
    }

    public static Fi cacheFileForHash(String hash) {
        return Core.files.cache(CACHE_DIR + hash + ".ogg");
    }

    public static String extensionFrom(String source) {
        int dot = source.lastIndexOf('.');
        if (dot >= 0) {
            String e = source.substring(dot).toLowerCase();
            if (e.equals(".mp3")) return ".mp3";
            if (e.equals(".wav")) return ".wav";
        }
        return ".ogg";
    }

    /** 写缓存（URL 下载 / 二进制共享落地统一走这里）；扩展名用 ogg 以便 Sound.createStream 识别 */
    static boolean writeCacheBytes(String hash, byte[] data) {
        try {
            Fi file = Core.files.cache(CACHE_DIR + hash + ".ogg");
            try (OutputStream out = file.write(false)) {
                out.write(data);
            }
            return true;
        } catch (Exception e) {
            SiliconLog.log("Cache write fail " + hash + ": " + e.getMessage());
            return false;
        }
    }

    /** 从输入流写缓存（URL 分块下载用） */
    static boolean writeCacheStream(String hash, InputStream in) {
        try {
            Fi file = Core.files.cache(CACHE_DIR + hash + ".ogg");
            try (InputStream src = in; OutputStream out = file.write(false)) {
                Streams.copy(src, out);
            }
            return true;
        } catch (Exception e) {
            SiliconLog.log("Cache stream fail " + hash + ": " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 远程声源（由 MusicNetwork 调用，可叠加多个；按 ownerUuid 区分归属）
    // ------------------------------------------------------------------

    /** 播一个远程声源。调用方需保证本地已可解析（内置/已缓存/已下载）。 */
    static void playRemoteVoice(String ownerUuid, String hash, float ownerX, float ownerY) {
        if (!enabled) return;
        // 同 owner 已在播则仅刷新位置
        for (Voice v : voices) {
            if (!v.isLocalOwner && ownerUuid.equals(v.ownerUuid)) {
                v.lastX = ownerX;
                v.lastY = ownerY;
                Core.audio.setVolume(v.voiceId, calcListenVolume(ownerX - player.x, ownerY - player.y));
                Core.audio.setPaused(v.voiceId, false);
                return;
            }
        }
        MusicTrack t = trackByHash(hash);
        Fi file = null;
        if (t != null) {
            file = resolveToPlayableFile(t);
        }
        // 接收端可能没有对应曲目记录（如本地文件二进制共享），但缓存已存在 → 直接按缓存播
        if ((t == null || file == null || !file.exists()) && hasCache(hash)) {
            file = cacheFileForHash(hash);
        }
        if (file == null || !file.exists()) {
            SiliconLog.log("Remote play: no local file for " + hash);
            return; // 尚未下载/尚未拿到二进制，等下载完成后由网络层再次调用
        }
        try {
            Sound snd = Sound.createStream(file);
            int id = snd.at(ownerX, ownerY, calcListenVolume(ownerX - player.x, ownerY - player.y), 0f);
            Core.audio.setLooping(id, false);
            Core.audio.setPitch(id, pitch);
            Voice v = new Voice();
            v.ownerUuid = ownerUuid;
            v.hash = hash;
            v.isLocalOwner = false;
            v.voiceId = id;
            v.lastX = ownerX;
            v.lastY = ownerY;
            voices.add(v);
            ownerHash.put(ownerUuid, hash);
        } catch (Exception e) {
            SiliconLog.log("Remote play fail: " + e.getMessage());
        }
    }

    /** 刷新某远程声源位置（收到 mp-pos 时调用） */
    static void updateRemotePosition(String ownerUuid, float x, float y) {
        for (Voice v : voices) {
            if (!v.isLocalOwner && ownerUuid.equals(v.ownerUuid)) {
                v.lastX = x;
                v.lastY = y;
                Core.audio.setVolume(v.voiceId, calcListenVolume(x - player.x, y - player.y));
                return;
            }
        }
    }

    /** 暂停某远程声源（收到 mp-sync pause 时调用） */
    static void pauseRemoteVoice(String ownerUuid) {
        for (Voice v : voices) {
            if (!v.isLocalOwner && ownerUuid.equals(v.ownerUuid) && v.voiceId >= 0) {
                Core.audio.setPaused(v.voiceId, true);
            }
        }
    }

    /** 继续某远程声源（收到 mp-sync resume 时调用） */
    static void resumeRemoteVoice(String ownerUuid) {
        for (Voice v : voices) {
            if (!v.isLocalOwner && ownerUuid.equals(v.ownerUuid) && v.voiceId >= 0) {
                Core.audio.setPaused(v.voiceId, false);
            }
        }
    }

    /** 停止某远程声源（收到 mp-sync stop/next 时调用） */
    static void stopRemoteVoice(String ownerUuid) {
        for (int i = voices.size - 1; i >= 0; i--) {
            Voice v = voices.get(i);
            if (!v.isLocalOwner && ownerUuid.equals(v.ownerUuid)) {
                if (v.voiceId >= 0) Core.audio.stop(v.voiceId);
                voices.remove(i);
            }
        }
        ownerHash.remove(ownerUuid);
    }

    public static void clearRemoteVoices() {
        for (int i = voices.size - 1; i >= 0; i--) {
            Voice v = voices.get(i);
            if (!v.isLocalOwner) {
                if (v.voiceId >= 0) Core.audio.stop(v.voiceId);
                voices.remove(i);
            }
        }
        ownerHash.clear();
    }

    private static final class SiliconLog {
        static void log(String msg) {
            Log.info("[SiliconMusic] " + msg);
        }
    }
}
