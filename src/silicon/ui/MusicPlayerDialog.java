package silicon.ui;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.CheckBox;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.Slider;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.scene.ui.layout.Scl;
import arc.struct.Seq;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import silicon.audio.MusicPlayer;
import silicon.audio.MusicTrack;

import static mindustry.Vars.ui;

/**
 * 音乐播放器弹窗：完整的播放控制 + 曲目库管理。
 * 入口：设置菜单「音乐播放器」按钮、快捷键、悬浮条展开。
 */
public class MusicPlayerDialog extends BaseDialog {
    private static MusicPlayerDialog instance;

    private Table trackTable;

    public static void open() {
        if (instance == null || !instance.isShown() || instance.getScene() != Core.scene) {
            instance = new MusicPlayerDialog();
        }
        instance.show();
        instance.rebuild();
    }

    private MusicPlayerDialog() {
        super(Core.bundle.get("musicplayer.title"));
        closeOnBack();
    }

    private void rebuild() {
        cont.clearChildren();
        cont.top();

        // —— 曲目列表 ——
        trackTable = new Table();
        trackTable.top();
        trackTable.defaults().fillX().padBottom(2f);
        rebuildRows();

        ScrollPane pane = new ScrollPane(trackTable, Styles.defaultPane);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        cont.add(pane).growX().height(Scl.scl(220f)).padTop(6f).row();

        // —— 控制排文字按钮（均分宽度，避免中文被固定宽裁切） ——
        Table ctrl = new Table();
        ctrl.button(Core.bundle.get("musicplayer.prev"), Styles.flatBordert, MusicPlayer::prev).growX().height(Scl.scl(44f)).pad(2f);
        ctrl.button(Core.bundle.get("musicplayer.playpause"), Styles.flatBordert, this::togglePlay).growX().height(Scl.scl(44f)).pad(2f);
        ctrl.button(Core.bundle.get("musicplayer.stop"), Styles.flatBordert, MusicPlayer::stop).growX().height(Scl.scl(44f)).pad(2f);
        ctrl.button(Core.bundle.get("musicplayer.next"), Styles.flatBordert, MusicPlayer::next).growX().height(Scl.scl(44f)).pad(2f);
        cont.add(ctrl).growX().padTop(10f).row();

        // —— 音量 / 音高 ——
        cont.table(sl -> {
            sl.add(Core.bundle.get("musicplayer.volume")).padRight(10f);
            Slider vol = new Slider(0f, 1f, 0.05f, false);
            vol.setValue(MusicPlayer.volume());
            vol.changed(() -> MusicPlayer.setVolume(vol.getValue()));
            sl.add(vol).width(Scl.scl(200f)).row();
        }).padTop(8f).row();

        cont.table(sl -> {
            sl.add(Core.bundle.get("musicplayer.pitch")).padRight(10f);
            Slider pit = new Slider(0.5f, 2f, 0.05f, false);
            pit.setValue(MusicPlayer.pitch());
            pit.changed(() -> MusicPlayer.setPitch(pit.getValue()));
            sl.add(pit).width(Scl.scl(200f));
        }).padTop(4f).row();

        // —— 循环模式（点按切换 3 态） ——
        TextButton loop = new TextButton(Core.bundle.get("musicplayer.loopmode." + MusicPlayer.loopMode()), Styles.flatBordert);
        loop.clicked(() -> {
            MusicPlayer.cycleLoopMode();
            loop.setText(Core.bundle.get("musicplayer.loopmode." + MusicPlayer.loopMode()));
        });
        cont.add(loop).width(Scl.scl(240f)).padTop(8f).row();

        // —— 添加曲目 ——
        cont.button(Core.bundle.get("musicplayer.addTrack"), Styles.flatBordert, this::showAddDialog).size(Scl.scl(240f), Scl.scl(44f)).padTop(10f).row();

        // —— 启用开关 ——
        CheckBox enable = new CheckBox(Core.bundle.get("musicplayer.enable"));
        enable.setChecked(MusicPlayer.isEnabled());
        enable.changed(() -> MusicPlayer.setEnabled(enable.isChecked()));
        cont.add(enable).padTop(8f);
    }

    private void togglePlay() {
        if (MusicPlayer.isPlaying()) {
            MusicPlayer.pause();
        } else {
            MusicPlayer.resume();
        }
    }

