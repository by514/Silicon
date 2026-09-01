package silicon.audio;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.util.Http;
import arc.util.Log;
import arc.util.Time;
import mindustry.game.EventType;
import mindustry.gen.Call;
import mindustry.gen.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static mindustry.Vars.net;
import static mindustry.Vars.netServer;

/**
 * 音乐播放器多人网络层：让其他玩家按距离远近听到本机播放的音乐。
 * <p>
 * 链路：播放者(owner) 上报服务端 → 服务端转发给所有客户端 → 各客户端按「自己到 owner 的距离」
 * 在本地 3D 定位播放同一曲目。多个 owner 可叠加多个声源。
 * <p>
 * 曲目资源分发：
 * - 内置原版音乐：所有人本地都有，仅广播元数据即可播放
 * - URL 曲目：广播 URL，接收端用 Http 下载到本地缓存后播放（本地缓存优先复用）
 * - 本地路径曲目：播放者读文件字节，二进制分块广播，接收端重组写缓存后播放
 *
 * 协议（自定义包）：
 * - "mp-sync" (String, 可靠)：op=play/pause/resume/stop/next + 曲目元数据
 * - "mp-meta"(String, 可靠)：本地路径曲目的二进制分块元数据
 * - "mp-chunk"(Binary, 可靠)：二进制分块（头部含 hash+总块数+块索引）
 * - "mp-pos"(String, 不可靠)：owner 世界坐标
 */
public class MusicNetwork {
    private static final int CHUNK_SIZE = 24 * 1024;
    private static final int HEADER_LEN = 16 + 4 + 4;
    private static final String MSG_SYNC = "mp-sync";
    private static final String MSG_META = "mp-meta";
    private static final String MSG_CHUNK = "mp-chunk";
    private static final String MSG_POS = "mp-pos";

    /** ownerUuid → 其本地路径曲目分块接收状态 */
    private static final ObjectMap<String, ChunkRecv> recv = new ObjectMap<>();
    /** ownerUuid → 播放曲目 hash（mp-pos 定位用） */
    private static final ObjectMap<String, String> ownerHash = new ObjectMap<>();
    /** ownerUuid → 最近已知坐标 */
    private static final ObjectMap<String, float[]> ownerPos = new ObjectMap<>();

    private static float lastPosTick = 0;
    private static boolean initialized = false;

    private MusicNetwork() {}

    public static void init() {
        if (initialized) return;
        initialized = true;

        // —— 服务端接收客户端上报并转发给所有客户端 ——
        if (netServer != null) {
            netServer.addPacketHandler(MSG_SYNC, (p, data) -> {
                if (p == null) return;
                Call.clientPacketReliable(MSG_SYNC, data);
            });
            netServer.addPacketHandler(MSG_META, (p, data) -> {
                if (p == null) return;
                Call.clientPacketReliable(MSG_META, data);
            });
            netServer.addPacketHandler(MSG_POS, (p, data) -> {
                if (p == null) return;
                Call.clientPacketUnreliable(MSG_POS, data);
            });
            netServer.addBinaryPacketHandler(MSG_CHUNK, (p, bytes) -> {
                if (p == null || bytes == null) return;
                Call.clientBinaryPacketReliable(MSG_CHUNK, bytes);
            });
        }

        // —— 客户端接收 ——
        mindustry.core.NetClient nc = mindustry.Vars.netClient;
        if (nc != null) {
            nc.addPacketHandler(MSG_SYNC, MusicNetwork::onSync);
            nc.addPacketHandler(MSG_META, MusicNetwork::onMeta);
            nc.addPacketHandler(MSG_POS, MusicNetwork::onPos);
            nc.addBinaryPacketHandler(MSG_CHUNK, MusicNetwork::onChunk);
        }

        // 周期性上报本机坐标（若本机正在本地播放）
        Events.run(EventType.Trigger.update, MusicNetwork::tick);
    }

