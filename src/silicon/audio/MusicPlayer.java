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
    public static final int LOOP_OFF = 0, LOOP_LIST = 1, LOOP_ONE = 2, LOOP_SHUFFLE = 3, LOOP_ONE_STOP = 4;

    private static final String CFG_TRACKS = "musicplayer.tracks";
    private static final String CFG_VOLUME = "musicplayer.volume";
    private static final String CFG_PITCH = "musicplayer.pitch";
    private static final String CFG_LOOP = "musicplayer.loopmode";
    private static final String CFG_ENABLED = "musicplayer.enabled";
    private static final String CFG_LAST = "musicplayer.lastIndex";
    private static final String CFG_ALBUMS = "musicplayer.albums";
    private static final String CFG_ALBUM = "musicplayer.album";
    private static final String CFG_SPEED = "musicplayer.speed";
    private static final String CFG_AB_A = "musicplayer.ab.a";
    private static final String CFG_AB_B = "musicplayer.ab.b";

    private static final String[] INTERNAL_KEYS = {
        "game1","game2","game3","game4","game5","game6","game7","game8","game9",
        "boss1","boss2","fine","editor","menu","land","launch"
    };

    private static final Pattern EXT_WHITELIST = Pattern.compile("(?i)\\.(ogg|mp3|wav|flac|m4a|wma|aac|opus)$");
    /** Soloud 内置解码器可解码的格式（stb_vorbis=ogg / dr_mp3=mp3 / stb_wav=wav）。flac/m4a/wma/aac/opus 只能做字节共享，不能在本机解码播放（会原生崩溃） */
    private static final Pattern DECODABLE_EXT = Pattern.compile("(?i)\\.(ogg|mp3|wav)$");
    /** 时长探测的文件大小上限（字节）：超过则跳过 Music.create 解码读取，避免大文件解码卡顿/占用过多内存 */
    private static final long LENGTH_PROBE_SIZE_LIMIT = 64L * 1024 * 1024;
    /** 倍速最小值（对数滑杆 1/16–16x） */
    public static final float MIN_SPEED = 1f / 16f;
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
    /** 倍速（相对播放速率 0.1–16x）；与音高叠加为实际 Soloud 速率 pitch*speed */
    private static float speed = 1f;
    private static int loopMode = LOOP_LIST;

    /** A-B 区间两点（秒）；<0 表示未设置。两点按 min/max 取区间，可实现区间重复 */
    private static float abA = -1f;
    private static float abB = -1f;

    /** 专辑：一组曲目（存放曲目 cacheHash 引用），可按专辑整体播放 */
    public static class Album {
        public String name;
        public Seq<String> hashes = new Seq<>();
        public Album() {}
        public Album(String name) { this.name = name; }
    }

    private static final Seq<Album> albums = new Seq<>();
    /** 当前激活的专辑名（null = 全部曲目）；决定「上一首/下一首」在专辑内切换并限定循环范围 */
    private static String activeAlbum = null;

    private static boolean playing = false;
    private static int localVoiceId = -1;
    private static float lastBlip = 0;
    /** 暂停时保存的进度（秒）；恢复播放时 seek 回该位置 */
    private static float pausedPosition = 0f;
    /** 游戏自身暂停（ESC 菜单）期间：Mindustry 会静音本播声源，但流式声源内部仍在计时 → 进度虚进。
     *  为 true 时显示进度被冻结在 pausedPosition，待游戏恢复时 seek 回该处保持进度与音频一致 */
    private static boolean pausedByGame = false;
    /** 已解析文件绝对路径 → 时长（秒）缓存，避免反复用 Music.create 读取耗时 */
    private static final ObjectMap<String, Float> lengthCache = new ObjectMap<>();
    private static boolean autoAdvancing = false;
    /** 最近一次 seek 的游戏内时间（秒）；seek 后给 Soloud 一段恢复期，防止流式声源跳转瞬间被误判为「已播完」而跳歌 */
    private static float lastSeekAt = -1000f;
    /** 上一帧本地位置（秒），用于识别 LOOP_ONE 原生循环在曲末回绕到 0 的进度回退（区分于手动拖动 seek） */
    private static float lastPos = 0f;
    private static boolean initialized = false;
    /** 上一帧游戏是否处于暂停（ESC）态的边沿记忆，用于在此版本无 pause 事件时轮询检测 */
    private static boolean prevGamePaused = false;
    /** 待应用的恢复进度（秒）；<0 表示无。resume 后不立即 idSeek（新流式声源可能未就绪，实测即时 seek 会原生崩溃），
     *  推迟到声源确认存活（isPlaying 且在 beginPlayback 后经过 0.3s）再应用 */
    private static float pendingResumeSeek = -1f;

    /** 当前本机声源是否曾确认进入播放态（用于区分「自然播完可推进」与「新声源启动即失败」：
     *  后者不得静默跳到别的曲目（曾因误判把本地曲跳成内置曲），应停播并留日志 */
    private static boolean voiceEverPlayed = false;
    /** 随机循环（LOOP_SHUFFLE）的播放顺序（tracks 索引）；进入随机模式或曲目库/作用域变化时重建 */
    private static final Seq<Integer> shuffleOrder = new Seq<>();
    private static boolean shuffleDirty = true;

    /** 声源结束检测的静默阈值（秒）：自然播完后等待该时长再推进下一首，避免新声源流式加载未就绪时重复推进 */
    private static final float ADVANCE_DELAY = 0.5f;

    /** 单个活跃声源句柄（本机 owner 或远程，可叠加）。远程按 ownerUuid 区分归属 */
    static class Voice {
        String ownerUuid;      // 远程播放者 uuid；本机本地播放时为 null
        String hash;
        boolean isLocalOwner;
        int voiceId;
        float lastX, lastY;
        float createdAt;       // 创建时刻（秒），用于判断远程声源是否已足够久可安全清理
        Sound sound;           // 对应 createStream 的 Sound，停止/清理时需 dispose 释放原生 Soloud 源
    }

    /** 停止并释放一个声源的播放与原生 Sound 句柄 */
    private static void disposeVoice(Voice v) {
        if (v == null) return;
        if (v.voiceId >= 0) {
            try {
                Core.audio.stop(v.voiceId);
            } catch (Exception ignored) {
            }
            v.voiceId = -1;
        }
        if (v.sound != null) {
            try {
                v.sound.dispose();
            } catch (Exception ignored) {
            }
            v.sound = null;
        }
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
        speed = Core.settings.getFloat(CFG_SPEED, 1f);
        loopMode = Core.settings.getInt(CFG_LOOP, LOOP_LIST);
        current = Core.settings.getInt(CFG_LAST, -1);
        abA = Core.settings.getFloat(CFG_AB_A, -1f);
        abB = Core.settings.getFloat(CFG_AB_B, -1f);
        loadTracks();
        loadAlbums();
        String alb = Core.settings.getString(CFG_ALBUM, "");
        activeAlbum = (alb == null || alb.isEmpty()) ? null : alb;

        Events.run(EventType.Trigger.update, MusicPlayer::update);
    }

    private static void loadAlbums() {
        albums.clear();
        String raw = Core.settings.getString(CFG_ALBUMS, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                Album[] arr = json.fromJson(Album[].class, raw);
                if (arr != null) albums.addAll(arr);
            } catch (Exception e) {
                albums.clear();
            }
        }
    }

    private static void saveAlbums() {
        Core.settings.put(CFG_ALBUMS, json.toJson(albums, Album[].class, Album.class));
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
    // 专辑（曲目分组）
    // ------------------------------------------------------------------

    /** 全部专辑（含未命名曲目的默认组返回 null → 表示全部曲目） */
    public static Seq<Album> albums() {
        return albums;
    }

    public static Album album(int index) {
        return (index >= 0 && index < albums.size) ? albums.get(index) : null;
    }

    public static void addAlbum(String name) {
        if (name == null || name.trim().isEmpty()) return;
        albums.add(new Album(name.trim()));
        saveAlbums();
    }

    public static void removeAlbum(int index) {
        if (index < 0 || index >= albums.size) return;
        albums.remove(index);
        saveAlbums();
        if (activeAlbum != null && !existsAlbum(activeAlbum)) activeAlbum = null;
    }

    private static boolean existsAlbum(String name) {
        for (Album a : albums) if (name.equals(a.name)) return true;
        return false;
    }

    public static void addToAlbum(int albumIndex, int trackIndex) {
        Album a = album(albumIndex);
        if (a == null || trackIndex < 0 || trackIndex >= tracks.size) return;
        a.hashes.addAll(tracks.get(trackIndex).cacheHash);
        saveAlbums();
    }

    public static void removeFromAlbum(int albumIndex, int trackIndex) {
        Album a = album(albumIndex);
        if (a == null || trackIndex < 0 || trackIndex >= tracks.size) return;
        a.hashes.remove(tracks.get(trackIndex).cacheHash);
        saveAlbums();
    }

    /** 当前激活专辑名（null = 全部曲目） */
    public static String activeAlbum() {
        return activeAlbum;
    }

    public static void setActiveAlbum(String name) {
        activeAlbum = (name == null || name.isEmpty()) ? null : name;
        Core.settings.put(CFG_ALBUM, activeAlbum == null ? "" : activeAlbum);
        shuffleDirty = true; // 作用域变化 → 随机播放顺序需重建
        // 当前曲目不再属于激活专辑 → 跳到该专辑第一首（若有），否则停止
        MusicTrack cur = currentTrack();
        if (activeAlbum != null && cur != null && !albumContainsByName(activeAlbum, cur.cacheHash)) {
            int first = firstTrackOfAlbum(activeAlbum);
            if (first >= 0) play(first);
            else stop();
        }
    }

    /** 指定专辑内曲目当前顺序（按曲目库序）的索引列表 */
    public static int[] albumTrackIndices(String albumName) {
        Seq<Integer> out = new Seq<>();
        for (int i = 0; i < tracks.size; i++) {
            if (albumName != null && !albumContainsByName(albumName, tracks.get(i).cacheHash)) continue;
            out.add(i);
        }
        int[] res = new int[out.size];
        for (int i = 0; i < out.size; i++) res[i] = out.get(i);
        return res;
    }

    private static int firstTrackOfAlbum(String albumName) {
        int[] ind = albumTrackIndices(albumName);
        return ind.length > 0 ? ind[0] : -1;
    }

    private static boolean albumContainsByName(String albumName, String hash) {
        for (Album a : albums) {
            if (!albumName.equals(a.name)) continue;
            if (a.hashes.contains(hash)) return true;
        }
        return false;
    }

    /** 激活专辑的首/末曲索引；null 专辑（全部曲目）返还 true */
    private static int[] currentScope() {
        if (activeAlbum == null) return null;
        return albumTrackIndices(activeAlbum);
    }

    // ------------------------------------------------------------------
    // 启用开关（双生效）
    // ------------------------------------------------------------------

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        Core.settings.put(CFG_ENABLED, enabled);
        if (!enabled) {
            stopAll();
            // 通知其他玩家停止听到本机的曲目（否则对方仍按旧声源播我上一条）
            bcast("stop");
        }
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
        if (player == null) return;
        // 游戏暂停（ESC）边沿检测：进入时冻结进度，恢复时 seek 回冻结点。
        // 此前 pausedByGame 从未被置 true（死代码）→ 暂停期间进度条虚进，从未真正对齐。
        boolean gp = isGamePaused();
        if (gp && !prevGamePaused) {
            pausedByGame = true;
            if (localVoiceId >= 0) pausedPosition = currentTime();
        } else if (!gp && prevGamePaused) {
            pausedByGame = false;
            if (localVoiceId >= 0 && pausedPosition > 0f) {
                // 同样走延迟 seek：暂停期间声源可能已自然播完被 autoAdvance 重建（新声源立刻 seek 会原生崩溃）
                deferSeek(pausedPosition);
                pausedPosition = 0f;
            }
        }
        prevGamePaused = gp;
        tickLocal();
        refreshVolumes();
    }

    private static boolean isGamePaused() {
        return mindustry.Vars.state != null && mindustry.Vars.state.isPaused();
    }

    private static void tickLocal() {
        if (!playing || localVoiceId < 0) return;
        // 恢复播放的延迟 seek：新声源确认存活（isPlaying 且 beginPlayback 后已过 0.3s）才应用，
        // 避开「刚建声源立刻 idSeek」的原生崩溃窗口（见 resume() 注释）
        if (pendingResumeSeek >= 0f && Core.audio.isPlaying(localVoiceId) && Time.time - lastBlip >= 0.3f) {
            float s = pendingResumeSeek;
            pendingResumeSeek = -1f;
            seek(s);
        }
        // A-B 区间循环：进度到达 B 点（hi）后回转到 A 点（lo），实现区间重复
        if (hasAb()) {
            float pos = currentTime();
            float lo = Math.min(abA, abB);
            float hi = Math.max(abA, abB);
            if (pos >= hi) {
                seek(lo);
            } else if (Time.time - lastSeekAt >= 1.0f && lastPos - pos > 0.5f) {
                // LOOP_ONE 原生循环在曲末无痕回绕到 0：位置相对上帧明显回退，且非手动拖动后刚发生，
                // 说明整曲被原生层重启，把播放无缝拉回区间起点，避免每圈先播一段 [0,lo) 再进区间
                seek(lo);
            }
            lastPos = currentTime();
        }
        // 仍在播放（含暂停态，暂停时 Soloud 的 id 仍有效）→ 复位推进守卫
        if (Core.audio.isPlaying(localVoiceId)) {
            autoAdvancing = false;
            voiceEverPlayed = true;
            return;
        }
        // 声音已结束（非循环播完或已 stop）
        if (autoAdvancing) return; // 上一条刚触发推进，等新声源就绪，避免重复推进/跳曲
        if (Time.time - lastSeekAt < 1.0f) return; // seek 后声源可能瞬时未就绪，误判已播完会跳歌
        if (Time.time - lastBlip < ADVANCE_DELAY) return; // 新声源流式加载未就绪的静默窗口
        if (!voiceEverPlayed) {
            // 静默窗口过后仍从未进入播放态 → 启动即失败（解码不支持/缓存损坏/文件缺失）。
            // 不自动跳到别的曲目（此前会静默推进成「内置歌曲」），停播并留日志便于排查。
            MusicTrack cur = currentTrack();
            Log.warn("Playback failed to start, stopped without auto-skip: " + (cur == null ? "?" : cur.name));
            stopLocal();
            return;
        }
        autoAdvancing = true;
        lastBlip = Time.time;
        autoAdvance();
    }

    private static void autoAdvance() {
        switch (loopMode) {
            case LOOP_ONE:
                stopLocal();
                beginPlayback(current);
                if (playing) bcast("play");
                break;
            case LOOP_LIST:
            case LOOP_SHUFFLE:
                if (advanceSafely(1)) bcast("next");
                break;
            default: // LOOP_OFF / LOOP_ONE_STOP：自然播完即停
                stopLocal();
                bcast("stop");
        }
    }

    private static boolean advanceSafely(int delta) {
        // 激活专辑时只在专辑内切换；否则全曲目库切换
        int[] scope = currentScope();
        int size = (scope == null) ? tracks.size : scope.length;
        if (size == 0) return false;
        // 当前曲目在当前作用域内的位置；若不在（如新增曲目/换专辑）则从头
        int pos = 0;
        if (current >= 0) {
            if (scope == null) pos = current;
            else {
                for (int i = 0; i < scope.length; i++) if (scope[i] == current) { pos = i; break; }
            }
        }
        pausedPosition = 0f;
        clearAb();
        stopLocal();
        int nextPos;
        if (loopMode == LOOP_SHUFFLE) {
            int[] order = ensureShuffleOrder();
            size = order.length;
            if (size == 0) return false;
            int cur = -1;
            for (int i = 0; i < order.length; i++) if (order[i] == current) { cur = i; break; }
            int nxt = cur < 0 ? (delta > 0 ? 0 : order.length - 1) : ((cur + delta) % size + size) % size;
            nextPos = order[nxt];
        } else {
            int nxt = ((pos + delta) % size + size) % size;
            nextPos = (scope == null) ? nxt : scope[nxt];
        }
        MusicTrack from = currentTrack();
        current = nextPos;
        Core.settings.put(CFG_LAST, current);
        beginPlayback(current);
        MusicTrack to = currentTrack();
        if (from != to) {
            Log.info("Track transition: " + (from == null ? "?" : from.name) + " -\u003e " + (to == null ? "?" : to.name));
        }
        return playing;
    }

    /** 随机播放顺序（当前作用域内索引）；dirty 或尺寸不符时重建（打乱）。返回数组便于遍历 */
    private static int[] ensureShuffleOrder() {
        int[] scope = currentScope();
        int size = (scope == null) ? tracks.size : scope.length;
        if (shuffleDirty || shuffleOrder.size != size) {
            java.util.ArrayList<Integer> tmp = new java.util.ArrayList<>();
            for (int i = 0; i < size; i++) tmp.add(scope == null ? i : scope[i]);
            java.util.Collections.shuffle(tmp);
            shuffleOrder.clear();
            shuffleOrder.addAll(tmp);
            shuffleDirty = false;
        }
        int[] out = new int[shuffleOrder.size];
        for (int i = 0; i < out.length; i++) out[i] = shuffleOrder.get(i);
        return out;
    }

    /** 各声源按播放者当前位置刷新音量（距离衰减），并清理已自然播完的远程声源 */
    private static void refreshVolumes() {
        for (int i = voices.size - 1; i >= 0; i--) {
            Voice v = voices.get(i);
            if (v.voiceId < 0) { disposeVoice(v); voices.remove(i); continue; }
            // 远程非循环声源自然播完后 Soloud 会释放 id → 清理（含释放原生 Sound），防止 voices 无限累积
            if (!v.isLocalOwner && !Core.audio.isPlaying(v.voiceId) && Time.time - v.createdAt > 2f) {
                disposeVoice(v);
                voices.remove(i);
                continue;
            }
            // 本机声源原点跟随玩家（自己永远在声源处 → 恒 0 位移全音量）；
            // 远程声源原点固定在 owner 位置，听者按「自己到 owner」衰减。
            if (v.isLocalOwner) {
                v.lastX = player.x;
                v.lastY = player.y;
            }
            // 本机恒在声源处 → 全音量；远程才做距离衰减。修复：本机音量不再乘以 0.12 的 BASE_VOLUME，避免几乎听不见。
            float vol = v.isLocalOwner ? volume : calcListenVolume(v.lastX - player.x, v.lastY - player.y);
            float pan = calcListenPan(v.lastX);
            // set(voiceId, pan, volume) 同时更新左右声像与音量（arc 中第2参为 pan、第3参为 volume；
            // 反向传入会把 pan 当 volume，声源在屏幕中央(pan≈0)时音量≈0 → 听不到声音）
            Core.audio.set(v.voiceId, pan, vol);
        }
    }

    /** 监听者在 (dx,dy) 相对播放者位移处应听到的音量 */
    static float calcListenVolume(float dx, float dy) {
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float factor = clamp(1f - dist / FALLOFF_RADIUS, 0f, 1f);
        return volume * factor * BASE_VOLUME;
    }

    /** 按声源在世界 x 相对监听视角（相机）的水平偏移计算左右声像（-0.9 左 … 0.9 右） */
    static float calcListenPan(float wx) {
        if (Core.camera == null) return 0f;
        float half = Core.camera.width / 2f;
        if (half <= 0f) return 0f;
        return clamp((wx - Core.camera.position.x) / half, -0.9f, 0.9f);
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

    /** 内置曲目 key 列表（供 UI 内置曲目选择器使用） */
    public static String[] internalKeys() {
        return INTERNAL_KEYS;
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
        shuffleDirty = true; // 曲目库变化 → 随机播放顺序需重建
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
        String hash = tracks.get(index).cacheHash;
        if (current == index) stopLocal();
        if (current > index) current--;
        tracks.remove(index);
        saveTracks();
        // 同步清理专辑中对该曲目的引用，避免孤悬 hash
        for (Album a : albums) a.hashes.remove(hash);
        saveAlbums();
        shuffleDirty = true; // 曲目库变化 → 随机播放顺序需重建
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
        pausedPosition = 0f;
        clearAb();
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
        // flac/m4a/wma/aac/opus 等 Soloud 无内置解码器：直接创建声源会原生崩溃 → 阻止并在日志说明（仅排除本机解码，仍可分享字节给他人）
        if (t != null && (t.isUrl() || t.isLocal()) && !isDecodablePath(t.source)) {
            Log.warn("Blocked play of undecodable " + t.name + " (Soloud only decodes ogg/mp3/wav)");
            playing = false;
            localVoiceId = -1;
            return;
        }
        Fi file = resolveToPlayableFile(t);
        if (file == null || !file.exists()) {
            if (t != null && t.isUrl()) {
                // 本机 URL 曲目尚未下载：先下载到缓存，完成后在主线程重播
                final int target = index;
                MusicNetwork.fetchLocalThenPlay(t, () -> {
                    if (current == target && !playing) {
                        beginPlayback(target);
                        if (playing) bcast("play");
                    }
                });
                return;
            }
            SiliconLog.log("Cannot resolve " + (t == null ? "?" : t.name) + " to a local file");
            return;
        }
        // 内置曲目是 jar 打包资源，无真实磁盘路径，Soloud 无法流式读取 → 先提取为真实缓存文件
        if (t.isInternal()) {
            file = extractInternalToRealFile(file, t.source);
            if (file == null) {
                SiliconLog.log("Cannot extract internal track " + t.name);
                return;
            }
        }
        Sound snd = null;
        try {
            if (!isAsciiPath(file.absolutePath())) {
                SiliconLog.log("Block playback of non-ASCII path: " + file.name());
                return;
            }
            snd = Sound.createStream(file);
            int id = snd.play(volume, pitch * speed, 0f);
            Core.audio.setLooping(id, loopMode == LOOP_ONE);
            localVoiceId = id;
            playing = true;
            voiceEverPlayed = false; // 新声源尚未确认进入播放态前不推进
            lastBlip = Time.time;
            lastPos = 0f; // 新播放从 0 起算，避免上一首的残留位置触发 A-B 回绕误判
            unregisterLocalVoice();
            Voice v = new Voice();
            v.hash = t.cacheHash;
            v.isLocalOwner = true;
            v.voiceId = id;
            v.sound = snd;
            v.lastX = player.x;
            v.lastY = player.y;
            v.createdAt = Time.time;
            voices.add(v);
        } catch (Exception e) {
            // 播放失败时释放刚创建的原生 Sound，避免泄漏
            if (snd != null) {
                try {
                    snd.dispose();
                } catch (Exception ignored) {
                }
            }
            SiliconLog.log("Failed to play " + t.name + ": " + e.getMessage());
            playing = false;
            localVoiceId = -1;
        }
    }

    private static void unregisterLocalVoice() {
        for (int i = voices.size - 1; i >= 0; i--) {
            if (voices.get(i).isLocalOwner) {
                disposeVoice(voices.get(i));
                voices.remove(i);
            }
        }
    }

    public static void toggle() {
        if (playing) pause();
        else resume();
    }

    public static void pause() {
        if (!playing) return;
        if (localVoiceId >= 0) pausedPosition = currentTime();
        // 流式声源的 setPaused 并不总能真的静音（音乐仍会继续），故暂停实现为：
        // 真正 stop 本地声源保证不再出声，并记录进度，供恢复时 seek 回原位。
        stopLocal();
        bcast("pause");
    }

    public static void resume() {
        if (!enabled) return;
        if (playing) return;
        if (current >= 0) {
            float seekTo = pausedPosition;
            pausedPosition = 0f;
            beginPlayback(current);
            if (playing && seekTo > 0.05f) {
                // 恢复播放通常要 seek 到暂停位置，但刚创建的新流式声源可能尚未就绪；
                // 实测此时立刻 idSeek 会在原生 arc64.dll 崩溃（Soloud 内部锁断言，见 hs_err_pid*）。
                // 推迟到声源确认存活后应用（tickLocal 内 pendingResumeSeek 处理）。
                deferSeek(seekTo);
            }
            if (playing) bcast(seekTo > 0.05f ? "resume" : "play");
        }
    }

    /** 记录待应用的恢复进度并夹取在曲末前 0.5s 内（秒）；由 tickLocal 在声源确认存活后应用。
     *  统一通道：resume() 与游戏暂停恢复都用它，避免对"刚建/可能重建"的新声源同步 idSeek 触发原生崩溃 */
    private static void deferSeek(float seconds) {
        float len = trackLength();
        pendingResumeSeek = Math.min(seconds, len > 0f ? Math.max(0f, len - 0.5f) : seconds);
    }

    public static void stopLocal() {
        pendingResumeSeek = -1f;
        voiceEverPlayed = false;
        if (localVoiceId >= 0) {
            Core.audio.stop(localVoiceId);
            localVoiceId = -1;
        }
        playing = false;
        unregisterLocalVoice();
    }

    /** 公开停止：停止本地并广播给其他玩家 */
    public static void stop() {
        pausedPosition = 0f;
        stopLocal();
        bcast("stop");
    }

    public static void setVolume(float v) {
        volume = clamp(v, 0f, 1f);
        Core.settings.put(CFG_VOLUME, volume);
        // 统一交给 refreshVolumes 按本地/远程口径应用（含 pan），避免重复 native 调用
        refreshVolumes();
    }

    public static float volume() {
        return volume;
    }

    public static void setPitch(float p) {
        pitch = p;
        Core.settings.put(CFG_PITCH, pitch);
        applyRate();
    }

    public static float pitch() {
        return pitch;
    }

    /** 当前倍速 */
    public static float speed() {
        return speed;
    }

    /** 设置倍速（1/16–16x）；与音高叠加，实际速率 = pitch * speed */
    public static void setSpeed(float s) {
        speed = clamp(s, MIN_SPEED, 16f);
        Core.settings.put(CFG_SPEED, speed);
        applyRate();
    }

    /** 把「音高 * 倍速」的实际速率应用到本机与所有远程声源 */
    private static void applyRate() {
        float eff = pitch * speed;
        if (localVoiceId >= 0) Core.audio.setPitch(localVoiceId, eff);
        for (Voice v : voices) {
            if (v.voiceId >= 0) Core.audio.setPitch(v.voiceId, eff);
        }
    }

    // ------------------------------------------------------------------
    // A-B 区间（区间重复）
    // ------------------------------------------------------------------

    public static float abA() {
        return abA;
    }

    public static float abB() {
        return abB;
    }

    /** 是否已设置有效 A-B 区间（两点均已设且相距 > 0.3s） */
    public static boolean hasAb() {
        return abA >= 0f && abB >= 0f && Math.abs(abB - abA) > 0.3f;
    }

    /** 设 A 点（秒）；B 已设为更早位置时不强制顺序，由 hasAb 用 min/max 处理 */
    public static void setAbA(float seconds) {
        abA = seconds < 0f ? -1f : seconds;
        Core.settings.put(CFG_AB_A, abA);
    }

    /** 设 B 点（秒） */
    public static void setAbB(float seconds) {
        abB = seconds < 0f ? -1f : seconds;
        Core.settings.put(CFG_AB_B, abB);
    }

    /** 清除 A-B 区间 */
    public static void clearAb() {
        abA = -1f;
        abB = -1f;
        Core.settings.put(CFG_AB_A, -1f);
        Core.settings.put(CFG_AB_B, -1f);
    }

    /** 切换 A-B 区间：无区间则设置，有则清除（悬浮条/设置按钮快捷开关） */
    public static void toggleAb() {
        if (hasAb()) clearAb();
        else {
            if (!playing || localVoiceId < 0) return;
            float pos = currentTime();
            // 已设 A 未设 B → 设 B
            if (abA >= 0f && abB < 0f) setAbB(pos);
            else setAbA(pos); // 未设或已设 B 未设 A → 重新设 A
        }
    }

    // ------------------------------------------------------------------
    // 播放进度 / 拖动选进度
    // ------------------------------------------------------------------

    /** 当前播放进度（秒）；本地未播放返回 0。游戏自身暂停（ESC）期间流式源虚进而音频被静音 → 返回冻结的进度 */
    public static float currentTime() {
        if (pausedByGame) return pausedPosition;
        if (localVoiceId >= 0) return SoloudBridge.getPosition(localVoiceId);
        // 播放器暂停后 localVoiceId=-1，返回保存的进度
        return pausedPosition > 0f ? pausedPosition : 0f;
    }

    /** 当前本地曲目总时长（秒）；未知/未播放返回 -1 */
    public static float trackLength() {
        if (localVoiceId >= 0) {
            for (Voice v : voices) {
                if (v.isLocalOwner && v.sound != null) return v.sound.getLength();
            }
        }
        if (current >= 0) return trackLengthOf(tracks.get(current));
        return -1f;
    }

    /** 指定曲目时长（秒）；未知返回 -1。本地曲目优先读原始文件（避免无谓的全文件异步缓存拷贝造成卡顿），
     *  非 ASCII 路径读取失败时回退到已有 ASCII 缓存（若无则不拷贝，返回 -1，播放后将填充）。结果按路径缓存。 */
    public static float trackLengthOf(MusicTrack t) {
        if (t == null) return -1f;
        // 内部曲目：必须提取为真实磁盘文件才能读取时长
        if (t.isInternal()) {
            Fi f = extractInternalToRealFile(resolveToPlayableFile(t), t.source);
            return f == null ? -1f : readLengthFrom(f);
        }
        if (t.isUrl()) {
            Fi f = resolveToPlayableFile(t);
            return f == null ? -1f : readLengthFrom(f);
        }
        // 本地曲目：直接读原始文件（长度/大小都不需要拷贝）；路径 ASCII 也满足
        Fi orig = originalLocalFile(t);
        float len = orig == null ? -1f : readLengthFrom(orig);
        // 非 ASCII 原始路径读取失败 → 回退已有 ASCII 缓存（不做新拷贝）
        if (len <= 0f) {
            Fi cached = cacheFileOf(t);
            if (cached != null && cached.exists()) len = readLengthFrom(cached);
        }
        return len;
    }

    /** 从指定文件读取时长（秒）；失败返回 -1，结果按文件路径缓存。
     *  注意：Soloud 的 Music.create 对含非 ASCII（如中文）路径会原生 fopen 失败并可能使音频内部锁不一致 → 直接跳过，避免崩溃 */
    private static float readLengthFrom(Fi f) {
        if (f == null || !f.exists()) return -1f;
        String key = f.absolutePath();
        if (!isAsciiPath(key)) return -1f; // 非 ASCII 路径不读（防 Soloud 内部锁崩溃），由 ASCII 缓存补齐
        if (!isDecodablePath(key)) return -1f; // flac/m4a/wma/aac/opus 无 Soloud 解码器，探测会原生失败 → 跳过
        if (f.length() > LENGTH_PROBE_SIZE_LIMIT) return -1f; // 超大文件解码耗时/占内存，仅显示大小
        Float cached = lengthCache.get(key);
        if (cached != null) return cached;
        float len = -1f;
        try {
            arc.audio.Music m = arc.audio.Music.create(f);
            try {
                len = m.getLength();
            } finally {
                m.dispose();
            }
        } catch (Exception ignored) {
        }
        lengthCache.put(key, len);
        return len;
    }

    /** 路径扩展名是否可由 Soloud 内置解码器解码（ogg/mp3/wav） */
    private static boolean isDecodablePath(String path) {
        if (path == null) return false;
        return DECODABLE_EXT.matcher(path).find();
    }

    /** 路径是否全为 ASCII（可安全交给 Soloud 原生 fopen） */
    private static boolean isAsciiPath(String path) {
        if (path == null) return false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c > 0x7f) return false;
        }
        return true;
    }

    /** 指定曲目本地文件大小（字节）；不可用时返回 -1。本地曲目直接读原始文件，不触发缓存拷贝 */
    public static long trackSizeOf(MusicTrack t) {
        if (t == null) return -1L;
        if (t.isInternal()) {
            Fi f = extractInternalToRealFile(resolveToPlayableFile(t), t.source);
            return f != null && f.exists() ? f.length() : -1L;
        }
        if (t.isLocal()) {
            Fi orig = originalLocalFile(t);
            if (orig != null && orig.exists()) return orig.length();
            Fi c = cacheFileOf(t);
            return c != null && c.exists() ? c.length() : -1L;
        }
        Fi f = resolveToPlayableFile(t);
        return f != null && f.exists() ? f.length() : -1L;
    }

    /** 解析本地曲目的原始源文件（不做任何缓存拷贝）；不存在返回 null */
    public static Fi originalLocalFile(MusicTrack t) {
        if (t == null || !t.isLocal() || t.source == null) return null;
        Fi abs = Core.files.absolute(t.source);
        if (abs.exists()) return abs;
        Fi loc = Core.files.local(t.source);
        return loc.exists() ? loc : null;
    }

    /** 相对当前进度增减（秒）：暂停态/游戏暂停态改保存进度，播放态 seek 并夹取在范围内 */
    public static void seekRelative(float delta) {
        float len = trackLength();
        float pos = currentTime() + delta;
        if (len > 0f) pos = arc.math.Mathf.clamp(pos, 0f, Math.max(0f, len - 0.5f));
        else pos = Math.max(0f, pos);
        seek(pos);
    }

    /** 拖动到指定进度（秒）：播放中直接 seek 流式声源；播放器暂停时只改保存进度（恢复后从该处继续） */
    public static void seek(float seconds) {
        if (seconds < 0f) seconds = 0f;
        // 夹取在轨道末尾前 0.5 秒内，防止 seek 到末尾导致流立即结束触发跳歌
        float len = trackLength();
        if (len > 0f) seconds = Math.min(seconds, Math.max(0f, len - 0.5f));
        pausedPosition = seconds;
        lastSeekAt = Time.time;
        if (localVoiceId >= 0) SoloudBridge.seek(localVoiceId, seconds);
    }

    public static void setLoopMode(int mode) {
        loopMode = mode;
        Core.settings.put(CFG_LOOP, loopMode);
        if (localVoiceId >= 0) Core.audio.setLooping(localVoiceId, loopMode == LOOP_ONE);
        if (loopMode == LOOP_SHUFFLE) {
            shuffleDirty = true; // 进入随机模式时重建顺序（含当前曲目首次进入的起点）
        } else {
            shuffleOrder.clear();
        }
    }

    public static int loopMode() {
        return loopMode;
    }

    public static void cycleLoopMode() {
        setLoopMode((loopMode + 1) % 5);
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

    /** 地图切换后若本机仍在播放，重广播当前曲目给新地图玩家（供 MusicNetwork.reset 调用），防远端失去声源 */
    static void reBroadcastIfPlaying() {
        if (enabled && playing && current >= 0) {
            bcast("play");
        }
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
            Fi src = null;
            Fi abs = Core.files.absolute(t.source);
            if (abs.exists()) src = abs;
            else {
                Fi loc = Core.files.local(t.source);
                if (loc.exists()) src = loc;
            }
            if (src == null) return null;
            // Soloud 原生 fopen 无法读取含中文/非 ASCII 字符的路径（Windows fopen 用 ANSI 编码），
            // 也依赖扩展名解码。统一把本地文件复制到 ASCII 安全的缓存路径 <hash>.<真实ext> 后再播放。
            Fi safe = localAsciiCopy(t, src);
            return safe; // 不再 fallback 到 src（非 ASCII 路径），由播放端 isAsciiPath 守卫兜底
        }
        return null; // URL 未缓存由网络层下载
    }

    /** 把本地文件内容复制到 ASCII 安全的缓存路径（<hash>.<ext>），供 Soloud 流式读取；失败返回 null */
    private static Fi localAsciiCopy(MusicTrack t, Fi src) {
        try {
            Fi out = cacheFileForHash(t.cacheHash, extensionFrom(t.source));
            if (out.exists()) return out;
            out.parent().mkdirs();
            // 避免超大文件一次性读入内存：用流拷贝到缓存
            out.write(src.read(), false);
            hashExt.put(t.cacheHash, extensionFrom(t.source));
            return out.exists() ? out : null;
        } catch (Exception e) {
            SiliconLog.log("Local ascii-copy fail " + t.name + ": " + e.getMessage());
            return null;
        }
    }

    private static arc.audio.Music internalMusic(String key) {
        try {
            java.lang.reflect.Field f = mindustry.gen.Musics.class.getField(key);
            return (arc.audio.Music) f.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将 jar 打包的内置音频资源提取为真实磁盘缓存文件（Soloud 只能按磁盘路径流式读取）。
     * 以 source 键缓存，仅首次提取一次；返回可直接 createStream 的真实 Fi，失败返回 null。
     */
    private static Fi extractInternalToRealFile(Fi jarFile, String key) {
        if (jarFile == null) return null;
        try {
            String name = jarFile.name();
            String ext = (name != null && name.contains("."))
                    ? name.substring(name.lastIndexOf('.')) : ".ogg";
            Fi out = Core.files.cache(CACHE_DIR + "int-" + key + ext);
            if (!out.exists()) {
                byte[] data = jarFile.readBytes();
                if (data == null || data.length == 0) return null;
                out.writeBytes(data, false);
            }
            return out.exists() ? out : null;
        } catch (Exception e) {
            SiliconLog.log("Extract internal " + key + " fail: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 本地缓存（文件名统一 `<hash>.<真实扩展名>`，扩展名从源/协议得出，
    // 避免 mp3/wav 被 Soloud 按 .ogg 错误解码）
    // ------------------------------------------------------------------

    /** hash → 真实扩展名（供网络接收侧在分块落盘/查询时还原文件名） */
    private static final ObjectMap<String, String> hashExt = new ObjectMap<>();

    public static Fi cacheFileOf(MusicTrack t) {
        return cacheFileForHash(t.cacheHash, extensionFrom(t.source));
    }

    /** 登记某 hash 的真实扩展名（网络接收侧写/查缓存前调用） */
    public static void registerHashExt(String hash, String ext) {
        if (hash == null || hash.isEmpty()) return;
        hashExt.put(hash, normalizeExt(ext));
    }

    public static boolean hasCache(String hash) {
        String ext = resolveExt(hash);
        return ext != null && Core.files.cache(CACHE_DIR + hash + ext).exists();
    }

    public static boolean hasCache(String hash, String ext) {
        String e = normalizeExt(ext);
        return Core.files.cache(CACHE_DIR + hash + e).exists();
    }

    public static Fi cacheFileForHash(String hash) {
        String ext = resolveExt(hash);
        if (ext == null) return null;
        return Core.files.cache(CACHE_DIR + hash + ext);
    }

    public static Fi cacheFileForHash(String hash, String ext) {
        return Core.files.cache(CACHE_DIR + hash + normalizeExt(ext));
    }

    /** 分块接收暂存文件（未收齐前不视为正式缓存，防止半截文件被当作有效缓存） */
    public static Fi stagingFile(String hash) {
        return Core.files.cache(CACHE_DIR + hash + ".part");
    }

    /** 分块全部收齐后：把暂存文件重命名为正式缓存 `<hash>.<ext>` 并登记扩展名 */
    public static boolean finalizeCache(String hash, String ext) {
        try {
            String e = (ext == null || ext.isEmpty()) ? resolveExt(hash) : normalizeExt(ext);
            Fi staging = stagingFile(hash);
            if (staging == null || !staging.exists()) return false;
            Fi finalFile = cacheFileForHash(hash, e);
            staging.moveTo(finalFile);
            hashExt.put(hash, e);
            return finalFile.exists();
        } catch (Exception ex) {
            SiliconLog.log("Cache finalize fail " + hash + ": " + ex.getMessage());
            return false;
        }
    }

    /** 清理残留的未完成分块暂存文件（世界切换时无进行中的传输，避免孤儿 .part 长期堆积） */
    public static void cleanupStagingFiles() {
        try {
            Fi dir = Core.files.cache(CACHE_DIR);
            if (dir != null && dir.isDirectory()) {
                for (Fi f : dir.list()) {
                    if (f != null && "part".equalsIgnoreCase(f.extension())) f.delete();
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 解析 hash 对应缓存文件的真实扩展名：优先已登记/已知，否则扫缓存目录 `<hash>.*`（跨重启命中） */
    private static String resolveExt(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        String known = hashExt.get(hash);
        if (known != null) return known;
        // 未登记：扫缓存目录找 <hash>.<任意ext>
        try {
            Fi dir = Core.files.cache(CACHE_DIR);
            if (dir != null && dir.isDirectory()) {
                for (Fi f : dir.list()) {
                    if (f != null && "part".equalsIgnoreCase(f.extension())) continue; // 跳过未完成的分块暂存文件
                    if (f != null && f.nameWithoutExtension().equals(hash)) {
                        String e = f.extension();
                        String ext = (e == null || e.isEmpty()) ? ".ogg" : ("." + e.toLowerCase());
                        hashExt.put(hash, ext);
                        return ext;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        hashExt.put(hash, ".ogg");
        return ".ogg";
    }

    private static String normalizeExt(String ext) {
        if (ext == null || ext.isEmpty()) return ".ogg";
        return ext.charAt(0) == '.' ? ext.toLowerCase() : "." + ext.toLowerCase();
    }

    public static String extensionFrom(String source) {
        source = source == null ? "" : source;
        int q = source.indexOf('?');
        if (q >= 0) source = source.substring(0, q);
        int dot = source.lastIndexOf('.');
        if (dot >= 0) {
            String e = source.substring(dot).toLowerCase();
            if (e.equals(".mp3")) return ".mp3";
            if (e.equals(".wav")) return ".wav";
            if (e.equals(".flac")) return ".flac";
            if (e.equals(".m4a")) return ".m4a";
            if (e.equals(".wma")) return ".wma";
            if (e.equals(".aac")) return ".aac";
            if (e.equals(".opus")) return ".opus";
        }
        return ".ogg";
    }

    /** 写缓存（URL 下载 / 二进制共享落地统一走这里）；按真实扩展名命名 */
    static boolean writeCacheBytes(String hash, byte[] data) {
        String ext = hashExt.get(hash, ".ogg");
        return writeCacheBytes(hash, ext, data);
    }

    static boolean writeCacheBytes(String hash, String ext, byte[] data) {
        try {
            Fi file = cacheFileForHash(hash, ext);
            try (OutputStream out = file.write(false)) {
                out.write(data);
            }
            hashExt.put(hash, ext == null ? ".ogg" : ext);
            return true;
        } catch (Exception e) {
            SiliconLog.log("Cache write fail " + hash + ": " + e.getMessage());
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
        if (t != null && t.isInternal()) {
            file = extractInternalToRealFile(file, t.source);
            if (file == null) {
                SiliconLog.log("Remote play: cannot extract internal " + hash);
                return;
            }
        }
        Sound snd = null;
        try {
            if (!isAsciiPath(file.absolutePath())) {
                SiliconLog.log("Block remote play of non-ASCII path: " + file.name());
                return;
            }
            if (!isDecodablePath(file.absolutePath())) {
                SiliconLog.log("Block remote play of undecodable " + file.name());
                return;
            }
            snd = Sound.createStream(file);
            Voice v = new Voice();
            v.ownerUuid = ownerUuid;
            v.hash = hash;
            v.isLocalOwner = false;
            v.sound = snd;
            int id = snd.play(calcListenVolume(ownerX - player.x, ownerY - player.y), pitch * speed, 0f);
            v.voiceId = id;
            Core.audio.setLooping(id, false);
            v.lastX = ownerX;
            v.lastY = ownerY;
            v.createdAt = Time.time;
            voices.add(v);
            ownerHash.put(ownerUuid, hash);
        } catch (Exception e) {
            // play/createStream 失败时释放刚创建的原生 Sound，避免泄漏
            if (snd != null) {
                try {
                    snd.dispose();
                } catch (Exception ignored) {
                }
            }
            SiliconLog.log("Remote play fail: " + e.getMessage());
        }
    }

    /** 刷新某远程声源位置（收到 mp-pos 时调用） */
    static void updateRemotePosition(String ownerUuid, float x, float y) {
        for (Voice v : voices) {
            if (!v.isLocalOwner && ownerUuid.equals(v.ownerUuid)) {
                v.lastX = x;
                v.lastY = y;
                Core.audio.set(v.voiceId, calcListenPan(x), calcListenVolume(x - player.x, y - player.y));
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
                disposeVoice(v);
                voices.remove(i);
            }
        }
        ownerHash.remove(ownerUuid);
    }

    public static void clearRemoteVoices() {
        for (int i = voices.size - 1; i >= 0; i--) {
            Voice v = voices.get(i);
            if (!v.isLocalOwner) {
                disposeVoice(v);
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

    /**
     * 通过反射调用 arc.audio.Soloud 的包私有 native 方法读取/定位流式播放进度。
     * arc 未对 Sound.createStream 暴露 seek/position 公共 API，而 arc.audio.Music 提供；
     * 这里保留 Sound 流式播放（与远程 mp-pos 广播的 voiceId 模型一致）的前提下，
     * 仅读取/写入本地 voice 的进度，避免为 seek 重构整套播放模型。
     */
    private static final class SoloudBridge {
        private static final java.lang.reflect.Method GET_POS;
        private static final java.lang.reflect.Method SEEK;

        static {
            java.lang.reflect.Method gp = null, sk = null;
            try {
                Class<?> c = Class.forName("arc.audio.Soloud");
                gp = c.getDeclaredMethod("idPosition", int.class);
                gp.setAccessible(true);
                sk = c.getDeclaredMethod("idSeek", int.class, float.class);
                sk.setAccessible(true);
            } catch (Throwable ignored) {
            }
            GET_POS = gp;
            SEEK = sk;
        }

        static float getPosition(int voiceId) {
            try {
                if (GET_POS != null) return (Float) GET_POS.invoke(null, voiceId);
            } catch (Throwable ignored) {
            }
            return 0f;
        }

        static void seek(int voiceId, float seconds) {
            try {
                if (SEEK != null) SEEK.invoke(null, voiceId, seconds);
            } catch (Throwable ignored) {
            }
        }
    }
}