    private void rebuildRows() {
        if (trackTable == null) return;
        trackTable.clearChildren();
        Seq<MusicTrack> tracks = MusicPlayer.tracks();
        if (tracks.size == 0) {
            trackTable.add(Core.bundle.get("musicplayer.empty")).color(Color.lightGray).pad(10f);
            return;
        }
        int current = MusicPlayer.currentIndex();
        for (int i = 0; i < tracks.size; i++) {
            MusicTrack t = tracks.get(i);
            int idx = i;
            String prefix = (current == i) ? "[accent]> " : "";
            Table row = new Table();
            row.defaults().pad(2f);
            TextButton name = new TextButton(prefix + t.name + "  [gray](" + Core.bundle.get(t.typeKey) + ")", Styles.flatBordert);
            name.clicked(() -> { MusicPlayer.play(idx); rebuildRows(); });
            row.add(name).growX().height(Scl.scl(38f));
            row.button(Icon.trash, Styles.cleari, () -> removeTrack(idx)).size(Scl.scl(38f)).padLeft(4f);
            trackTable.add(row).growX().row();
        }
    }

    private void removeTrack(int idx) {
        MusicPlayer.removeTrack(idx);
        rebuildRows();
    }

    private void showAddDialog() {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.addTitle"));
        dlg.cont.table(t -> {
            t.button(Core.bundle.get("musicplayer.addInternal"), Styles.flatBordert, () -> {
                dlg.hide();
                showInternalPicker();
            }).width(Scl.scl(200f)).height(Scl.scl(44f)).row();
            t.button(Core.bundle.get("musicplayer.addUrl"), Styles.flatBordert, () -> {
                dlg.hide();
                showSourceInput(MusicTrack.URL);
            }).width(Scl.scl(200f)).height(Scl.scl(44f)).row();
            t.button(Core.bundle.get("musicplayer.addLocal"), Styles.flatBordert, () -> {
                dlg.hide();
                showSourceInput(MusicTrack.LOCAL);
            }).width(Scl.scl(200f)).height(Scl.scl(44f));
        }).pad(10f);
        dlg.closeOnBack();
        dlg.show();
    }

    private void showInternalPicker() {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.addInternal"));
        Table list = new Table();
        list.top();
        String[] keys = {"game1","game2","game3","game4","game5","game6","game7","game8","game9",
            "boss1","boss2","fine","editor","menu","land","launch"};
        for (String k : keys) {
            list.button(k, Styles.flatBordert, () -> {
                MusicTrack t = MusicPlayer.trackByHash("int-" + k);
                if (t != null) {
                    int idx = MusicPlayer.tracks().indexOf(t);
                    if (idx >= 0) MusicPlayer.play(idx);
                }
                dlg.hide();
                rebuildRows();
            }).growX().height(Scl.scl(38f)).pad(2f).row();
        }
        ScrollPane pane = new ScrollPane(list, Styles.defaultPane);
        dlg.cont.add(pane).grow().height(Scl.scl(260f));
        dlg.closeOnBack();
        dlg.show();
    }

    private void showSourceInput(int type) {
        BaseDialog dlg = new BaseDialog(type == MusicTrack.URL
                ? Core.bundle.get("musicplayer.addUrl") : Core.bundle.get("musicplayer.addLocal"));
        TextField field = new TextField();
        field.setMessageText(type == MusicTrack.URL
                ? Core.bundle.get("musicplayer.placeholderUrl") : Core.bundle.get("musicplayer.placeholderLocal"));
        dlg.cont.add(field).growX().pad(10f).row();
        dlg.cont.button(Core.bundle.get("musicplayer.confirm"), Styles.flatBordert, () -> {
            String src = field.getText().trim();
            if (src.isEmpty()) { dlg.hide(); return; }
            String name = null;
            int slash = Math.max(src.lastIndexOf('/'), src.lastIndexOf('\\'));
            if (slash >= 0 && slash < src.length() - 1) name = src.substring(slash + 1);
            MusicTrack t = MusicPlayer.addTrack(type, src, name);
            dlg.hide();
            rebuildRows();
        }).width(Scl.scl(120f)).height(Scl.scl(40f));
        dlg.closeOnBack();
        dlg.show();
    }
}
