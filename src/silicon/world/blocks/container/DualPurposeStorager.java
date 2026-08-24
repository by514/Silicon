package silicon.world.blocks.container;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.type.Liquid;
import mindustry.world.blocks.liquid.LiquidBlock;
import mindustry.world.blocks.storage.StorageBlock;
import silicon.util.SiliconLog;

/**
 * 两用存储方块：可存储物品与单种液体。
 * 继承 StorageBlock，放置于核心旁可与核心连接并为核心扩容（同原版仓库）。
 * 同时通过 hasLiquids + dumpLiquid 提供液体存储与导管抽取能力。
 * 支持 bottomRegion/liquidRegion/topRegion 三层贴图，呈现与原版流体储罐一致的流动+颜色特效。
 */
@SuppressWarnings("SpellCheckingInspection")
public class DualPurposeStorager extends StorageBlock {

    // ========== 配置参数 ==========
    /** 液体边缘内缩间距（绘制流动液面时用，与原版 LiquidRouter 一致为 0） */
    public float liquidPadding = 0f;
    /** 储存罐底座贴图 */
    public TextureRegion bottomRegion;
    /** 流动液面贴图 */
    public TextureRegion liquidRegion;
    /** 储存罐顶盖贴图（中心挖空，露出液体） */
    public TextureRegion topRegion;

    public DualPurposeStorager(String name) {
        super(name);
        this.coreMerge = true;
        this.itemCapacity = 500;
        this.liquidCapacity = 900f;
        this.hasLiquids = true;
        this.outputsLiquid = true;
        this.update = true;
        this.displayFlow = false;
    }

    @Override
    public void load() {
        super.load();
        // 按 mod 约定加载储罐三贴图；缺失时回退到主贴图并打印警告
        this.bottomRegion = Core.atlas.find(name + "-bottom");
        this.liquidRegion = Core.atlas.find(name + "-liquid");
        this.topRegion = Core.atlas.find(name + "-top");
        if (!bottomRegion.found()) {
            SiliconLog.warn("DualPurposeStorager '{}' missing -bottom texture, fallback to region", name);
            bottomRegion = region;
        }
        if (!liquidRegion.found()) {
            SiliconLog.warn("DualPurposeStorager '{}' missing -liquid texture, fallback to region", name);
            liquidRegion = region;
        }
        if (!topRegion.found()) {
            SiliconLog.warn("DualPurposeStorager '{}' missing -top texture, fallback to region", name);
            topRegion = region;
        }
    }

    // ============================================================
    // 自定义建筑类 - 继承 StorageBuild 以便被原版核心识别并扩容
    // ============================================================
    public class DualPurposeStoragerBuild extends StorageBuild {

        private static final float LIQUID_THRESHOLD = 0.001f;

        /**
         * 单液体类型接受规则（纯逻辑，可独立测试，不依赖 Mindustry 运行时）。
         *
         * @param currentAmount 当前存量
         * @param currentType   当前液体类型标识（null 表示无液体）
         * @param incomingType  待注入液体类型标识
         * @param threshold     空判定阈值
         * @return 是否接受该类型（类型层面）
         */
        static boolean canAcceptLiquidType(float currentAmount, Object currentType, Object incomingType, float threshold) {
            if (incomingType == null) return false;
            // 完全为空时接受任意液体
            if (currentAmount <= threshold) return true;
            // 有存量：必须类型一致
            return currentType == incomingType;
        }

        /**
         * 注入时防混液检查（纯逻辑）。有存量且类型不一致时拒绝。
         */
        static boolean shouldRejectLiquid(float currentAmount, Object currentType, Object incomingType, float threshold) {
            return currentAmount > threshold && currentType != incomingType;
        }

        // ================================================================
        // 绘制：复刻原版液体储罐的三层绘制，用 drawTiledFrames 生成流动条纹+颜色效果
        // ================================================================

        @Override
        public void draw() {
            // 1. 底座贴图
            Draw.rect(DualPurposeStorager.this.bottomRegion, x, y);
            // 2. 有液体时绘制流动液面（白色条纹滚动 + 液体颜色）
            //    drawTiledFrames 依赖 Vars.renderer.fluidFrames（仅客户端可用），null 时跳过
            if (liquids.currentAmount() > LIQUID_THRESHOLD && Vars.renderer != null) {
                Liquid liq = liquids.current();
                if (liq != null) {
                    LiquidBlock.drawTiledFrames(size, x, y, DualPurposeStorager.this.liquidPadding, liq,
                            liquids.currentAmount() / liquidCapacity);
                }
            }
            // 3. 顶盖贴图（中心挖空，液体透过中心显示）
            //    注意：drawTiledFrames 会把 Draw.color 设为液体色，这里必须重置，否则顶盖会被染色
            Draw.color();
            Draw.rect(DualPurposeStorager.this.topRegion, x, y);
        }

        // ========== 辅助方法（全部基于 liquids 模块，自动序列化/同步） ==========

        public boolean hasLiquid() {
            return liquids.current() != null && liquids.currentAmount() > LIQUID_THRESHOLD;
        }

        // ================================================================
        // 液体输入（只接受一种液体，由 liquids 模块管理）
        // ================================================================

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            if (source == this || liquid == null) return false;
            // 严格单液体约束（类型层面，复用纯逻辑规则）+ 容量检查
            if (!canAcceptLiquidType(liquids.currentAmount(), liquids.current(), liquid, LIQUID_THRESHOLD)) return false;
            return liquids.get(liquid) < liquidCapacity - LIQUID_THRESHOLD;
        }

        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount) {
            if (liquid == null || amount <= 0) return;
            // 防混液：有存量且类型不一致则拒绝
            if (shouldRejectLiquid(liquids.currentAmount(), liquids.current(), liquid, LIQUID_THRESHOLD)) return;

            float remaining = liquidCapacity - liquids.get(liquid);
            float actualAmount = Math.min(amount, remaining);
            if (actualAmount > 0) {
                super.handleLiquid(source, liquid, actualAmount);
            }
        }

        // ================================================================
        // 核心：主动输出液体到相邻导管
        // ================================================================

        @Override
        public void updateTile() {
            super.updateTile();

            // 标准抽取接口：dumpLiquid 内部用 proximity 遍历真实相邻建筑，任意尺寸均可输出到导管
            if (hasLiquid()) {
                dumpLiquid(liquids.current());
            }
        }

        
    }
}