    // ------------------------------------------------------------------
    // 发送侧（本机为播放者时触发）
    // ------------------------------------------------------------------

    /** 本机本地播放状态变化时由 MusicPlayer 回调：广播给其他玩家 */
    static void notifyLocalChanged(String op) {
        if (!net.active()) return;
        if (!MusicPlayer.isEnabled()) return;

        MusicTrack t = MusicPlayer.currentTrack();
        if (t == null && !op.equals("stop")) return;

        String owner = ownerKey();
        switch (op) {
            case "play":
            case "next":
                emitSync(owner, op, t);
                if (t != null && t.isLocal()) {
                    // 本地路径曲目：二进制分块广播
                    sendLocalFile(owner, t);
                }
                break;
            case "pause":
            case "resume":
            case "stop":
                emitSyncSimple(owner, op);
                break;
        }
    }

    private static void emitSyncSimple(String owner, String op) {
        String payload = "{\"owner\":\"" + owner + "\",\"op\":\"" + op + "\"}";
        sendReliable(MSG_SYNC, payload);
    }

    private static void emitSync(String owner, String op, MusicTrack t) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"owner\":\"").append(owner)
          .append("\",\"op\":\"").append(op)
          .append("\",\"hash\":\"").append(t.cacheHash)
          .append("\",\"name\":\"").append(escape(t.name))
          .append("\",\"type\":").append(t.type);
        if (t.isUrl() && t.source != null) {
            sb.append(",\"url\":\"").append(escape(t.source)).append('"');
        } else if (t.isLocal() && t.source != null) {
            sb.append(",\"src\":\"").append(escape(t.source)).append('"');
        }
        sb.append('}');
        sendReliable(MSG_SYNC, sb.toString());
    }

    /** 坐标上报（周期调用） */
    private static void tick() {
        if (!net.active()) return;
        if (!MusicPlayer.isEnabled() || !MusicPlayer.isPlaying()) return;
        if (Time.time - lastPosTick < 30f) return;
        lastPosTick = Time.time;
        String owner = ownerKey();
        String hash = MusicPlayer.currentTrack() == null ? "" : MusicPlayer.currentTrack().cacheHash;
        ownerHash.put(owner, hash);
        sendUnreliable(MSG_POS, owner + "|" + hash + "|" + playerX() + "|" + playerY());
    }

    /** 本机作为播放者时，向服务端上报或直接广播 */
    private static void sendReliable(String type, String data) {
        if (net.server()) {
            Call.clientPacketReliable(type, data);
        } else {
            Call.serverPacketReliable(type, data);
        }
    }

    private static void sendUnreliable(String type, String data) {
        if (net.server()) {
            Call.clientPacketUnreliable(type, data);
        } else {
            Call.serverPacketUnreliable(type, data);
        }
    }

    private static void broadcastBinary(String type, byte[] data) {
        if (net.server()) {
            Call.clientBinaryPacketReliable(type, data);
        } else {
            Call.serverBinaryPacketReliable(type, data);
        }
    }

    /** 本地路径曲目：读取文件字节，分块广播 */
    private static void sendLocalFile(String owner, MusicTrack t) {
        Fi file = MusicPlayer.resolveToPlayableFile(t);
        if (file == null || !file.exists()) {
            Log.info("[SiliconMusic] local file missing: " + t.source);
            return;
        }
        try {
            byte[] all = file.readBytes();
            int chunkCount = (all.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
            String hash = t.cacheHash;

            // 先广播元数据
            StringBuilder meta = new StringBuilder();
            meta.append("{\"owner\":\"").append(owner)
                .append("\",\"hash\":\"").append(hash)
                .append("\",\"name\":\"").append(escape(t.name))
                .append("\",\"type\":2")
                .append(",\"total\":").append(all.length)
                .append(",\"chunks\":").append(chunkCount)
                .append('}');
            sendReliable(MSG_META, meta.toString());

            // 再逐块广播
            int idx = 0;
            for (int off = 0; off < all.length; off += CHUNK_SIZE) {
                int len = Math.min(CHUNK_SIZE, all.length - off);
                byte[] header = buildHeader(hash, chunkCount, idx);
                byte[] payload = new byte[HEADER_LEN + len];
                System.arraycopy(header, 0, payload, 0, HEADER_LEN);
                System.arraycopy(all, off, payload, HEADER_LEN, len);
                broadcastBinary(MSG_CHUNK, payload);
                idx++;
            }
            Log.info("[SiliconMusic] broadcast local file " + t.name + " (" + all.length + "B, " + chunkCount + " chunks)");
        } catch (IOException e) {
            Log.info("[SiliconMusic] local file read fail: " + e.getMessage());
        }
    }

    private static byte[] buildHeader(String hash, int chunkCount, int idx) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        byte[] hashBytes = new byte[16];
        byte[] src = hash.getBytes();
        System.arraycopy(src, 0, hashBytes, 0, Math.min(16, src.length));
        dos.write(hashBytes);
        dos.writeInt(chunkCount);
        dos.writeInt(idx);
        dos.flush();
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------
    // 客户端接收侧
    // ------------------------------------------------------------------

    private static void onSync(String data) {
        if (!MusicPlayer.canReceive()) return;
        try {
            String owner = extract(data, "owner");
            String op = extract(data, "op");
            if (owner == null || isSelf(owner)) return;

            String hash = extract(data, "hash");
            int type = parseInt(extract(data, "type"), -1);
            String name = extract(data, "name");
            String url = extract(data, "url");
            String src = extract(data, "src");

            if (op.equals("stop")) {
                MusicPlayer.stopRemoteVoice(owner);
                ownerHash.remove(owner);
                recv.remove(owner);
                return;
            }
            if (op.equals("pause")) { MusicPlayer.pauseRemoteVoice(owner); return; }
            if (op.equals("resume")) { MusicPlayer.resumeRemoteVoice(owner); return; }

            // play / next
            if (owner == null || hash == null) return;
            ownerHash.put(owner, hash);
            MusicPlayer.stopRemoteVoice(owner); // 切换曲目时先停旧的

            if (type == MusicTrack.INTERNAL || MusicPlayer.hasCache(hash)) {
                // 可立即播放（内置或已有缓存）
                float[] pos = ownerPos.get(owner);
                MusicPlayer.playRemoteVoice(owner, hash, pos == null ? 0f : pos[0], pos == null ? 0f : pos[1]);
            } else if (type == MusicTrack.URL && url != null) {
                downloadAndPlay(owner, hash, url, name);
            } else if (type == MusicTrack.LOCAL) {
                // 等 mp-meta / mp-chunk；若本地已有同名曲目（本机也加过同源），可能已在 tracks 里
                if (MusicPlayer.trackByHash(hash) != null && MusicPlayer.hasCache(hash)) {
                    float[] pos = ownerPos.get(owner);
                    MusicPlayer.playRemoteVoice(owner, hash, pos == null ? 0f : pos[0], pos == null ? 0f : pos[1]);
                }
            }
        } catch (Exception e) {
            Log.info("[SiliconMusic] onSync err: " + e.getMessage());
        }
    }

    private static void downloadAndPlay(String owner, String hash, String url, String name) {
        // 保证已有曲目记录（接收方本地建立一条 URL 元数据，便于缓存查找）
        if (MusicPlayer.trackByHash(hash) == null) {
            int dup = MusicPlayer.indexOfHash(hash);
            if (dup < 0) MusicPlayer.addTrack(MusicTrack.URL, url, name);
        }
        if (MusicPlayer.hasCache(hash)) {
            float[] pos = ownerPos.get(owner);
            MusicPlayer.playRemoteVoice(owner, hash, pos == null ? 0f : pos[0], pos == null ? 0f : pos[1]);
            return;
        }
        Log.info("[SiliconMusic] downloading " + url);
        Http.get(url, res -> {
            byte[] bytes = res.getResult();
            Core.app.post(() -> {
                MusicPlayer.writeCacheBytes(hash, bytes);
                if (MusicPlayer.canReceive()) {
                    float[] pos = ownerPos.get(owner);
                    MusicPlayer.playRemoteVoice(owner, hash, pos == null ? 0f : pos[0], pos == null ? 0f : pos[1]);
                }
            });
        }, err -> {
            Log.info("[SiliconMusic] download fail: " + err.getMessage());
        });
    }

    /** 本机播放 URL 曲目但尚未下载缓存时，先从网络下载到本地缓存，完成后回调 onDone（主线程）。 */
    static void fetchLocalThenPlay(MusicTrack t, Runnable onDone) {
        if (t == null || !t.isUrl() || t.source == null) return;
        if (MusicPlayer.hasCache(t.cacheHash)) {
            Core.app.post(onDone);
            return;
        }
        Log.info("[SiliconMusic] downloading (local) " + t.source);
        Http.get(t.source, res -> {
            byte[] bytes = res.getResult();
            Core.app.post(() -> {
                if (MusicPlayer.writeCacheBytes(t.cacheHash, bytes)) {
                    onDone.run();
                }
            });
        }, err -> {
            Log.info("[SiliconMusic] download fail: " + err.getMessage());
        });
    }

    private static void onMeta(String data) {
        if (!MusicPlayer.canReceive()) return;
        try {
            String owner = extract(data, "owner");
            String hash = extract(data, "hash");
            int chunks = parseInt(extract(data, "chunks"), 0);
            if (owner == null || hash == null || chunks <= 0 || isSelf(owner)) return;
            ownerHash.put(owner, hash);
            if (MusicPlayer.hasCache(hash)) return; // 已有缓存，无需接收分块
            // 建接收缓冲与缓存文件（先写占位）
            ChunkRecv r = new ChunkRecv();
            r.hash = hash;
            r.chunkCount = chunks;
            r.received = new boolean[chunks];
            r.total = chunks;
            recv.put(owner, r);
        } catch (Exception e) {
            Log.info("[SiliconMusic] onMeta err: " + e.getMessage());
        }
    }

    private static void onChunk(byte[] payload) {
        if (!MusicPlayer.canReceive()) return;
        if (payload == null || payload.length < HEADER_LEN) return;
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(payload, 0, HEADER_LEN);
            DataInputStream dis = new DataInputStream(bis);
            byte[] hashBytes = new byte[16];
            dis.readFully(hashBytes);
            int chunkCount = dis.readInt();
            int idx = dis.readInt();
            String hash = new String(hashBytes).trim();
            int dataLen = payload.length - HEADER_LEN;
            byte[] chunk = new byte[dataLen];
            System.arraycopy(payload, HEADER_LEN, chunk, 0, dataLen);

            ChunkRecv r = recvByHash(hash);
            if (r == null) {
                // 未收到 meta（乱序）：按头部信息重建
                r = new ChunkRecv();
                r.hash = hash;
                r.chunkCount = chunkCount;
                r.total = -1;
                r.received = new boolean[chunkCount];
                recv.put(hash, r);
            }
            if (idx < 0 || idx >= r.received.length || r.received[idx]) return;

            // 追加写缓存：reliable 包有序，按到达顺序 append
            Fi file = MusicPlayer.cacheFileForHash(hash);
            if (!file.exists()) file.write(false).close();
            try (java.io.OutputStream out = file.write(true)) {
                out.write(chunk);
            }
            r.received[idx] = true;
            r.receivedCount++;

            if (r.receivedCount >= r.received.length) {
                // 收齐 → 移除临时记录，尝试按 owner 播放
                recvRemoveByHash(hash);
                String owner = ownerOfHash(hash);
                if (owner != null && !isSelf(owner)) {
                    float[] pos = ownerPos.get(owner);
                    MusicPlayer.playRemoteVoice(owner, hash, pos == null ? 0f : pos[0], pos == null ? 0f : pos[1]);
                }
            }
        } catch (Exception e) {
            Log.info("[SiliconMusic] onChunk err: " + e.getMessage());
        }
    }

    private static ChunkRecv recvByHash(String hash) {
        ChunkRecv direct = recv.get(hash);
        if (direct != null) return direct;
        for (ObjectMap.Entries<String, ChunkRecv> it = recv.entries().iterator(); it.hasNext(); ) {
            ObjectMap.Entry<String, ChunkRecv> e = it.next();
            if (hash.equals(e.value.hash)) return e.value;
        }
        return null;
    }

    private static String ownerOfHash(String hash) {
        for (ObjectMap.Entries<String, String> it = ownerHash.entries().iterator(); it.hasNext(); ) {
            ObjectMap.Entry<String, String> e = it.next();
            if (hash.equals(e.value)) return e.key;
        }
        String fallback = null;
        for (ObjectMap.Entries<String, ChunkRecv> it = recv.entries().iterator(); it.hasNext(); ) {
            ObjectMap.Entry<String, ChunkRecv> e = it.next();
            if (e.value != null && hash.equals(e.value.hash)) { fallback = e.key; break; }
        }
        return fallback;
    }

    private static void recvRemoveByHash(String hash) {
        for (ObjectMap.Entries<String, ChunkRecv> it = recv.entries().iterator(); it.hasNext(); ) {
            ObjectMap.Entry<String, ChunkRecv> e = it.next();
            if (hash.equals(e.value.hash)) { it.remove(); }
        }
    }

    private static void onPos(String data) {
        if (!MusicPlayer.canReceive()) return;
        try {
            String[] parts = data.split("\\|");
            if (parts.length < 4) return;
            String owner = parts[0];
            String hash = parts[1];
            float x = Float.parseFloat(parts[2]);
            float y = Float.parseFloat(parts[3]);
            if (isSelf(owner)) return;
            ownerHash.put(owner, hash);
            ownerPos.put(owner, new float[]{x, y});
            MusicPlayer.updateRemotePosition(owner, x, y);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static String ownerKey() {
        Player p = mindustry.Vars.player;
        if (p == null) return "none";
        return (p.uuid() != null && !p.uuid().isEmpty()) ? p.uuid() : ("id:" + p.id());
    }

    private static boolean isSelf(String owner) {
        return owner != null && owner.equals(ownerKey());
    }

    private static float playerX() {
        Player p = mindustry.Vars.player;
        return p == null ? 0f : p.x;
    }

    private static float playerY() {
        Player p = mindustry.Vars.player;
        return p == null ? 0f : p.y;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static String extract(String json, String key) {
        if (json == null) return null;
        String pat = "\"" + key + "\":\"";
        int i = json.indexOf(pat);
        if (i < 0) {
            String patNum = "\"" + key + "\":";
            int j = json.indexOf(patNum);
            if (j < 0) return null;
            int end = json.indexOf(',', j);
            if (end < 0) end = json.indexOf('}', j);
            if (end < 0) return null;
            return json.substring(j + patNum.length(), end).trim();
        }
        int start = i + pat.length();
        int end = start;
        while (end < json.length() && json.charAt(end) != '"') end++;
        if (end >= json.length()) return null;
        return json.substring(start, end);
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static class ChunkRecv {
        String hash;
        int chunkCount;
        int total;
        boolean[] received;
        int receivedCount;
    }

    /** 世界加载/地图切换时清空网络残局 */
    public static void reset() {
        recv.clear();
        ownerHash.clear();
        ownerPos.clear();
        MusicPlayer.clearRemoteVoices();
    }
}
