package silicon.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.HandCursorListener;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Log;
import arc.util.Tmp;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import silicon.world.blocks.distribution.UniversalJunction;

import static mindustry.Vars.ui;

/**
 * 万向交叉器配置对话框（新 UI）。
 * <p>
 * 布局：每个输入方向一个白色面板区域，区域内：
 * - 最上方：输入方向标题（白字，水平居中）；
 * - 标题下方：可拖动生成的 0~4 个白色槽位框（优先级分级，越靠上权重越大：槽0=4, 槽1=3, 槽2=2, 槽3=1），
 *   空槽位自动移除，最多 4 个；
 * - 区域最下方：红色框（优先级 0，即不输出），初始放置 4 个输出方向按钮（2x2 宫格）。
 * <p>
 * 交互：按住黄色按钮拖动，松手放置——拖到空白处生成新白色槽位；拖到已有槽位则并入该槽位；
 * 拖回红色框则移回红框，所在槽位空时槽位自动消失。每次放置都会同步到 weights[输入][输出] 并写入配置。
 * <p>
 * 整框调序：按住白框左侧的灰色拖动手柄拖动整框换位，拖拽时：
 * - 白色拖拽影（含内部黄按钮）跟随鼠标移动；
 * - 灰色占位框在落点位置（两白框之间的空隙里）实时显示，水平位置跟随鼠标；
 * - 落点位置两旁的白色框自动让位，拉开空隙；
 * - 松手后灰色占位框消失，白框按落点重排。
 */
public class UniversalJunctionDialog extends BaseDialog {
    /** 单个输入区域最多白色槽位数 */
    private static final int MAX_SLOTS = 4;
    /** 红框（优先级 0 区域）高度基准值：无论内部有无按钮都保持不变、不消失 */
    private static final float RED_H = 140f;
    /** 方向按钮尺寸基准值：红框与白框内一致，保证按钮大小不变 */
    private static final float BTN_W = 140f;
    private static final float BTN_H = 100f;
    /** 方向按钮配色：亮金细边框 + 深琥珀内面 + 白字，构成金属徽章效果 */
    private static final Color BTN_BORDER = Color.valueOf("ffcf4d");
    private static final Color BTN_FACE = Color.valueOf("7a5c16");
    /** 整框拖拽时灰色落点占位框的高度（基准值） */
    private static final float PLACE_H = 60f;
    /** 按钮拖到空白处时「新建槽位」长条灰框的高度：比按钮(y=52)高，避免内嵌按钮框与大框上下边缘重叠 */
    private static final float BTN_HINT_H = 100f;

    UniversalJunction.UniversalJunctionBuild build;
    /** 单个黄色按钮正在被拖动时置 true：抑制整框重排的灰色占位框，避免按钮拖拽与整框拖拽互相干扰 */
    boolean draggingButton = false;
    /** 四个输入方向的区域状态，供保存按钮一次性同步全部 */
    private final Seq<RegionState> allRegions = new Seq<>();

    public UniversalJunctionDialog(String title) {
        this(title, Core.scene.getStyle(DialogStyle.class));
    }

    public UniversalJunctionDialog(String title, DialogStyle style) {
        super(title, style);
        setFillParent(true);
        row();
        add(buttons).growX().name("universal-junction");
        shown(this::setup);
    }

    public UniversalJunctionDialog() {
        this("@universal-junction.title");
    }

    public void setup() {
        allRegions.clear(); // shown → setup 可能多次调用：先清空，避免 re-show 时累积陈旧区域状态
        cont.table(grid -> {
            grid.margin(10f);
            for (int in = 0; in < 4; in++) {
                final int input = in;
                grid.table(Tex.whitePane, column -> {
                    column.top();
                    column.margin(14f);
                    RegionState rs = new RegionState(input, column);
                    allRegions.add(rs);

                    // 输入方向标题（白字，水平居中）
                    column.add(Core.bundle.get("universal-junction.dir" + input))
                            .growX().labelAlign(Align.center).padBottom(10f).row();

                    // 上方弹性空白：把白框层推到区域纵向居中
                    column.add().expandY().row();

                    // 槽位层：纵向居中于标题与红框之间
                    column.add(rs.slotLayer).growX().row();

                    // 下方弹性空白：与上方共同把白框层居中，同时把红框推到底部
                    column.add().expandY().row();

                    // 红框（优先级 0）：置于区域最下方，高度随按钮数量自动伸缩
                    Table redBox = new Table();
                    redBox.background(Tex.whitePane);
                    redBox.setColor(Color.red);
                    redBox.margin(10f);
                    rs.redBox = redBox;
                    column.add(redBox).growX().padTop(6f).row();

                    // 统一按建筑当前权重还原布局：w>0 → 放入对应白色槽位，w==0 → 放入红框。
                    // 刚放置（权重仍为默认值 2 均分）时视为「未配置」：全部放入红框，与用户直觉一致，
                    // 红框语义为优先级 0 = 不输出。
                    // 仅还原显示：不改写 weights，也不在此触发 configure，避免「打开面板即重置路由瞬态」。
                    boolean freshDefault = true;
                    for (int out = 0; out < 4; out++) {
                        if (build.weights[input][out] != 2) { freshDefault = false; break; }
                    }
                    for (int out = 0; out < 4; out++) {
                        Direction d = new Direction(rs, out);
                        int w = build.weights[input][out];
                        if (freshDefault) {
                            rs.redButtons.add(d);
                        } else if (w > 0) {
                            rs.placeInSlotByWeight(d, w);
                        } else {
                            rs.redButtons.add(d);
                        }
                    }
                    rs.rebuildRed();
                    rs.pruneEmptySlots();
                    rs.rebuildSlots();
                }).growX().grow().uniformX().pad(8f);
            }
        }).grow();

        buttons.defaults()
                .size(280f, 60f)
                .left()
                .margin(10f);
        buttons.button("@back", Icon.left, this::hide);
        buttons.button("@edit", Icon.edit, () -> {
            BaseDialog dialog = new BaseDialog("@edit");

            dialog.cont.pane(p -> p.table(Tex.button, t -> {
                t.defaults()
                        .size(280f, 60f)
                        .left()
                        .margin(10f);
                t.button("@clear", Icon.cancel, Styles.flatt, () -> {
                    ui.showConfirm("", () -> {
                        build.setAll(0);
                        cont.clearChildren();
                        buttons.clearChildren();
                        hide();
                        show();
                        invalidateHierarchy();
                    });
                    dialog.hide();
                }).row();
                t.button("@copy.clipboard", Icon.copy, Styles.flatt, () -> {
                    copyToClipboard();
                    dialog.hide();
                }).row();
                t.button("@load.clipboard", Icon.download, Styles.flatt, () -> {
                    loadFromClipboard();
                    dialog.hide();
                }).row();
            }));
            dialog.addCloseButton();
            dialog.show();
        });
    }

