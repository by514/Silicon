package silicon.util;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Align;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;

/**
 * 模组更新日志：仅在「当前版本号」与「上次见过版本」不同时弹窗（更新日志的常规语义）。
 * <p>
 * 弹窗内容为「≤ 当前版本的最高版本」对应的全部条目；弹过后记录当前版本，避免每次启动重复弹出。
 * <p>
 * 每条以黄线分隔，从左到右：物品贴图 | 物品名字 | 改动内容（自动换行）。
 */
public class Changelog {
    private static float dialogScl() {
        return Mathf.clamp(Math.min(Math.max(Core.graphics.getWidth(), 1f) / 1920f,
                Math.max(Core.graphics.getHeight(), 1f) / 1080f), 0.5f, 1.6f);
    }

    public static class Entry {
        public final String version;
        public final String item;
        public final String desc;

        public Entry(String version, String item, String desc) {
            this.version = version;
            this.item = item;
            this.desc = desc;
        }
    }

    /** 更新日志条目（版本 → 文案）。顺序无关紧要：checkAndShow 显式按版本比较取 ≤ 当前版本的最高版本。 */
    private static final Entry[] ENTRIES = {
        new Entry("a0.12.2.0", "universal-junction", "配置界面重构：拖拽换位、动态分组、预设与保存"),
        new Entry("a0.12.1.0", "switch", "开关按钮改为切换式（返回式→按下切换开/关）"),
        new Entry("a0.12.1.0", "power-protector", "恢复期保持电网连接，防止中枢网跳变"),
        new Entry("a0.12.1.0", "item-transfer-hub", "修复连接线存档重载后不渲染"),
        new Entry("a0.12.1.0", "dimension-anchor", "修复接收态释放物品按钮失效"),
    };

    /** 上次弹过更新日志的版本号（与当前版本相同则不再弹） */
    private static final String SEEN_KEY = "silicon.changelog.seen";
    /** 方块图标缓存：避免每次弹窗对每个条目重复 Vars.content.blocks() 线性查找 */
    private static final ObjectMap<String, TextureRegion> iconCache = new ObjectMap<>();

    /**
     * 每次启动必弹当前版本的改动内容（供调试方便观察）。
     * 目标版本 = ≤ 当前版本中的最高版本（ENTRIES 按「旧→新」排列，遍历覆盖即得）。
     */
    public static void checkAndShow() {
        String current = UpdateChecker.currentVersion();
        if (current.isEmpty()) return;
        // 常规更新日志语义：仅版本号变化时弹（避免每次启动都弹同一版本）
        if (current.equals(Core.settings.getString(SEEN_KEY, ""))) return;

        // 取 ≤ 当前版本的最高版本（显式按版本比较，不依赖 ENTRIES 的排列顺序）
        String target = null;
        for (Entry e : ENTRIES) {
            if (UpdateChecker.isNewer(e.version, current)) continue;
            if (target == null || UpdateChecker.isNewer(target, e.version)) target = e.version;
        }
        if (target == null) return;

        Seq<Entry> show = new Seq<>();
        for (Entry e : ENTRIES) {
            if (e.version.equals(target)) show.add(e);
        }
        if (show.isEmpty()) return;

        Core.settings.put(SEEN_KEY, current);
        Core.app.post(() -> showDialog(show));
    }

    private static void showDialog(Seq<Entry> entries) {
        BaseDialog dialog = new BaseDialog(Core.bundle.get("changelog.title"));
        dialog.cont.top();

        float scl = dialogScl();

        Table list = new Table();
        list.top();

        String version = entries.first().version;
        list.add(version).color(Pal.accent).left().padBottom(6f);
        list.row();
        list.image(Tex.whitePane, Color.yellow).growX().height(2f).padBottom(4f);
        list.row();

        for (int i = 0; i < entries.size; i++) {
            Entry e = entries.get(i);

            list.table(row -> {
                // 优先取 mod 方块（silicon-<内部名>），再回退任何其后缀匹配的方块；结果按 item 名缓存
                TextureRegion icon = iconCache.get(e.item);
                if (icon == null) {
                    Block block = Vars.content.blocks().find(b -> b.name.equals("silicon-" + e.item));
                    if (block == null) block = Vars.content.blocks().find(b -> b.name.startsWith("silicon-") && b.name.endsWith("-" + e.item));
                    // 未命中就不入缓存，避免内容尚未加载时把 null 永久缓存
                    if (block != null && block.uiIcon != null) {
                        icon = block.uiIcon;
                        iconCache.put(e.item, icon);
                    }
                }
                if (icon != null) {
                    row.image(icon).size(48f * scl).padRight(10f).padTop(2f);
                }
                String display = Core.bundle.getOrNull("block.silicon-" + e.item + ".name");
                row.add(display != null ? display : e.item).color(Pal.accent).left().padRight(12f);
                row.add(e.desc).growX().left().wrap().padBottom(4f);
            }).fillX().pad(4f);
            list.row();

            list.image(Tex.whitePane, Color.yellow).growX().height(2f).padBottom(4f);
            list.row();
        }

        float pw = Math.min(Core.graphics.getWidth() * 0.75f, 820f * scl);
        dialog.cont.pane(list).growX().width(pw).maxHeight(420f * scl).padTop(6f);
        dialog.buttons.button("@updatecheck.close", Icon.cancel, dialog::hide)
                .size(210f * scl, 60f * scl).pad(10f);
        dialog.closeOnBack();
        dialog.show();
    }
}
