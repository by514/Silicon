package silicon.ui;

import arc.Core;
import arc.Events;
import arc.scene.ui.ImageButton;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.scene.ui.layout.Scl;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import silicon.audio.MusicPlayer;
import silicon.audio.MusicTrack;

/**
 * 常驻悬浮音乐控制条（屏幕右下角）。
 * - 收起态：单一音符图标按钮（Icon.music），点击展开为完整控制条。
 * - 展开态：播放/暂停、曲名、下一曲、收起按钮。
 * 由于触发顺序，scene 切换（进/出游戏、菜单）会重建 scene 并清空 root，
 * 因此每帧检查条是否仍在当前 scene，脱落后自动重建。全局仅一个实例。
 * 按钮均用 iconfont / bundle 文字，避免 unicode 符号缺字（♪/⏭ 等在默认字体中可能为豆腐块）。
 */
public class MusicBar {
    private static Table bar;
    private static boolean collapsed = true;

    private MusicBar() {}

    public static void init() {
        Events.run(EventType.Trigger.update, () -> {
            if (Core.scene == null || Core.scene.root == null) {
                bar = null;
                return;
            }
            if (bar != null && !bar.isDescendantOf(Core.scene.root)) {
                bar = null;
            }
            // 仅在游戏中且有玩家实体时显示（避免主菜单 player==null 时误播 NPE）
            if (!mindustry.Vars.state.isGame() || mindustry.Vars.player == null || !MusicPlayer.isEnabled()) {
                if (bar != null) {
                    bar.remove();
                    bar = null;
                }
                return;
            }
            if (bar == null) {
                build();
            }
        });
    }

    private static void build() {
        bar = new Table();

        if (collapsed) {
            ImageButton btn = new ImageButton(Icon.music, Styles.cleari);
            btn.clicked(() -> {
                collapsed = false;
                detach();
            });
            btn.resizeImage(Scl.scl(26f));
            bar.add(btn).size(Scl.scl(44f));
            bar.pack();
            bar.setPosition(Core.graphics.getWidth() - Scl.scl(54f), Scl.scl(22f));
        } else {
            TextButton play = new TextButton(playLabel(), Styles.flatBordert);
            play.clicked(() -> {
                if (MusicPlayer.isPlaying()) MusicPlayer.pause();
                else MusicPlayer.resume();
            });
            play.update(() -> play.setText(playLabel()));
            bar.add(play).growX().height(Scl.scl(40f)).pad(2f);

            TextButton track = new TextButton(trackLabel(), Styles.flatBordert);
            track.clicked(MusicPlayerDialog::open);
            track.update(() -> track.setText(trackLabel()));
            bar.add(track).height(Scl.scl(40f)).width(Scl.scl(210f)).pad(2f);

            ImageButton next = new ImageButton(Icon.rightOpen, Styles.cleari);
            next.clicked(MusicPlayer::next);
            bar.add(next).size(Scl.scl(40f)).pad(2f);

            TextButton close = new TextButton(Core.bundle.get("musicplayer.collapse"), Styles.flatBordert);
            close.clicked(() -> {
                collapsed = true;
                detach();
            });
            bar.add(close).height(Scl.scl(40f)).growX().pad(2f);
            // 展开态不能没有宽度锚点，给整排一个近似宽度
        }

        bar.pack();
        // 展开态整条宽度固定，保证「播放/暂停」「收起」等文字按钮不被裁切
        bar.setSize(Scl.scl(collapsed ? 44f : 530f), bar.getPrefHeight());
        float w = bar.getWidth();
        bar.setPosition(Core.graphics.getWidth() - w - Scl.scl(10f), Scl.scl(16f));
        Core.scene.root.addChild(bar);
    }

    private static void detach() {
        if (bar == null) return;
        bar.remove();
        bar = null;
        // 下次 update（依据 collapsed 决定形态）重建
    }

    private static String playLabel() {
        return MusicPlayer.isPlaying() ? Core.bundle.get("musicplayer.pause")
                                       : Core.bundle.get("musicplayer.play");
    }

    private static String trackLabel() {
        MusicTrack t = MusicPlayer.currentTrack();
        if (t == null) return Core.bundle.get("musicplayer.none");
        if (MusicPlayer.isPlaying()) return "[accent]> " + t.name;
        return t.name;
    }
}