    public void copyToClipboard() {
        Core.app.setClipboardText(build.weightsString());
    }

    public void loadFromClipboard() {
        try {
            build.applyConfig(Core.app.getClipboardText());
        } catch (Throwable e) {
            ui.showException(e);
        }
    }

    public void show(UniversalJunction.UniversalJunctionBuild build) {
        this.build = build;
        show();
    }

    // ============================================================
    // 单个输入区域的状态：维护按钮在红框/各槽位中的归属
    // ============================================================

    public class RegionState {
        final int input;
        final Table column;
        final Table slotLayer;                 // 槽位层（从上堆叠）
        final Seq<SlotBox> slotBoxes = new Seq<>();   // 每个白框（含拖动手柄+按钮区）
        final Seq<Seq<Direction>> slotContents = new Seq<>(); // 每个白框内的按钮
        final Seq<Direction> redButtons = new Seq<>(); // 红框内的按钮
        Table redBox;

        RegionState(int input, Table column) {
            this.input = input;
            this.column = column;
            this.slotLayer = new Table();
        }

        /** 重建红框布局：按钮竖向排列，高度随按钮数量自动伸缩 */
        void rebuildRed() {
            redBox.clearChildren();
            redBox.margin(0f).marginLeft(8f).marginRight(8f).marginTop(12f).marginBottom(12f);
            for (int i = 0; i < redButtons.size; i++) {
                redBox.add(redButtons.get(i)).growX().height(BTN_H).pad(4f).row();
            }
            redBox.invalidateHierarchy();
        }

        /** 重建槽位层（按 slotBoxes 顺序从上往下堆叠，按钮竖向排列，框间留 40 间距） */
        void rebuildSlots() {
            slotLayer.clearChildren();
            for (int i = 0; i < slotBoxes.size; i++) {
                int n = slotContents.get(i).size;
                // 若该框正显示灰色占位（拖拽悬停中），多算一个按钮高度使白框实时扩大
                if (slotBoxes.get(i).previewInsert >= 0) n++;
                float h = n * (BTN_H + 8f) + 14f;
                slotLayer.add(slotBoxes.get(i)).growX().height(h).padBottom(40f).row();
            }
            slotLayer.invalidateHierarchy();
        }

        /** 移除全部空白槽位（无按钮即消失） */
        void pruneEmptySlots() {
            for (int i = slotBoxes.size - 1; i >= 0; i--) {
                if (slotContents.get(i).size == 0) {
                    slotBoxes.remove(i);
                    slotContents.remove(i);
                }
            }
            rebuildSlots();
        }

        /** 由当前布局同步 weights[input][out]：槽 i 权重 = 4-i，红框权重 = 0，并写入配置 */
        void syncWeights() {
            for (int i = 0; i < slotContents.size; i++) {
                int w = MAX_SLOTS - i; // 槽0=4, 槽1=3, ...
                for (Direction d : slotContents.get(i)) {
                    build.weights[input][d.dir] = w;
                }
            }
            for (Direction d : redButtons) {
                build.weights[input][d.dir] = 0;
            }
            build.configure(build.weightsString());
        }

        /** 按钮当前所在：红框 offset=-1；槽位 i 返回其索引 */
        int locate(Direction d) {
            for (int i = 0; i < slotContents.size; i++) {
                if (slotContents.get(i).contains(d)) return i;
            }
            return -1;
        }

        /** 从当前归属处移除按钮，并立即重排来源白框（删除后剩余按钮重新居中） */
        void removeFrom(Direction d) {
            int i = locate(d);
            if (i >= 0) {
                slotContents.get(i).remove(d);
                rebuildSlotContents(i);
            }
            if (redButtons.contains(d)) {
                redButtons.remove(d);
                rebuildRed(); // 红框按钮被拖出：立即重排红框，高度随数量伸缩
            }
        }

        /** 放入指定槽位 i */
        void placeInSlot(Direction d, int i) {
            removeFrom(d);
            slotContents.get(i).add(d);
            rebuildSlotContents(i);
            pruneEmptySlots();
            syncWeights();
        }

        /** 放入指定槽位 i，在该槽内按钮序列的指定位置插入（竖向排序） */
        void placeIntoSlot(Direction d, int i, int insert) {
            int tLoc = locate(d);
            removeFrom(d);
            if (slotBoxes.size == 0) {
                placeInRed(d);
                return;
            }
            int cur = slotContents.get(i).size;
            if (tLoc == i) {
                // 来源与目标同槽：removeFrom 已移除 d，落点相对「移除后」的列表计算
                if (insert > cur) insert = cur;
            }
            insert = Mathf.clamp(insert, 0, slotContents.get(i).size);
            slotContents.get(i).insert(insert, d);
            rebuildSlotContents(i);
            pruneEmptySlots();
            syncWeights();
        }

        /** 计算落点(stage坐标)应插入的槽位索引：与每个槽中心精确比较（stage y-up，越大越靠上） */
        int insertIndexFor(float sx, float sy) {
            for (int i = 0; i < slotBoxes.size; i++) {
                Vec2 v = slotBoxes.get(i).localToStageCoordinates(Tmp.v1.set(0f, 0f));
                float centerY = v.y + slotBoxes.get(i).getHeight() / 2f;
                if (sy > centerY) return i; // 落点在该槽中心上方 → 插入到该槽位置（其前）
            }
            return slotBoxes.size; // 落点在所有槽中心之下 → 追加末尾
        }

        /** 槽 i 的高度：与 rebuildSlots 完全一致（按钮竖向排列，每个按钮 BTN_H+8） */
        float slotHeight(int i) {
            int n = slotContents.get(i).size;
            return n * (BTN_H + 8f) + 14f;
        }

        /** 槽 i 高度（若正显示灰色占位则多算一个按钮高度，与 rebuildSlots 一致） */
        float slotHeightWithPreview(int i) {
            int n = slotContents.get(i).size;
            if (slotBoxes.get(i).previewInsert >= 0) n++;
            return n * (BTN_H + 8f) + 14f;
        }

        /**
         * 只调整单个白框在槽位层中的高度（灰色占位增/删时让白框实时扩大/复原）。
         * 只改对应 Cell 的高度并触发重排，不 clearChildren slotLayer——
         * 避免把仍持有触摸焦点的来源白框从场景中 detach，从而引发 arc 的合成 touchUp
         * 把松手前的拖拽提前放置（「一离开白框就放置」）或 NPE。
         */
        void setSlotHeight(int i) {
            slotLayer.getCell(slotBoxes.get(i)).height(slotHeightWithPreview(i));
            slotLayer.invalidateHierarchy();
        }

