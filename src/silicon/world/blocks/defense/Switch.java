package silicon.world.blocks.defense;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.scene.ui.layout.Table;
import arc.util.Eachable;
import arc.util.Nullable;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.gen.Sounds;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.meta.BlockGroup;
import silicon.util.SiliconTmp;

import static mindustry.Vars.player;

public class Switch extends Block {
    TextureRegion state[];

    public Switch(String name) {
        super(name);
        update = true;
        solid = true;
        sync = true; // 操纵另一端 enabled 的控制块：写读档后 fE 同步（同原版 SwitchBlock）
        // configurable 故意保持 false：InputHandler 在 configurable=true 时会把该点击标记为已消费（consumed=true），
        // 使 tapped() 不再被调用（其 !consumed 守卫）。本方块靠 tapped()+configure() 切换，故不能开启 configurable。
        rotate = true;
        group = BlockGroup.logic;
        config(Boolean.class, (building, enabled) -> {
            Building front = building.front();
            // 服务器端执行：目标固定为「本开关正前方」的服务端建筑，客户端无法指定任意目标；
            // 仅允许控制同队、且非开关的建筑（#28）。
            if (front == null || front.team != building.team || front instanceof SwitchBuild) return;
            // #43 单次状态更新（不持续覆盖），并按该次设置刷新 switch 记忆状态
            front.enabled = enabled;
            ((SwitchBuild) building).fE = enabled; // 回调目标恒为 SwitchBuild 本身
        });
        state = new TextureRegion[2];
    }

    @Override
    public void load() {
        super.load();
        state[0] = Core.atlas.find(name + "-off");
        state[1] = Core.atlas.find(name + "-on");
//        state[0].flip(true,true);
//        state[1].flip(true,true);
        region = state[0];
    }

    @Override
    public void drawDefaultPlanRegion(BuildPlan plan, Eachable<BuildPlan> list){
        int trns = size / 2 + 1;
        Building front = Vars.world.build(plan.tile().x + Geometry.d4(plan.rotation).x * trns, plan.tile().y + Geometry.d4(plan.rotation).y * trns);
        float a = Draw.getColorAlpha();
        Draw.rect(front != null && front.enabled ? state[1] : state[0], plan.drawx(), plan.drawy(), !rotate || !rotateDraw ? 0 : plan.rotation * 90 + 90);
        if(plan.worldContext && player != null && teamRegion != null && teamRegion.found()){
            if(teamRegions[player.team().id] == teamRegion) Draw.color(player.team().color, a);
            Draw.rect(teamRegions[player.team().id], plan.drawx(), plan.drawy());
            Draw.color(1f, 1f, 1f, a);
        }

        drawPlanConfig(plan, list);
    }

    @Override
    public void placeEnded(Tile tile, @Nullable Unit builder, int rotation, @Nullable Object config) {
        if (tile.build instanceof SwitchBuild build && build.front() != null
            && build.front().team == build.team) { // #28 同队校验
            build.fE = build.front().enabled;
        }
    }

    public class SwitchBuild extends Building {
        /** 状态镜像：与前方建筑 enabled 保持一致（configure 回调与 updateTile 都只写 front，再同步到此）。 */
        boolean fE;
        @Override
        public void drawSelect() {
            super.drawSelect();
            if (front() == null || (front() instanceof SwitchBuild)) return;
            Drawf.selected(front(), SiliconTmp.c1.set(front().enabled ? Color.green : Color.red).a(Mathf.absin(4f, 1f)));

        }

        @Override
        public void draw() {
            super.draw();
            Draw.rect(fE ? state[1] : state[0], x, y, this.drawrot() + 90);
        }



        @Override
        public void updateTile() {
            super.updateTile();
            // #43 同步方向：本块为「状态反映器」——fE 跟随前方建筑的实际 enabled（含外部逻辑处理器改动），
            // 自身 configure 也写 front 再置 fE；两处都写 front，故 fE 恒等于 front.enabled，无双向竞争。
            Building f = front();
            if (f != null && f.team == team) {
                if (f.enabled != fE) fE = f.enabled;
            }
        }

        @Override
        public void tapped() {
            // #28 同队校验：仅发起配置请求，fE 由服务器端 config 回调统一回写（不做客户端乐观改值）
            if (front() != null && front().team == team && !(front() instanceof SwitchBuild)) {
                Sounds.click.at(this);
                configure(!fE);
            }
        }

        /**
         * Writes building data to save a file
         *
         * @param write The writer object
         */
        @Override
        public void write(Writes write) {
            super.write(write);
            write.bool(fE);
        }

        /**
         * Reads building data from a save file
         *
         * @param read     The reader object
         * @param revision The save revision
         */
        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            fE = read.bool();
        }

        @Override
        public Boolean config() {
            return fE;
        }
    }
}
