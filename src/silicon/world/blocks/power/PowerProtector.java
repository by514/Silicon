package silicon.world.blocks.power;

import arc.Core;
import arc.audio.Sound;
import arc.files.Fi;
import arc.graphics.Color;
import arc.math.Interp;
import arc.math.Mathf;
import arc.scene.actions.Actions;
import arc.scene.event.Touchable;
import arc.scene.style.NinePatchDrawable;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.util.*;
import arc.util.io.Reads;
import arc.util.io.Writes;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.core.UI;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.logic.LAccess;
import mindustry.ui.Bar;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.world.Tile;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.sandbox.PowerVoid;
import mindustry.world.meta.Env;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;

import static mindustry.Vars.control;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tree;
import static mindustry.Vars.ui;
import static mindustry.content.Blocks.powerVoid;
import static silicon.Vars.*;

/**
 * PowerProtector - 存档级共享状态的电力保护器
 * <p>
 * 架构：
 * - 数据字段写在每个保护器的存档中（write/read），属于存档层面而非游戏全局静态
 * - 同一电网同队伍的保护器共享同一个 State 引用（模式/冲突/提示/会话时间，由最早放置的作 Master 维护）
 * - 全队共享"可用保护时间"：跨电网由队伍 Master 统一维护消耗与回充，每台保护器持有副本用于存档
 * - 欠款（debt）每台保护器独立计算与偿还
 * - 保护时按每台 1x 速率扣减全队时间池；全队无欠款时才回充
 * - 模式仅为显示文本：根据实际在供电/消耗/空闲/阻塞 自动推导，不控制逻辑
 * - 供电/消耗逻辑独立：满足条件即执行，不受模式切换影响
 */
public class PowerProtector extends PowerGenerator {
    /** 保护总时长（tick），默认 90 秒 */
    public float protectionTime = 90 * 60f;
    /** 恢复利率（每秒） */
    public float recoveryRatePerSecond = 0.02f; // 2%/s
    /** 偿还手续费比例（10%） */
    public float recoverySurcharge = 0.1f; // 10%
    /** 恢复间隔秒数：每达到该时长线性恢复 1 秒可用保护时间 */
    public float restoreInterval = 5f; // 5s 恢复 1s
    /** 欠款进度条满格对应的欠款值（仅用于显示归一化） */
    public float maxDebt = 100000f;
    /** 预热动画速度 */
    public float warmupSpeed = 0.1f;

    /** 启用按钮样式：与 flatTogglet 相同，但 checked 高亮边框为红色（Pal.remove）。懒加载以避免 Styles 类初始化顺序问题 */
    private static TextButtonStyle redToggle;

    private static TextButtonStyle redToggle() {
        if (redToggle == null) {
            redToggle = new TextButtonStyle(){{
                font = Fonts.def;
                fontColor = Color.white;
                up = Styles.flatTogglet.up;
                over = Styles.flatTogglet.over;
                down = ((NinePatchDrawable)Styles.flatDown).tint(Pal.remove);
                checked = down;
                disabled = Styles.flatTogglet.disabled;
                disabledFontColor = Color.gray;
            }};
        }
        return redToggle;
    }

    // 拆除提示节流（避免 validBreak 轮询时刷屏）
    private static float lastBreakToast = Float.NEGATIVE_INFINITY;

    // #39/PowerProtector 性能：按队缓存保护器列表，只在建筑增删/重叠变化时重建，
    // 避免每帧每台都遍历全局 Groups.build（O(N×M)）。运行时仍逐台校验当前电网状态。
    private static final ObjectMap<Team, Seq<Building>> protectorCache = new ObjectMap<>();
    private static boolean protectorDirty = true;

    private static void markProtectorDirty() {
        protectorDirty = true;
    }

    private static Seq<Building> protectors(Team team) {
        if (protectorDirty) {
            protectorDirty = false;
            protectorCache.clear();
            for (Building b : Groups.build) {
                if (b instanceof PowerProtectorBuild) {
                    protectorCache.get(b.team, Seq::new).add(b);
                }
            }
        }
        return protectorCache.get(team, Seq.with());
    }