        /** 基准布局下最顶白框顶边的 stage y：取 slotBoxes[0] 当前底边 + 自身高度。
         * 整框拖动中调用前必须先用 applyPristine 恢复基准布局（此时与实际布局一致）。 */
        float pristineTopOfStack() {
            return slotBoxes.get(0).localToStageCoordinates(Tmp.v1.set(0f, 0f)).y + slotHeight(0);
        }

        /** 整框拖动的落点索引：基于「基准堆叠」几何推算每个槽的中心（stage y），
         * 不会受拖动期间手工置位影响；与 insertIndexFor 语义一致（光标落在槽中心上方即插入其前，否则 append）。
         * @param baseTop 基准布局最顶框顶边的 stage y（拖动开始瞬间快照） */
        int computeInsert(float sx, float sy, float baseTop) {
            float y = baseTop;
            for (int i = 0; i < slotBoxes.size; i++) {
                float hh = slotHeight(i);
                y -= hh / 2f;
                if (sy > y) return i;
                y -= hh / 2f + 40f;
            }
            return slotBoxes.size;
        }

        /** 生成新槽位（插入到两框之间）并放入按钮 */
        void createSlotFor(Direction d, float sx, float sy) {
            removeFrom(d); // 先移除按钮并重排来源框视觉
            pruneEmptySlots(); // 立即清掉腾空的来源框，让索引基于剔除后的列表
            if (slotBoxes.size >= MAX_SLOTS) {
                placeInRed(d); // 仍无空位则退回红框
                return;
            }
            int insert = insertIndexFor(sx, sy);
            SlotBox box = new SlotBox(this);
            Seq<Direction> contents = new Seq<>();
            contents.add(d);
            slotBoxes.insert(insert, box);
            slotContents.insert(insert, contents);
            rebuildSlotContents(insert);
            rebuildSlots();
            syncWeights();
        }

        /** 放入红框 */
        void placeInRed(Direction d) {
            removeFrom(d);
            redButtons.add(d);
            rebuildRed();
            pruneEmptySlots();
            syncWeights();
        }

        /** 按权重放入对应槽位（初始化时用）：w=4→槽0, 3→槽1, 2→槽2, 1→槽3；槽不存在则创建 */
        void placeInSlotByWeight(Direction d, int w) {
            int idx = MAX_SLOTS - w;
            if (idx < 0 || idx >= MAX_SLOTS) {
                placeInRed(d);
                return;
            }
            while (slotBoxes.size <= idx) {
                slotBoxes.add(new SlotBox(this));
                slotContents.add(new Seq<Direction>());
            }
            removeFrom(d);
            slotContents.get(idx).add(d);
            rebuildSlotContents(idx);
        }

        /** 重建某个槽位的内部按钮布局：按钮组整体水平居中（用两侧expandX空白吸收剩余宽度） */
        void rebuildSlotContents(int i) {
            SlotBox box = slotBoxes.get(i);
            box.rebuildButtons(slotContents.get(i));
        }

        /** 整框换位：把 srcIdx 的白框整体移动到落点位置（拖动手柄触发） */
        void reorderBox(int srcIdx, float sx, float sy) {
            int insert = computeInsert(sx, sy, pristineTopOfStack()); // 基准堆叠几何，与拖动预览同口径
            SlotBox box = slotBoxes.remove(srcIdx);
            Seq<Direction> contents = slotContents.remove(srcIdx);
            if (insert > srcIdx) insert--; // 移除后索引前移
            insert = Mathf.clamp(insert, 0, slotBoxes.size);
            slotBoxes.insert(insert, box);
            slotContents.insert(insert, contents);
            rebuildSlotContents(insert);
            rebuildSlots();
            syncWeights();
        }

        /** 单个白框：左侧拖动手柄（整框排序），右侧按钮区（按钮可单独拖出/拖入） */
        class SlotBox extends Table {
            /** 按钮区：只有按钮会被重排 */
            final Table content = new Table();
            /** 拖拽悬停本框时，灰色占位按钮的插入位置（-1 表示不显示占位） */
            int previewInsert = -1;

            SlotBox(RegionState rs) {
                // 白框底
                background(Tex.whitePane);
                setColor(Color.white);
                margin(2f);
                touchable = Touchable.enabled;

                addListener(new InputListener() {
                    private Table ghost; // 整框拖拽影（跟随指针的「白框+内部黄按钮」整体）
                    private Table placeGhost; // 灰色落点占位框（root 层）
                    private float downX, downY; // 按下时的指针(stage)坐标，用于判定是否开始拖动
                    private float ghostW, ghostH; // 拖拽影的真实尺寸（灰色落点框与它对齐）
                    private boolean dragging; // 是否已进入整框拖动
                    private int srcIdx; // 拖动开始时被拖框的索引
                    private float[] baseBottoms; // 基准布局下各白框底边的 stage y（拖动开始瞬刻快照）
                    private float baseTop; // 基准布局下最顶白框顶边的 stage y
                    private Vec2 slotBase; // slotLayer 原点(stage)坐标，用于把 stage 几何转局部坐标置位

                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                        if (button == KeyCode.mouseMiddle) return false;
                        // 黄色按钮拖动进行中：不启动整框拖动
                        if (draggingButton) return false;
                        // 按在黄色 Direction 按钮（或其内部子元素）上时不启动整框拖动（按钮自带独立拖拽）
                        if (isDirectionTarget(event.targetActor)) return false;
                        downX = event.stageX;
                        downY = event.stageY;
                        return true;
                    }

                    /** 判断目标或其任意祖先是否为黄色 Direction 按钮 */
                    private boolean isDirectionTarget(Element e) {
                        while (e != null) {
                            if (e instanceof Direction) return true;
                            e = e.parent;
                        }
                        return false;
                    }

                    @Override
                    public void touchDragged(InputEvent event, float x, float y, int pointer) {
                        // 黄色按钮拖动进行中：不做整框重排
                        if (draggingButton) return;

                        if (!dragging) {
                            // 指针移动超过阈值才判定为「拖动」
                            if (Math.abs(event.stageX - downX) + Math.abs(event.stageY - downY) < 8f) return;
                            srcIdx = rs.slotBoxes.indexOf(SlotBox.this);
                            if (srcIdx < 0) return;
                            // 快照基准堆叠几何（只记录坐标，不修改布局树：不改 Cell/不增删子元素，
                            // 避免 arc 对触摸焦点元素的 unfocus 重入 touchUp 杀死拖拽）
                            baseBottoms = new float[rs.slotBoxes.size];
                            for (int i = 0; i < rs.slotBoxes.size; i++) {
                                baseBottoms[i] = rs.slotBoxes.get(i).localToStageCoordinates(Tmp.v1.set(0f, 0f)).y;
                            }
                            baseTop = baseBottoms[0] + rs.slotHeight(0);
                            slotBase = rs.slotLayer.localToStageCoordinates(Tmp.v2.set(0f, 0f)).cpy();

                            Table gh = buildGhost();
                            gh.touchable = Touchable.disabled;
                            ghostW = gh.getWidth();
                            ghostH = gh.getHeight();
                            gh.setPosition(event.stageX - ghostW / 2f, event.stageY - ghostH / 2f);
                            Core.scene.root.addChild(gh);
                            ghost = gh;
                            SlotBox.this.visible = false;
                            dragging = true;
                            Log.info("[UJ] whole-drag start, boxes=" + rs.slotBoxes.size);
                        }

                        Table gh = ghost;
                        if (gh == null) return;
                        gh.setPosition(event.stageX - gh.getWidth() / 2f, event.stageY - gh.getHeight() / 2f);
                        gh.toFront();
                        layoutDragPreview(event.stageX, event.stageY);
                        gh.toFront(); // 白色拖拽影始终在最上层
                    }

                    @Override
                    public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                        if (!dragging) return; // 单击（未拖动）：不换位、无痕迹
                        Log.info("[UJ] whole-drag end");
                        dragging = false;
                        float sx = event.stageX;
                        float sy = event.stageY;
                        if (ghost != null) {
                            ghost.remove();
                            ghost = null;
                        }
                        removePlaceGhost();
                        SlotBox.this.visible = true;
                        // 恢复基准布局后执行换位（此时本框触摸焦点已被 Scene.touchUp 移除，
                        // reorderBox 内 rebuildSlots 的 clearChildren 不会触发 unfocus 重入）
                        applyPristine();
                        int src = rs.slotBoxes.indexOf(SlotBox.this);
                        if (src >= 0) rs.reorderBox(src, sx, sy);
                    }

                    /** 构造整框拖拽影：白框底 + 左侧灰手柄 + 内部黄按钮（与真实框同样式），可整体拖动 */
                    private Table buildGhost() {
                        int srcIdx = slotBoxes.indexOf(SlotBox.this);
                        Seq<Direction> contents = srcIdx >= 0 ? slotContents.get(srcIdx) : new Seq<>();

                        Table g = new Table();
                        g.background(Tex.whitePane);
                        g.setColor(1f, 1f, 1f, 0.85f);
                        g.touchable = Touchable.disabled;
                        g.margin(0f).marginLeft(8f).marginRight(8f);

                        for (int i = 0; i < contents.size; i++) {
                            Direction d = contents.get(i);
                            Table btn = new Table() {
                                @Override
                                public void draw() {
                                    float pad = 5f;
                                    Fill.dropShadow(x + width / 2f, y + height / 2f, width + pad, height + pad, 10f, 0.9f * parentAlpha);
                                    Draw.color(0, 0, 0, 0.3f * parentAlpha);
                                    Fill.crect(x, y, width, height);
                                    Draw.reset();
                                    super.draw();
                                }
                            };
                            btn.background(Tex.whitePane);
                            btn.setColor(BTN_BORDER);
                            btn.margin(0f);
                            btn.touchable = Touchable.disabled;
                            btn.table(Tex.whiteui, t -> {
                                t.color.set(BTN_FACE);
                                t.margin(10f);
                                t.touchable = Touchable.disabled;
                                t.add("@universal-junction.dir" + d.dir).style(Styles.outlineLabel).color(Color.white)
                                        .grow().labelAlign(Align.center);
                            }).grow().pad(2f);
                            g.add(btn).growX().height(BTN_H).pad(4f).row();
                        }

                        g.pack();
                        g.setSize(SlotBox.this.getWidth(), g.getPrefHeight());
                        return g;
                    }

                    /** 把各白框置回「基准堆叠」的原始位置（纯手工 setPosition，不增删子元素、不改 Cell，
                     * 不触发弧布局重排——规避对触摸焦点元素的 unfocus 重入） */
                    private void applyPristine() {
                        float y = baseTop;
                        for (int i = 0; i < rs.slotBoxes.size; i++) {
                            SlotBox b = rs.slotBoxes.get(i);
                            float hh = rs.slotHeight(i);
                            b.setPosition(b.x, y - hh - slotBase.y);
                            y -= hh + 40f;
                        }
                    }

                    /** 整框拖拽预览：把「换位后的最终布局」手工置位到真实白框上（让位、收拢空隙），
                     * 并在落点绘制灰色占位框；几何口径与松手时的 reorderBox 完全一致，保证提示即实际落点 */
                    private void layoutDragPreview(float sx, float sy) {
                        int ins = rs.computeInsert(sx, sy, baseTop);
                        int drop = ins > srcIdx ? ins - 1 : ins;
                        drop = Mathf.clamp(drop, 0, rs.slotBoxes.size - 1);
                        if (drop == srcIdx) {
                            // 落回原位：恢复基准布局，在原位绘制灰色占位框（提示当前落点）
                            applyPristine();
                            float grayY = baseTop;
                            for (int i = 0; i < srcIdx; i++) {
                                grayY -= rs.slotHeight(i) + 40f;
                            }
                            drawPlaceGhost(grayY - ghostH, sx);
                            return;
                        }
                        Seq<SlotBox> order = new Seq<>();
                        for (SlotBox b : rs.slotBoxes) {
                            if (b != SlotBox.this) order.add(b);
                        }
                        order.insert(drop, SlotBox.this);
                        float y = baseTop;
                        float grayBottom = 0f;
                        for (SlotBox b : order) {
                            if (b == SlotBox.this) {
                                grayBottom = y - ghostH;
                                y -= ghostH + 40f;
                                continue;
                            }
                            float hh = rs.slotHeight(rs.slotBoxes.indexOf(b));
                            b.setPosition(b.x, y - hh - slotBase.y);
                            y -= hh + 40f;
                        }
                        drawPlaceGhost(grayBottom, sx);
                    }

                    /** 绘制灰色落点占位框到最终落点位置（stage 坐标，水平跟随鼠标但限制在大白框列内） */
                    private void drawPlaceGhost(float bottomStageY, float sx) {
                        float boxW = ghostW > 0 ? ghostW : SlotBox.this.getWidth();
                        float ph = ghostH > 0 ? ghostH : PLACE_H;
                        Vec2 cb = rs.column.localToStageCoordinates(Tmp.v1.set(0f, 0f));
                        float colLeft = cb.x;
                        float colRight = cb.x + rs.column.getWidth();
                        float marginX = 10f;
                        float minCx = Math.min(colLeft + marginX + boxW / 2f, colRight - marginX - boxW / 2f);
                        float maxCx = Math.max(colLeft + marginX + boxW / 2f, colRight - marginX - boxW / 2f);
                        float centerX = Mathf.clamp(sx, minCx, maxCx);

                        if (placeGhost != null && Math.abs(placeGhost.y - bottomStageY) < 1f
                                && Math.abs(placeGhost.x - (centerX - boxW / 2f)) < 1f) return;

                        if (placeGhost != null) placeGhost.remove();
                        placeGhost = new Table();
                        placeGhost.background(Tex.whitePane);
                        placeGhost.setColor(Color.gray);
                        placeGhost.setSize(boxW, ph);
                        placeGhost.touchable = Touchable.disabled;
                        placeGhost.setPosition(centerX - boxW / 2f, bottomStageY);
                        Core.scene.root.addChild(placeGhost);
                    }

                    private void removePlaceGhost() {
                        if (placeGhost != null) {
                            placeGhost.remove();
                            placeGhost = null;
                        }
                    }
                });