    public PowerProtector(String name) {
        super(name);
        update = true;
        solid = true;
        consumesPower = true;
        outputsPower = true;
        size = 2;
        health = 600;
        envEnabled = Env.any;
        configurable = true;
        saveConfig = false;
        displayFlow = false;
        drawArrow = false;
        // 不可被其他方块覆盖替换（放置时红色无效）
        replaceable = false;
        // 动态消费：恢复时按 tickRPower 消耗，否则不消耗
        consumePowerDynamic(entity -> {
            PowerProtectorBuild ppb = (PowerProtectorBuild) entity;
            return ppb.state != null ? ppb.state.tickRPower : 0f;
        }).optional(false, false);
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.repairTime, protectionTime / (60 * 60), StatUnit.minutes);
    }

    @Override
    public void setBars() {
        super.setBars();

        // 状态：满格，颜色随模式变化（与配置面板状态文字一致）
        addBar("status", (PowerProtectorBuild entity) -> new Bar(
                entity::modeText,
                entity::modeColor,
                () -> 1f));

        // 可用保护时间：青色，与配置面板剩余时间一致
        addBar("available", (PowerProtectorBuild entity) -> new Bar(
                () -> Core.bundle.get("block.silicon-power-protector.ui.availableTime"),
                () -> Color.cyan,
                () -> Mathf.clamp(entity.state.remainingProtectionTime / protectionTime)));

        // 欠下电力：淡橙，与配置面板欠款文字颜色一致
        addBar("debt", (PowerProtectorBuild entity) -> new Bar(
                () -> Core.bundle.get("block.silicon-power-protector.ui.totalSpent"),
                () -> Pal.powerBar,
                () -> Mathf.clamp((float) (entity.state.debt / maxDebt))));
    }

    @Override
    public boolean canBreak(Tile tile) {
        // 欠下电力时不可拆除（防止通过拆除抹掉欠款）
        if(tile != null && tile.build instanceof PowerProtectorBuild ppb && ppb.state != null && ppb.state.debt > 0){
            if(Time.time - lastBreakToast >= 90f){
                lastBreakToast = Time.time;
                if(!mindustry.Vars.headless && !state.isMenu()){
                    ppb.showCannotBreakBanner();
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean canPlaceOn(Tile tile, mindustry.game.Team team, int rotation) {
        return true;
    }

    /** 运行时模式（纯显示） */
    public enum Mode {
        Normal,      // 未启用
        Protecting,  // 保护中（正在供电）
        Recovering,  // 恢复中（正在消耗偿还）
        Blocked,     // 被阻塞（同电网有其他保护器在工作）
        Error,       // 错误（同电网多保护器冲突）
        Stopped      // 手动停止（不供电不恢复）
    }

    /** 共享状态数据（存档字段 + 运行时临时变量） */
    public static class State {
        // ===== 存档字段（每个保护器都写入/读取）=====
        /** 全队共享可用保护时间（tick）。由队伍 Master 统一维护，其余成员每帧拷贝同一份副本 */
        public float remainingProtectionTime = 90 * 60f;
        /** 自家欠下电力（每台保护器独立计算与偿还，不同步） */
        public double debt = 0;
        /** 全队回充累积器（tick）。与时间池一样为全队副本 */
        public float restoreTimer = 0f;
        /** 同电网冲突标记（网格级，读网格 Master 的 state） */
        public boolean error = false;
        /** 手动停止运行（自家） */
        public boolean stopped = false;

        // ===== 运行时临时变量（不存档）=====
        public Mode mode = Mode.Normal;                   // 当前显示模式（网格级）
        public float protectionTimer = 0f;                // 本次保护已用时间（网格级会话）
        public float tickPPower = 0f, lastTickPPower = 0f; // 供电相关（自家）
        public float tickRPower = 0f, lastTickRPower = 0f; // 恢复消耗相关（自家）
        public double rPowerPrincipal = 0;                // 均摊本金（自家）
        public float announceDelay = 0f;                  // 提示延迟（网格级）
        public boolean announced = false;                 // 已提示（网格级）
    }

    public class PowerProtectorBuild extends GeneratorBuild {
        // 运行时共享状态引用（指向同电网 Master 的 State）
        private State shared;

        // 全队共享时间池维护者（跨电网选举 id 最小的同队保护器，仅它执行消耗与回充）
        private PowerProtectorBuild teamMaster;

        // 间隔检测
        private final Interval interval = new Interval();

        // 电力不足警报音（仅随提示横幅播放一次）
        private Sound warnSfx;

        // 任何保护器加入/离开/重叠变化都要使按队缓存失效
        @Override
        public void onProximityAdded() {
            super.onProximityAdded();
            markProtectorDirty();
        }

        @Override
        public void onRemoved() {
            super.onRemoved();
            markProtectorDirty();
        }

        @Override
        public void updateTile() {
            // 1. 同步引用：电网共享（模式/冲突/提示）+ 全队时间池
            syncSharedState();
            syncTeamState();

            State s = shared;
            if (s == null) return; // 无电网或无 Master

            // 2. 检测冲突/阻塞
            detectConflictAndBlock();

            // 3. 计算电网状态
            float stored = powerStored.get(this);
            float capacity = powerCapacity.get(this);
            float changed = powerChanged.get(this); // 负值=净消耗
            float gridNet = gridNetWithoutSelf(); // 电网自身净盈余（不含本保护器）
            boolean hasStorage = capacity > Mathf.FLOAT_ROUNDING_ERROR && stored > Mathf.FLOAT_ROUNDING_ERROR;

            // 3.1 判断实际工作条件（与模式无关，纯逻辑）
            // 保护模式：电网亏电 && 无存储电力 && 无冲突 && 全队时间池有剩余
            boolean canProtect = state.remainingProtectionTime > Mathf.FLOAT_ROUNDING_ERROR
                    && !hasStorage
                    && gridNet < 0f
                    && power.graph != null && power.graph.all.size > 0
                    && !s.error
                    && !isBlockedByOther()
                    && !state.stopped;

            // 恢复：自家有欠款 && 电网富余
            boolean shouldRecover = state.debt > 0
                    && gridNet > 0f
                    && power.graph != null
                    && !state.stopped;

            // 未启用：自家无欠款 && 电网富余 && 无冲突
            boolean isNormal = state.debt == 0
                    && gridNet > 0f
                    && !s.error
                    && !isBlockedByOther()
                    && !state.stopped;

            // 3.2 更新显示模式（纯根据实际工作状态推导）
            if (state.stopped) {
                s.mode = Mode.Stopped;
            } else if (s.error) {
                s.mode = Mode.Error;
            } else if (isBlockedByOther()) {
                s.mode = Mode.Blocked;
            } else if (canProtect) {
                s.mode = Mode.Protecting;
            } else if (shouldRecover) {
                s.mode = Mode.Recovering;
            } else if (isNormal) {
                s.mode = Mode.Normal;
            } else {
                s.mode = Mode.Normal; // 兜底
            }

            // 3.3 全队共享时间池：仅由队伍 Master 执行消耗与回充
            if (teamMaster == this) {
                manageTeamTimePool();
            }

            // 4. 执行供电逻辑（保护）
            if (canProtect) {
                handleProtection(powerStored.get(this), powerChanged.get(this));
            } else {
                state.tickPPower = state.lastTickPPower = 0f;
                s.protectionTimer = 0f;
                s.announceDelay = 0f;
                s.announced = false;
            }

            // 5. 执行恢复逻辑（恢复）
            if (shouldRecover) {
                handleRecovery(gridNetWithoutSelf());
            } else {
                state.tickRPower = state.lastTickRPower = 0f;
                state.rPowerPrincipal = 0;
            }

            // 6. UI 刷新
            if (configTable != null && control.input.config.isShown() && control.input.config.getSelected() == this) {
                updateConfigUI();
            }
        }

        /** 同步电网共享引用：选举同电网同队伍 Master，所有保护器指向同一个 State（模式/冲突/提示/会话时间） */
        private void syncSharedState() {
            if (power == null || power.graph == null) {
                shared = null;
                return;
            }

            // 找到同电网同队伍的所有保护器，选 id 最小的作为 Master
            PowerProtectorBuild master = null;
            for (Building b : power.graph.all) {
                if (b instanceof PowerProtectorBuild ppb && ppb.team == team) {
                    if (master == null || b.id < master.id) {
                        master = ppb;
                    }
                }
            }

            shared = master != null ? master.state : null;
        }

        /** 同步全队时间池：跨电网选举 id 最小且有电网的同队保护器作为队伍 Master，其余成员每帧拷贝时间池存档字段 */
        private void syncTeamState() {
            teamMaster = null;
            PowerProtectorBuild master = null;
            // 走按队缓存（仅在建筑增删时重建），替代每帧遍历全图 Groups.build
            for (Building b : protectors(team)) {
                if (b instanceof PowerProtectorBuild ppb
                        && ppb.power != null && ppb.power.graph != null) {
                    if (master == null || b.id < master.id) {
                        master = ppb;
                    }
                }
            }

            if (master != null) {
                teamMaster = master;
                if (master != this) {
                    // 同步全队共享时间池（欠款各台独立，不同步）
                    this.state.remainingProtectionTime = master.state.remainingProtectionTime;
                    this.state.restoreTimer = master.state.restoreTimer;
                }
            }
        }

        /** 维护全队共享时间池：保护时按每台 1x 扣减，全队无欠款才回充。仅队伍 Master 调用 */
        private void manageTeamTimePool() {
            float activeCnt = 0f;
            boolean anyDebt = false;

            for (Building b : protectors(team)) {
                if (!(b instanceof PowerProtectorBuild ppb)) continue;

                if (ppb.power != null && ppb.power.graph != null && ppb.shared != null && ppb.shared.mode == Mode.Protecting) {
                    activeCnt += 1f;
                }
                if (ppb.state.debt > 0) anyDebt = true;
            }

            // 消耗：正在保护的每台保护器按 1x 速率扣减共享时间池（Time.delta 为 tick，1 秒 = 60 tick）
            if (activeCnt > 0f) {
                state.remainingProtectionTime = Math.max(0f, state.remainingProtectionTime - activeCnt * Time.delta);
            }

            // 回充：全队无欠款且未满时线性持续恢复，每 restoreInterval 秒恢复 1 秒可用时间（Time.delta 为 tick）
            if (!anyDebt && state.remainingProtectionTime < protectionTime) {
                state.remainingProtectionTime = Math.min(protectionTime, state.remainingProtectionTime + Time.delta / restoreInterval);
            }
        }

        /** 检测冲突与阻塞 */
        private void detectConflictAndBlock() {
            shared.error = false;
            if (power.graph != null) {
                int count = 0;
                for (Building b : power.graph.all) {
                    if (b instanceof PowerProtectorBuild ppb && ppb.team == team) {
                        count++;
                    }
                }
                if (count > 1) shared.error = true;
            }
            if (!shared.error && power.graph != null) {
                for (Building b : power.graph.all) {
                    if (b.block instanceof PowerVoid) return;
                }
            }
        }

        /** 是否被同电网其他正在工作的保护器阻塞 */
        private boolean isBlockedByOther() {
            if (power.graph == null) return false;
            for (Building b : power.graph.all) {
                if (b instanceof PowerProtectorBuild other && other != this && other.team == team) {
                    if (other.shared != null && (other.shared.mode == Mode.Protecting || other.shared.mode == Mode.Recovering)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** 电网自身净盈余（不含本保护器） */
        private float gridNetWithoutSelf() {
            if (power == null || power.graph == null) return 0f;
            float produced = power.graph.getPowerProduced();
            float needed = power.graph.getPowerNeeded();
            float selfProduced = shared.mode == Mode.Protecting ? state.tickPPower : 0f;
            float selfConsumed = shared.mode == Mode.Recovering ? state.tickRPower : 0f;
            return (produced - selfProduced) - (needed - selfConsumed);
        }

        /** 保护供电：填补电网缺口 */
        private void handleProtection(float stored, float changed) {
            shared.protectionTimer += Time.delta;

            state.tickPPower = Math.max(-(changed - state.lastTickPPower) - stored, 0f);
            state.lastTickPPower = state.tickPPower;

            state.debt = Math.min(state.debt + state.tickPPower, Double.MAX_VALUE);

            if (!shared.announced && state.tickPPower > 0f) {
                shared.announceDelay += Time.delta;
                if (shared.announceDelay >= 20f) {
                    shared.announced = true;
                    if (player != null && team == player.team()) {
                        showPowerShortageBanner();
                    }
                }
            }
        }

        /** 恢复消耗：从电网富余+电池偿还自家欠款 */
        private void handleRecovery(float gridNet) {
            if (state.debt <= 0 || Double.isNaN(state.debt)) return;

            float net = gridNetWithoutSelf();
            if (net < 0f) {
                state.tickRPower = state.lastTickRPower = 0f;
                return;
            }

            float surplus = Math.max(0f, net);
            float batteryAvailable = power.graph != null ? power.graph.getBatteryStored() : 0f;
            float available = surplus + batteryAvailable;

            double interest = state.debt * recoveryRatePerSecond / 60.0;
            float desiredRate = (float) ((state.debt / Math.max(shared.protectionTimer, 1f) + interest) * (1f + recoverySurcharge));

            state.tickRPower = Math.min(desiredRate, Math.max(0f, available));

            float repayRatio = Math.max(repayStatus(), 0.01f);
            state.lastTickRPower = state.tickRPower * repayRatio;

            state.debt -= state.lastTickRPower;
            if (state.debt < 0) state.debt = 0;

            state.rPowerPrincipal = state.debt / Math.max(shared.protectionTimer, 1f);
        }

        /** 播放电力不足警报音（与提示横幅绑定，横幅出现时播放一次；若该音效当前正在播放则取消本次，避免高频进出保护时声音重叠） */
        private void playWarnSfx() {
            if (mindustry.Vars.headless) return;
            if (warnSfx == null) {
                for (String path : new String[]{"sounds/warn/power-protector.ogg", "assets/sounds/warn/power-protector.ogg"}) {
                    Fi f = tree.get(path);
                    if (f.exists()) {
                        warnSfx = new Sound(f);
                        break;
                    }
                }
            }
            if (warnSfx != null && warnSfx.countPlaying() <= 0) warnSfx.play();
        }

        private void showPowerShortageBanner() {
            playWarnSfx();
            if (bannerTable != null) return;
            Table t = new Table(Styles.black3);
            t.touchable = Touchable.disabled;
            t.margin(8f);
            Label label = t.add(Core.bundle.format("block.silicon-power-protector.announce.powerShortageTime", "999.0"))
                    .style(Styles.outlineLabel).padLeft(14f).get();
            label.setAlignment(Align.left);
            label.update(() -> {
                float remainingSec = Math.max(0f, state.remainingProtectionTime / 60f);
                label.setText(Core.bundle.format("block.silicon-power-protector.announce.powerShortageTime",
                        Strings.fixed(remainingSec, 1)));
                label.setColor(Tmp.c1.set(Color.orange).lerp(Color.scarlet, Mathf.absin(Time.time, 2f, 1f)));
            });
            t.update(() -> {
                t.pack();
                t.setPosition(6f, Core.graphics.getHeight() * 0.6f, Align.topLeft);
                if (shared.mode != Mode.Protecting || mindustry.Vars.state.isMenu() || !ui.hudfrag.shown) {
                    if (bannerTable == t) bannerTable = null;
                    t.remove();
                }
            });
            bannerTable = t;
            t.pack();
            t.act(0.1f);
            // 参照原版 HUD 层级：挂到 hudGroup（先于弹窗加入 Core.scene），菜单/弹窗自然在其上方
            ui.hudGroup.addChild(t);
        }

        /** 禁止拆除提示横幅：位于电力不足横幅上方并与其左对齐，文字也左对齐，短暂显示后消失 */
        private void showCannotBreakBanner() {
            if(breakBannerTable != null) return;
            Table t = new Table(Styles.black3);
            t.touchable = Touchable.disabled;
            t.margin(8f);
            Label label = t.add(Core.bundle.get("block.silicon-power-protector.ui.cannotBreak"))
                    .style(Styles.outlineLabel).padLeft(2f).get();
            label.setAlignment(Align.left);
            t.update(() -> {
                t.pack();
                // 电力不足横幅上方，左对齐（y 为顶边，更小 y = 更靠屏幕上方）
                float y = bannerTable != null
                        ? bannerTable.getY(Align.top) - t.getPrefHeight() - 4f
                        : Core.graphics.getHeight() * 0.6f - 24f;
                t.setPosition(6f, y, Align.topLeft);
                if(mindustry.Vars.state.isMenu() || !ui.hudfrag.shown){
                    if(breakBannerTable == t) breakBannerTable = null;
                    t.remove();
                }
            });
            t.actions(Actions.fadeOut(2.4f, Interp.pow4In), Actions.run(() -> {
                if(breakBannerTable == t) breakBannerTable = null;
            }), Actions.remove());
            breakBannerTable = t;
            t.pack();
            t.act(0.1f);
            ui.hudGroup.addChild(t);
        }

        @Override
        public float getPowerProduction() {
            return shared != null && shared.mode == Mode.Protecting ? state.tickPPower : 0f;
        }

        @Override
        public float warmup() {
            return warmupSpeed;
        }

        @Override
        public byte version() {
            return 14;
        }

        @Override
        public double sense(LAccess sensor) {
            if (sensor == LAccess.powerNetStored) return powerStored.get(this);
            if (sensor == LAccess.powerNetCapacity) return powerCapacity.get(this);
            if (sensor == LAccess.efficiency) return shouldConsume() ? efficiency : 0f;
            return super.sense(sensor);
        }

        // ===== UI 配置面板 =====
        private Table configTable = null;
        private Label statusLabel = null, remainingLabel = null, debtLabel = null, supplyLabel = null;
        private TextButton stopButton = null;
        private Table bannerTable = null, breakBannerTable = null;

        /** 当前显示模式文案（与方块进度条共用） */
        public String modeText() {
            Mode m = shared != null ? shared.mode : Mode.Normal;
            return switch (m) {
                case Protecting -> Core.bundle.get("block.silicon-power-protector.protection");
                case Recovering -> Core.bundle.get("block.silicon-power-protector.recovery");
                case Blocked -> Core.bundle.get("block.silicon-power-protector.blocked");
                case Error -> Core.bundle.get("block.silicon-power-protector.error");
                case Stopped -> Core.bundle.get("block.silicon-power-protector.stopped");
                default -> Core.bundle.get("block.silicon-power-protector.normal");
            };
        }

        /** 当前显示模式颜色（恢复模式与剩余时间保持一致青色） */
        public Color modeColor() {
            Mode m = shared != null ? shared.mode : Mode.Normal;
            return switch (m) {
                case Protecting -> Color.green;
                case Recovering -> Color.cyan;
                case Blocked -> Color.red;
                case Error -> Color.red;
                case Stopped -> Color.gray;
                default -> Color.white;
            };
        }

        @Override
        public void buildConfiguration(Table table) {
            this.configTable = table;
            table.top();

            // 内容包裹表：背景 + 内边距，宽度由内容撑开
            Table inner = new Table();
            inner.background(Tex.pane);
            inner.margin(8f, 12f, 8f, 12f);
            table.add(inner).growX();

            // ── 状态徽章 ──
            inner.table(status -> {
                Image dot = status.image(Tex.whiteui).size(10f).padRight(6f).get();
                dot.update(() -> dot.setColor(modeColor()));
                statusLabel = status.add("").style(Styles.outlineLabel).get();
            }).colspan(2).center().padBottom(8f).row();

            // ── 可用保护时间 ──
            inner.table(t -> {
                t.add(Core.bundle.get("block.silicon-power-protector.ui.availableTime"))
                    .color(Color.lightGray).left().growX();
                remainingLabel = t.add("").color(Color.cyan).right().get();
            }).colspan(2).growX().padBottom(4f).row();

            // ── 欠下电力 ──
            inner.table(t -> {
                t.add(Core.bundle.get("block.silicon-power-protector.ui.totalSpent"))
                    .color(Color.lightGray).left().growX();
                debtLabel = t.add("").color(Pal.powerBar).right().get();
            }).colspan(2).growX().padBottom(4f).row();

            // ── 当前供电 ──
            inner.table(t -> {
                t.add(Core.bundle.get("block.silicon-power-protector.ui.currentSupply"))
                    .color(Color.lightGray).left().growX();
                supplyLabel = t.add("").right().get();
            }).colspan(2).growX().padBottom(8f).row();

            // ── 启用/禁用按钮 ──
            stopButton = inner.button("", redToggle(), () -> {
                state.stopped = !state.stopped;
                updateConfigUI();
            }).colspan(2).height(40f).growX().get();
            stopButton.getLabel().setAlignment(Align.center);
            stopButton.getLabel().setFontScale(1.1f);

            updateConfigUI();
        }

        @Override
        public void onConfigureClosed() {
            configTable = null;
            statusLabel = null;
            remainingLabel = null;
            debtLabel = null;
            supplyLabel = null;
            stopButton = null;
        }

        private void updateConfigUI() {
            if (configTable == null) return;

            // 启用/禁用按钮
            if (stopButton != null) {
                stopButton.setChecked(state.stopped);
                stopButton.setText(state.stopped
                    ? Core.bundle.get("block.silicon-power-protector.ui.disableRun")
                    : Core.bundle.get("block.silicon-power-protector.ui.enableRun"));
            }

            // 状态徽章
            if (statusLabel != null) {
                statusLabel.setText(modeText());
                statusLabel.setColor(modeColor());
            }

            // 可用保护时间
            if (remainingLabel != null) {
                float sec = Math.max(0f, state.remainingProtectionTime / 60f);
                remainingLabel.setText(Strings.fixed(sec, 1) + "s");
            }

            // 欠下电力
            if (debtLabel != null) debtLabel.setText(UI.formatAmount((long) state.debt));

            // 当前供电
            if (supplyLabel != null) {
                float supply = shared != null && shared.mode == Mode.Protecting ? state.tickPPower * 60f : 0f;
                supplyLabel.setText(Strings.fixed(supply, 1) + "/s");
                supplyLabel.setColor(shared != null && shared.mode == Mode.Protecting ? Color.green : Color.gray);
            }
        }

        public float repayStatus() {
            return power == null ? 0f : Mathf.clamp(power.status);
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            // remainingProtectionTime/restoreTimer 为全队一致的时间池副本，debt 为各台自己的欠款
            write.f(state.remainingProtectionTime);
            write.d(state.debt);
            write.f(state.restoreTimer);
            write.b(state.error ? 1 : 0);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            state.remainingProtectionTime = read.f();
            state.debt = read.d();
            state.restoreTimer = read.f();
            state.error = read.b() == 1;
        }

        // ===== 实例状态（存档 + 运行时）=====
        public final State state = new State();
    }
}