                // 布局：整框可拖动，内部按钮区
                content.touchable = Touchable.childrenOnly;
                add(content).grow();
            }

            /** 重建按钮区：按钮竖向排列，左右留小边距使按钮比白框略短；若 previewInsert>=0 则在对应位置插入灰色占位按钮 */
            void rebuildButtons(Seq<Direction> buttons) {
                // 重建前先取消本框及其按钮可能持有的触摸焦点，避免 detach 元素后下一事件派发到已离树的节点
                Core.scene.cancelTouchFocus(this);
                for (int i = 0; i < buttons.size; i++) Core.scene.cancelTouchFocus(buttons.get(i));
                content.clearChildren();
                content.margin(0f).marginLeft(8f).marginRight(8f);
                int placed = 0;
                for (int i = 0; i < buttons.size; i++) {
                    if (previewInsert == placed) {
                        content.add(placeholder()).growX().height(BTN_H).pad(4f).row();
                        placed++;
                    }
                    content.add(buttons.get(i)).growX().height(BTN_H).pad(4f).row();
                    placed++;
                }
                if (previewInsert >= placed) {
                    content.add(placeholder()).growX().height(BTN_H).pad(4f).row();
                }
                content.invalidateHierarchy();
                invalidateHierarchy();
            }

            /** 灰色占位按钮：提示按钮即将放入本框的位置 */
            private Table placeholder() {
                Table ph = new Table();
                ph.background(Tex.whitePane);
                ph.setColor(0.5f, 0.5f, 0.5f, 0.9f);
                ph.touchable = Touchable.disabled;
                return ph;
            }
        }
    }

    // ============================================================
    // 黄色按钮：可按住拖动，松手按落点放置
    // ============================================================

    public class Direction extends Table {
        final RegionState rs;
        final int dir;
        Table ghost; // 拖拽中的浮动影子
        RegionState.SlotBox srcSlotBox; // 拖出唯一按钮时被隐藏的来源白框（松手时恢复）

        // —— 拖动预览用的基准几何快照（dragStart 时采集，仅 setPosition 手绘，不增删布局树）——
        float boxBaseTop;        // 基准布局下最顶白框顶边的 stage y
        boolean boxReflowActive; // 是否已手动重排过白框（空白/新建槽位预览）
        Vec2 slotBase;           // slotLayer 原点(stage)坐标，用于把 stage 几何转局部置位
        int srcBoxIdx = -1;      // 来源白框索引（拖拽开始时按钮所在框），-1=不在框内
        boolean inBoxReflowActive; // 是否正在对来源框内按钮做手动重排
        float[] srcBtnBaseBottoms; // 来源框内各按钮底边 stage y 的快照（拖拽开始时）

        public Direction(RegionState rs, int dir) {
            this.rs = rs;
            this.dir = dir;

            background(Tex.whitePane);
            setColor(BTN_BORDER);
            margin(0f);
            touchable = Touchable.enabled;

            table(Tex.whiteui, t -> {
                t.color.set(BTN_FACE);
                t.addListener(new HandCursorListener());
                t.margin(10f);
                t.touchable = Touchable.enabled;
                t.add("@universal-junction.dir" + dir).style(Styles.outlineLabel).name("statement-name")
                        .color(Color.white).grow().labelAlign(Align.center);
            }).grow().pad(2f);

            row();

addListener(new InputListener() {
                private Table hint; // 灰色落点提示框（root 层，按钮大小一致）

                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                    if (event.targetActor instanceof Image) return false;
                    if (button == KeyCode.mouseMiddle) return false;
                    return dragStart(event);
                }

                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer) {
                    if (ghost != null) {
                        ghost.setPosition(event.stageX - ghost.getWidth() / 2f,
                                event.stageY - ghost.getHeight() / 2f);
                    }
                    updateDragPreview(event.stageX, event.stageY);
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                    if (ghost == null) return;
                    float sx = event.stageX;
                    float sy = event.stageY;

                    // 恢复来源白框可见性（拖空时曾隐藏）
                    if (Direction.this.srcSlotBox != null) {
                        Direction.this.srcSlotBox.visible = true;
                        Direction.this.srcSlotBox = null;
                    }

                    // 还原一切预览重排（白框/框内按钮回基准位），保证后续落点判断基于自然几何
                    restoreBoxPristine();
                    resetInBoxReflow();

                    // 判定落点
                    RegionState.SlotBox targetSlot = findTargetSlot(sx, sy);
                    boolean inRed = inRect(rs.redBox, sx, sy);
                    removeHint();
                    clearGhost();
                    draggingButton = false;
                    visible = true;

                    if (targetSlot != null) {
                        // 落入白框：在目标框内按钮序列的插入位置放置
                        int targetIdx = rs.slotBoxes.indexOf(targetSlot);
                        if (targetIdx >= 0) {
                            // 计算目标框内插入位置
                            int btnInsert = computeButtonInsert(targetSlot, sy);
                            rs.placeIntoSlot(Direction.this, targetIdx, btnInsert);
                        }
                    } else if (inRed) {
                        rs.placeInRed(Direction.this);
                    } else {
                        // 松手仍落在来源框内：留在原框、按落点排序，不新建槽位（避免被当成放框外）
                        RegionState.SlotBox srcBox = currentSrcBox();
                        if (srcBox != null && inRect(srcBox, sx, sy)) {
                            int srcIdx = rs.slotBoxes.indexOf(srcBox);
                            int btnInsert = baseInsertForSource(sy);
                            rs.placeIntoSlot(Direction.this, srcIdx, btnInsert);
                        } else if (rs.slotBoxes.size < MAX_SLOTS) {
                            rs.createSlotFor(Direction.this, sx, sy);
                        } else {
                            int cur = rs.locate(Direction.this);
                            if (cur >= 0) rs.placeInSlot(Direction.this, cur);
                            else rs.placeInRed(Direction.this);
                        }
                    }
                    resetDragState();
                }

                /** 主拖拽预览入口：判定落点目标并绘制灰色提示框（按钮等大 + 推开相邻占位） */
                private void updateDragPreview(float sx, float sy) {
                    RegionState.SlotBox targetSlot = findTargetSlot(sx, sy);
                    if (targetSlot != null) {
                        // 悬停在（非来源）白框上方 → 在该框内按钮序列位置插入灰色占位（实时改变白框大小）
                        int btnInsert = computeButtonInsert(targetSlot, sy);
                        leaveReflowStates();
                        showBoxPlaceholder(targetSlot, btnInsert);
                        return;
                    }
                    // 未落在其它白框：若仍落在来源框内（多按钮框内移动）→ 手动重排框内按钮并绘制按钮等大占位。
                    // 若来源框已被隐藏成空框（srcSlotBox，唯一按钮被拖出），则不再把它当框内目标，
                    // 其区域按空白处理 → 落在其上下时应显示新建槽位预览而非灰掉。
                    RegionState.SlotBox srcBox = currentSrcBox();
                    if (srcBox != null && srcBox != srcSlotBox && inRect(srcBox, sx, sy)) {
                        removeHint(); // 清除其它白框占位并复原布局
                        drawInBoxReflow(srcBox, baseInsertForSource(sy));
                        return;
                    }
                    boolean inRed = inRect(rs.redBox, sx, sy);
                    if (!inRed) {
                        // 离开所有白框/红框 → 清理框内/白框占位与重排，绘制「新建槽位」按钮等大占位并推开相邻白框
                        removeHint();
                        resetInBoxReflow();
                        drawBoxReflowPreview(sx, sy);
                        return;
                    }
                    // 落回红框：还原所有重排与占位
                    restoreBoxPristine();
                    resetInBoxReflow();
                    removeHint();
                }

                /** 离开框内重排/白框重排任一状态时统一还原（进入白框占位或落下前调用） */
                private void leaveReflowStates() {
                    restoreBoxPristine();
                    resetInBoxReflow();
                }

                /** 命中测试：指针落在哪个白框内（跳过来源框，避免重建来源框把持触摸焦点的按钮 detach 造成提前放置） */
                private RegionState.SlotBox findTargetSlot(float sx, float sy) {
                    for (int i = 0; i < rs.slotBoxes.size; i++) {
                        RegionState.SlotBox box = rs.slotBoxes.get(i);
                        if (box == srcSlotBox) continue; // 跳过隐藏的来源框（拖出唯一按钮时）
                        if (rs.slotContents.get(i).contains(Direction.this)) continue; // 跳过当前按钮所在框（多按钮框）
                        if (inRect(box, sx, sy)) return box;
                    }
                    return null;
                }

                /** 当前被拖按钮所在的来源框（任意按钮数量），null 表示不在任何白框内 */
                private RegionState.SlotBox currentSrcBox() {
                    for (int i = 0; i < rs.slotBoxes.size; i++) {
                        if (rs.slotContents.get(i).contains(Direction.this)) return rs.slotBoxes.get(i);
                    }
                    return null;
                }

                /** 基于「来源框基准布局」计算插入索引（使用 dragStart 快照的底边，不受框内重排位移影响，避免反馈抖动） */
                private int baseInsertForSource(float sy) {
                    if (srcBoxIdx < 0 || srcBoxIdx >= rs.slotContents.size) return 0;
                    Seq<Direction> contents = rs.slotContents.get(srcBoxIdx);
                    for (int i = 0; i < contents.size; i++) {
                        float bottom = (i < srcBtnBaseBottoms.length) ? srcBtnBaseBottoms[i]
                                : contents.get(i).localToStageCoordinates(Tmp.v1.set(0f, 0f)).y;
                        float centerY = bottom + BTN_H / 2f;
                        if (sy > centerY) return i;
                    }
                    return contents.size;
                }

                /** 计算按钮应插入目标白框内按钮序列的哪个位置（竖向比较中心 y） */
                private int computeButtonInsert(RegionState.SlotBox target, float sy) {
                    Seq<Direction> contents = null;
                    int tIdx = rs.slotBoxes.indexOf(target);
                    if (tIdx >= 0) contents = rs.slotContents.get(tIdx);
                    if (contents == null) return 0;
                    for (int i = 0; i < contents.size; i++) {
                        Direction d = contents.get(i);
                        Vec2 v = d.localToStageCoordinates(Tmp.v1.set(0f, 0f));
                        float centerY = v.y + d.getHeight() / 2f;
                        if (sy > centerY) return i;
                    }
                    return contents.size;
                }

                /** 在目标白框内放置灰色占位按钮：重建该框按钮区（含占位），使白框实时扩大并让占位与按钮一起排序。
                 * 只改目标框内容与高度，绝不 clearChildren slotLayer，避免把持触摸焦点的来源框 detach 造成提前放置。 */
                private void showBoxPlaceholder(RegionState.SlotBox target, int insertIdx) {
                    if (target == srcSlotBox) { removeHint(); return; } // 同框不实时改布局
                    if (target.previewInsert == insertIdx) { clearRootHint(); return; }
                    // 清除其它白框的占位（逐框复原，不整层重建）
                    for (int i = 0; i < rs.slotBoxes.size; i++) {
                        RegionState.SlotBox b = rs.slotBoxes.get(i);
                        if (b == target) continue;
                        if (b.previewInsert != -1) {
                            b.previewInsert = -1;
                            rs.rebuildSlotContents(i);
                            rs.setSlotHeight(i);
                        }
                    }
                    clearRootHint();
                    target.previewInsert = insertIdx;
                    int tIdx = rs.slotBoxes.indexOf(target);
                    if (tIdx >= 0) {
                        rs.rebuildSlotContents(tIdx);
                        rs.setSlotHeight(tIdx);
                    }
                }

                /** 框内排序预览：把来源框里的其它按钮手动重排进「去掉被拖按钮后 + 一个按钮等大占位」的槽位，
                 * 占位与按钮一起排位、绝不重叠。不改布局树（避免 detach 把持触摸焦点的按钮）。
                 * @param insertIdx 相对全部按钮（含被拖按钮）的插入索引 */
                private void drawInBoxReflow(RegionState.SlotBox box, int insertIdx) {
                    int idx = rs.slotBoxes.indexOf(box);
                    Seq<Direction> contents = idx >= 0 ? rs.slotContents.get(idx) : null;
                    if (contents == null || contents.size == 0) { removeHint(); return; }
                    inBoxReflowActive = true;
                    int n = contents.size;
                    int srcBtn = contents.indexOf(Direction.this);
                    // 去掉被拖按钮后，占位之前的可见按钮数 = previewRow
                    int previewRow = insertIdx - (srcBtn < insertIdx ? 1 : 0);
                    previewRow = Mathf.clamp(previewRow, 0, n - 1);

                    // 槽位顶取基准布局（dragStart 快照），避免被本次重排位移反馈影响
                    float slotTop = (srcBtnBaseBottoms != null && srcBtnBaseBottoms.length > 0)
                            ? srcBtnBaseBottoms[0] + BTN_H
                            : contents.get(0).localToStageCoordinates(Tmp.v1.set(0f, contents.get(0).getHeight())).y;
                    float pitch = BTN_H + 8f;

                    // 其余（可见）按钮按原序填入除 previewRow 外的各槽位
                    int v = 0;
                    Table content = box.content;
                    for (int row = 0; row < n; row++) {
                        if (row == previewRow) {
                            // 该行显示灰色占位（按钮等大，水平固定对齐按钮列，仅随鼠标上下移动）
                            showHintBox(fixedHintCenterX(),
                                    slotTop - (row + 1) * pitch, BTN_H, buttonWidth());
                            continue;
                        }
                        // 找到下一个可见（非被拖）按钮
                        while (v < n && contents.get(v) == Direction.this) v++;
                        if (v >= n) break;
                        Direction d = contents.get(v);
                        Vec2 l = content.stageToLocalCoordinates(Tmp.v1.set(0f, slotTop - (row + 1) * pitch));
                        d.setPosition(d.x, l.y);
                        v++;
                    }
                    // 说明：被拖按钮自身保持 visible=false（占位 row 显示灰色框），stay 原位
                    if (ghost != null) ghost.toFront();
                }

                /** 白框序列的新建槽位预览：手动把各白框重排，在落点处推开一个「按钮等大」的槽位并绘制灰色占位。
                 * 采用紧凑间距（只把落点处的 40 间隙扩到能容纳按钮），避免把相邻白框挤得太开。
                 * 若来源框已被隐藏成空框（srcSlotBox），一律把它从可见序列与插入索引中剔除，
                 * 与落下时的 pruneEmptySlots 口径一致，保证预览与真实落点吻合。 */
                private void drawBoxReflowPreview(float sx, float sy) {
                    // 可见白框 = 全部白框，剔除隐藏的空来源框 srcSlotBox
                    int nAll = rs.slotBoxes.size;
                    Seq<RegionState.SlotBox> vis = new Seq<>();
                    for (int i = 0; i < nAll; i++) {
                        RegionState.SlotBox b = rs.slotBoxes.get(i);
                        if (b != srcSlotBox) vis.add(b);
                    }
                    int n = vis.size;
                    if (n == 0) {
                        Vec2 cb = rs.column.localToStageCoordinates(Tmp.v1.set(0f, 0f));
                        float cx = cb.x + rs.column.getWidth() / 2f;
                        float cy = cb.y + rs.column.getHeight() / 2f;
                        showHintBox(cx, cy - BTN_H / 2f, BTN_H, buttonWidth());
                        return;
                    }
                    boxReflowActive = true;
                    float GAP = 40f;
                    float MARGIN = 8f;
                    // 各可见白框在「闭合堆叠」下的 top(stage)：从 boxBaseTop 起向下依次排布（剔除隐藏空框）
                    float[] top = new float[n];
                    float y = boxBaseTop;
                    for (int j = 0; j < n; j++) {
                        top[j] = y;
                        int bi = rs.slotBoxes.indexOf(vis.get(j));
                        y -= rs.slotHeight(bi) + GAP;
                    }
                    // 插入索引（含预览槽共 n+1 位，0..n）
                    int ins = 0;
                    for (int j = 0; j < n; j++) {
                        int bi = rs.slotBoxes.indexOf(vis.get(j));
                        float center = top[j] - rs.slotHeight(bi) / 2f;
                        if (sy > center) { ins = j; break; }
                        ins = j + 1;
                    }
                    ins = Mathf.clamp(ins, 0, n);
                    float previewBottom = 0f;
                    if (ins >= n) {
                        // 追加到最下方：各框不动，灰色框紧贴最后一个白框下方
                        int lb = rs.slotBoxes.indexOf(vis.get(n - 1));
                        float lastBottom = top[n - 1] - rs.slotHeight(lb);
                        previewBottom = lastBottom - MARGIN - BTN_H;
                        placeVisByTop(vis, top);
                    } else if (ins == 0) {
                        // 插到最顶部之上：白框不动，灰色框紧贴在最顶白框上方（留 MARGIN 间隙）。
                        // 原实现把整叠白框下移 BTN_H+MARGIN，但白框自身高度大于该移动量，
                        // 下移后的白框会覆盖住灰色框（与内部按钮重叠）——这里改为白框不挪、灰框浮在最上方。
                        previewBottom = boxBaseTop + MARGIN;
                        placeVisByTop(vis, top);
                    } else {
                        // 两框之间：ins..n-1 下移，使灰色框居中于扩大的间隙
                        int ui = rs.slotBoxes.indexOf(vis.get(ins - 1));
                        int li = rs.slotBoxes.indexOf(vis.get(ins));
                        float upperBottom = top[ins - 1] - rs.slotHeight(ui);
                        float shift = BTN_H + MARGIN - GAP;
                        for (int j = ins; j < n; j++) {
                            top[j] += shift;
                        }
                        previewBottom = upperBottom + (GAP + shift - BTN_H) / 2f;
                        placeVisByTop(vis, top);
                    }
                    showHintBox(fixedHintCenterX(), previewBottom, BTN_H, buttonWidth());
                    if (ghost != null) ghost.toFront();
                }

                /** 按各可见白框基准 top(stage) 置位（slotLayer 局部坐标） */
                private void placeVisByTop(Seq<RegionState.SlotBox> vis, float[] top) {
                    for (int j = 0; j < vis.size; j++) {
                        RegionState.SlotBox b = vis.get(j);
                        int bi = rs.slotBoxes.indexOf(b);
                        float hh = rs.slotHeight(bi);
                        b.setPosition(b.x, (top[j] - hh) - slotBase.y);
                    }
                }

                /** 把白框还原到基准堆叠（纯 setPosition，不动布局树；配合插入重排后的复原） */
                private void restoreBoxPristine() {
                    if (!boxReflowActive) return;
                    boxReflowActive = false;
                    if (rs.slotBoxes.size == 0) return;
                    float y = boxBaseTop;
                    for (int i = 0; i < rs.slotBoxes.size; i++) {
                        RegionState.SlotBox b = rs.slotBoxes.get(i);
                        float hh = rs.slotHeight(i);
                        b.setPosition(b.x, y - hh - slotBase.y);
                        y -= hh + 40f;
                    }
                }

                /** 还原来源框内按钮到基准位置（离开框内移动或落下前调用） */
                private void resetInBoxReflow() {
                    if (!inBoxReflowActive) return;
                    inBoxReflowActive = false;
                    if (srcBtnBaseBottoms == null || srcBoxIdx < 0 || srcBoxIdx >= rs.slotBoxes.size) return;
                    RegionState.SlotBox box = rs.slotBoxes.get(srcBoxIdx);
                    Seq<Direction> c = rs.slotContents.get(srcBoxIdx);
                    if (c.size != srcBtnBaseBottoms.length) return;
                    for (int i = 0; i < c.size; i++) {
                        Direction d = c.get(i);
                        Vec2 l = box.content.stageToLocalCoordinates(
                                Tmp.v1.set(0f, srcBtnBaseBottoms[i]));
                        d.setPosition(d.x, l.y);
                    }
                }

                /** 灰色占位框的水平中心：固定对齐黄色按钮列（stage 坐标），只随鼠标上下移动，水平不动 */
                private float fixedHintCenterX() {
                    float x = Direction.this.localToStageCoordinates(Tmp.v1.set(0f, 0f)).x;
                    return x + Direction.this.getWidth() / 2f;
                }

                private float buttonWidth() {
                    float w = Direction.this.getWidth();
                    return w > 0 ? w : rs.column.getWidth() - 30f;
                }

                /** 绘制/更新按钮等大灰色占位框（stage 坐标） */
                private void showHintBox(float centerX, float bottomY, float h, float w) {
                    if (hint != null && Math.abs(hint.x - (centerX - w / 2f)) < 1f
                            && Math.abs(hint.y - bottomY) < 1f) return;
                    if (hint != null) hint.remove();
                    hint = new Table();
                    hint.background(Tex.whitePane);
                    hint.setColor(Color.gray);
                    hint.setSize(w, h);
                    hint.touchable = Touchable.disabled;
                    hint.setPosition(centerX - w / 2f, bottomY);
                    Core.scene.root.addChild(hint);
                    if (ghost != null) ghost.toFront();
                }

                private void clearRootHint() {
                    if (hint != null) {
                        hint.remove();
                        hint = null;
                    }
                }

                private void removeHint() {
                    clearRootHint();
                    // 清除所有白框的占位并重建（逐框复原，去掉占位恢复原大小，不整层重建）
                    for (int i = 0; i < rs.slotBoxes.size; i++) {
                        RegionState.SlotBox b = rs.slotBoxes.get(i);
                        if (b.previewInsert != -1) {
                            b.previewInsert = -1;
                            rs.rebuildSlotContents(i);
                            rs.setSlotHeight(i);
                        }
                    }
                }
            });
        }

        private boolean dragStart(InputEvent event) {
            if (build == null) return false;
            draggingButton = true;
            resetDragState();
            ghost = new Table();
            ghost.background(Tex.whitePane);
            ghost.setColor(BTN_BORDER);
            ghost.margin(0f);
            ghost.table(Tex.whiteui, t -> {
                t.color.set(BTN_FACE);
                t.margin(10f);
                t.touchable = Touchable.disabled;
                t.add("@universal-junction.dir" + dir).style(Styles.outlineLabel).color(Color.white)
                        .grow().labelAlign(Align.center);
            }).grow().pad(2f);
            ghost.setSize(getWidth(), getHeight());
            ghost.touchable = Touchable.disabled;
            ghost.setPosition(event.stageX - getWidth() / 2f, event.stageY - getHeight() / 2f);
            Core.scene.root.addChild(ghost);
            toFront();
            ghost.toFront();
            visible = false;
            // 拖出唯一按钮时隐藏来源白框（松手时恢复）
            for (int i = 0; i < rs.slotBoxes.size; i++) {
                if (rs.slotContents.get(i).contains(Direction.this) && rs.slotContents.get(i).size == 1) {
                    RegionState.SlotBox box = rs.slotBoxes.get(i);
                    box.visible = false;
                    Direction.this.srcSlotBox = box;
                    break;
                }
            }
            // 快照白框基准堆叠几何（空白/新建槽位预览用，与整框拖动同口径）
            if (rs.slotBoxes.size > 0) {
                boxBaseTop = rs.slotBoxes.get(0).localToStageCoordinates(Tmp.v1.set(0f, 0f)).y
                        + rs.slotHeight(0);
                slotBase = rs.slotLayer.localToStageCoordinates(Tmp.v2.set(0f, 0f)).cpy();
            }
            // 快照来源框内按钮底边（框内排序预览用）
            for (int i = 0; i < rs.slotBoxes.size; i++) {
                if (rs.slotContents.get(i).contains(Direction.this)) {
                    srcBoxIdx = i;
                    Seq<Direction> c = rs.slotContents.get(i);
                    srcBtnBaseBottoms = new float[c.size];
                    for (int j = 0; j < c.size; j++) {
                        srcBtnBaseBottoms[j] = c.get(j).localToStageCoordinates(Tmp.v1.set(0f, 0f)).y;
                    }
                    break;
                }
            }
            return true;
        }

        /** 清空本轮拖拽的预览状态（新拖拽开始或拖拽结束时） */
        private void resetDragState() {
            boxReflowActive = false;
            inBoxReflowActive = false;
            srcBoxIdx = -1;
            srcBtnBaseBottoms = null;
        }

        private void clearGhost() {
            draggingButton = false;
            if (ghost != null) {
                ghost.remove();
                ghost = null;
            }
        }

        private boolean inRect(Table t, float sx, float sy) {
            if (t == null) return false;
            Vec2 v = t.localToStageCoordinates(Tmp.v1.set(0f, 0f));
            float x = v.x, y = v.y;
            return sx >= x && sx <= x + t.getWidth() && sy >= y && sy <= y + t.getHeight();
        }

        @Override
        public void draw() {
            float pad = 5f;
            Fill.dropShadow(x + width / 2f, y + height / 2f, width + pad, height + pad, 10f, 0.9f * parentAlpha);

            Draw.color(0, 0, 0, 0.3f * parentAlpha);
            Fill.crect(x, y, width, height);
            Draw.reset();

            super.draw();
        }
    }
}
