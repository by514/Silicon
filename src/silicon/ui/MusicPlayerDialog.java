package silicon.ui;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.CheckBox;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.Slider;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.scene.ui.layout.Scl;
import arc.struct.Seq;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
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
    /** 当前专辑筛选（null = 全部曲目） */
    private String filterAlbum = null;

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

        // —— 顶部「现在播放」面板（实时刷新：状态/曲名随播放变化自动更新） ——
        cont.table(now -> {
            now.background(Styles.grayPanel);
            now.margin(12f);
            now.defaults().top();

            // 左侧：状态图标圆盘
            final arc.scene.ui.Image[] disc = new arc.scene.ui.Image[1];
            now.table(d -> {
                d.background(Styles.grayPanelDark);
                d.margin(9f);
                disc[0] = new arc.scene.ui.Image(MusicPlayer.isPlaying() ? Icon.pause : Icon.play);
                disc[0].setSize(Scl.scl(24f));
                disc[0].setColor(MusicPlayer.isPlaying() ? Pal.accent : Color.lightGray);
                d.add(disc[0]).size(Scl.scl(24f)).color(MusicPlayer.isPlaying() ? Pal.accent : Color.lightGray);
            }).padRight(14f);

            // 右侧：状态标题 + 曲名
            final arc.scene.ui.Label[] stateLbl = new arc.scene.ui.Label[1];
            final arc.scene.ui.Label[] nameLbl = new arc.scene.ui.Label[1];
            now.table(info -> {
                info.defaults().left();
                stateLbl[0] = new arc.scene.ui.Label(MusicPlayer.isPlaying()
                                ? Core.bundle.get("musicplayer.playing")
                                : Core.bundle.get("musicplayer.play"),
                        Styles.outlineLabel);
                stateLbl[0].setColor(MusicPlayer.isPlaying() ? Pal.accent : Color.lightGray);
                info.add(stateLbl[0]);
                info.row();
                nameLbl[0] = new arc.scene.ui.Label(nowPlayingLabel(), Styles.outlineLabel);
                nameLbl[0].setColor(MusicPlayer.isPlaying() ? Color.white : Color.lightGray);
                nameLbl[0].setEllipsis(true);
                info.add(nameLbl[0]).growX().width(Scl.scl(280f));
            }).growX();
            // 每帧刷新状态与曲名（悬浮条/自动推进切换曲目时这里也跟着变）
            now.update(() -> {
                boolean playing = MusicPlayer.isPlaying();
                disc[0].setDrawable(playing ? Icon.pause : Icon.play);
                disc[0].setColor(playing ? Pal.accent : Color.lightGray);
                stateLbl[0].setText(playing ? Core.bundle.get("musicplayer.playing") : Core.bundle.get("musicplayer.play"));
                stateLbl[0].setColor(playing ? Pal.accent : Color.lightGray);
                nameLbl[0].setText(nowPlayingLabel());
                nameLbl[0].setColor(playing ? Color.white : Color.lightGray);
            });
        }).growX().padBottom(8f).row();

        // —— 播放进度条（可拖动选进度） ——
        cont.table(seek -> {
            seek.background(Styles.grayPanelDark);
            seek.margin(4f, 8f, 4f, 8f);
            seek.defaults().left();
            final arc.scene.ui.Label time = new arc.scene.ui.Label("0:00 / 0:00", Styles.outlineLabel);
            time.setColor(Color.white);
            seek.add(time).growX();
            Slider seekBar = new Slider(0f, 1f, 0.001f, false);
            seekBar.setDisabled(true);
            final boolean[] userSeek = {false};
            seekBar.update(() -> {
                float len = MusicPlayer.trackLength();
                boolean hasLen = len > 0f;
                // 已知时长即可拖动（播放/暂停皆可）；拖动中不刷新值避免回跳摇动
                seekBar.setDisabled(!hasLen);
                if (hasLen) {
                    float cur = userSeek[0] ? seekBar.getValue() * len : MusicPlayer.currentTime();
                    time.setText(formatTime(cur, len));
                    if (!userSeek[0] && !seekBar.isDragging()) {
                        userSeek[0] = true;
                        seekBar.setValue(cur / len);
                        userSeek[0] = false;
                    }
                } else {
                    time.setText("0:00 / 0:00");
                }
            });
            seekBar.changed(() -> {
                float len = MusicPlayer.trackLength();
                if (!userSeek[0] && len > 0f) {
                    userSeek[0] = true;
                    MusicPlayer.seek(seekBar.getValue() * len);
                    userSeek[0] = false;
                }
            });
            seek.add(seekBar).growX().padLeft(10f);
        }).growX().padBottom(4f).row();

        // —— 主控制条：上一首 / 播放暂停 / 下一首（图标按钮） ——
        cont.table(ctrl -> {
            ctrl.button(Icon.leftOpen, Styles.flati, MusicPlayer::prev)
                    .growX().height(Scl.scl(46f)).padRight(4f);
            ImageButton pp = new ImageButton(MusicPlayer.isPlaying() ? Icon.pause : Icon.play, Styles.flati);
            pp.resizeImage(Scl.scl(24f));
            pp.getImage().setColor(MusicPlayer.isPlaying() ? Pal.accent : Color.white);
            pp.clicked(this::togglePlay);
            ctrl.add(pp).growX().height(Scl.scl(52f)).pad(4f);
            ctrl.button(Icon.rightOpen, Styles.flati, MusicPlayer::next)
                    .growX().height(Scl.scl(46f)).padLeft(4f);
        }).growX().padTop(2f).row();

        // —— 专辑筛选栏 ——
        cont.table(albumsFilter -> {
            albumsFilter.background(Styles.grayPanel);
            albumsFilter.margin(3f, 6f, 3f, 6f);
            albumsFilter.add(Core.bundle.get("musicplayer.album") + ":").left().padRight(4f);
            // 「全部曲目」按钮
            TextButton all = new TextButton(Core.bundle.get("musicplayer.allAlbums"), Styles.flatBordert);
            all.getLabel().setWrap(false);
            all.update(() -> all.setColor(filterAlbum == null ? Pal.accent : Color.lightGray));
            all.clicked(() -> { filterAlbum = null; rebuildRows(); });
            albumsFilter.add(all).width(Scl.scl(100f)).height(Scl.scl(30f)).pad(1f);
            Seq<MusicPlayer.Album> albums = MusicPlayer.albums();
            for (int i = 0; i < albums.size; i++) {
                MusicPlayer.Album a = albums.get(i);
                final String name = a.name;
                TextButton b = new TextButton(a.name, Styles.flatBordert);
                b.getLabel().setWrap(false);
                b.getLabel().setEllipsis(true);
                b.update(() -> b.setColor(name.equals(filterAlbum) ? Pal.accent : Color.white));
                b.clicked(() -> { filterAlbum = name; rebuildRows(); });
                albumsFilter.add(b).width(Scl.scl(96f)).height(Scl.scl(30f)).pad(1f);
            }
            // 新专辑按钮
            albumsFilter.add().growX();
            albumsFilter.button(Icon.add, Styles.cleari, this::newAlbumDialog).size(Scl.scl(30f)).padLeft(4f);
            // 删除当前筛选专辑按钮（仅 filterAlbum 非空时有效）
            ImageButton delAlbum = new ImageButton(Icon.trash, Styles.cleari);
            delAlbum.resizeImage(Scl.scl(15f));
            delAlbum.clicked(() -> deleteCurrentAlbum());
            albumsFilter.add(delAlbum).size(Scl.scl(30f)).padLeft(2f);
        }).growX().padTop(6f).row();

        // —— 音量 / 音高 / 倍速（三个并排面板合为一行） ——
        cont.table(analog -> {
            analog.defaults().pad(2f);
            // 音量面板
            analog.table(vp -> {
                vp.background(Styles.grayPanel);
                vp.margin(6f);
                vp.defaults().pad(2f);
                vp.add(Core.bundle.get("musicplayer.volume")).left().width(Scl.scl(42f));
                Slider vol = new Slider(0f, 1f, 0.05f, false);
                vol.setValue(MusicPlayer.volume());
                final arc.scene.ui.Label volVal = new arc.scene.ui.Label(Math.round(MusicPlayer.volume() * 100) + "%", Styles.outlineLabel);
                volVal.setColor(Color.white);
                vol.update(() -> {
                    if (!vol.isDragging() && Math.abs(vol.getValue() - MusicPlayer.volume()) > 0.01f) vol.setValue(MusicPlayer.volume());
                    volVal.setText(Math.round(vol.getValue() * 100) + "%");
                });
                vol.changed(() -> MusicPlayer.setVolume(vol.getValue()));
                vp.add(vol).growX().width(Scl.scl(120f));
                vp.add(volVal).width(Scl.scl(36f)).right().padLeft(4f);
            }).growX();
            // 音高面板
            analog.table(pp -> {
                pp.background(Styles.grayPanel);
                pp.margin(6f);
                pp.defaults().pad(2f);
                pp.add(Core.bundle.get("musicplayer.pitch")).left().width(Scl.scl(42f));
                Slider pit = new Slider(0.5f, 2f, 0.05f, false);
                pit.setValue(MusicPlayer.pitch());
                final arc.scene.ui.Label pitVal = new arc.scene.ui.Label(String.format("%.2fx", MusicPlayer.pitch()), Styles.outlineLabel);
                pitVal.setColor(Color.white);
                pit.update(() -> {
                    if (!pit.isDragging() && Math.abs(pit.getValue() - MusicPlayer.pitch()) > 0.01f) pit.setValue(MusicPlayer.pitch());
                    pitVal.setText(String.format("%.2fx", pit.getValue()));
                });
                pit.changed(() -> MusicPlayer.setPitch(pit.getValue()));
                pp.add(pit).growX().width(Scl.scl(120f));
                pp.add(pitVal).width(Scl.scl(42f)).right().padLeft(4f);
            }).growX();
            // 倍速面板
            analog.table(sp -> {
                sp.background(Styles.grayPanel);
                sp.margin(6f);
                sp.defaults().pad(2f);
                sp.add(Core.bundle.get("musicplayer.speed")).left().width(Scl.scl(42f));
                Slider spd = new Slider(0f, 1f, 0.001f, false);
                spd.setValue(speedToCursor(MusicPlayer.speed()));
                final arc.scene.ui.Label spdVal = new arc.scene.ui.Label(formatSpeed(MusicPlayer.speed()), Styles.outlineLabel);
                spdVal.setColor(Color.white);
                spd.update(() -> {
                    if (!spd.isDragging() && Math.abs(cursorToSpeed(spd.getValue()) - MusicPlayer.speed()) > 0.001f) {
                        spd.setValue(speedToCursor(MusicPlayer.speed()));
                    }
                    spdVal.setText(formatSpeed(cursorToSpeed(spd.getValue())));
                });
                spd.changed(() -> MusicPlayer.setSpeed(cursorToSpeed(spd.getValue())));
                sp.add(spd).growX().width(Scl.scl(120f));
                sp.add(spdVal).width(Scl.scl(42f)).right().padLeft(4f);
            }).growX();
        }).growX().padTop(2f).row();

        // —— A-B 区间（区间重复）：设 A / 设 B / 清除 + 区间状态与范围 ——
        cont.table(abRow -> {
            abRow.background(Styles.grayPanel);
            abRow.margin(5f, 8f, 5f, 8f);
            abRow.defaults().pad(2f);
            abRow.add(Core.bundle.get("musicplayer.ab")).left().width(Scl.scl(80f));
            final arc.scene.ui.Label abStatus = new arc.scene.ui.Label(abStatusText(), Styles.outlineLabel);
            abStatus.setColor(MusicPlayer.hasAb() ? Pal.accent : Color.lightGray);
            abStatus.update(() -> {
                abStatus.setText(abStatusText());
                abStatus.setColor(MusicPlayer.hasAb() ? Pal.accent : Color.lightGray);
            });
            abRow.add(abStatus).growX().left().padLeft(2f);
            abRow.button(Core.bundle.get("musicplayer.abSetA"), Styles.flatBordert, () -> {
                MusicPlayer.setAbA(MusicPlayer.currentTime());
                rebuild();
            }).height(Scl.scl(32f)).width(Scl.scl(86f));
            abRow.button(Core.bundle.get("musicplayer.abSetB"), Styles.flatBordert, () -> {
                MusicPlayer.setAbB(MusicPlayer.currentTime());
                rebuild();
            }).height(Scl.scl(32f)).width(Scl.scl(86f));
            abRow.button(Core.bundle.get("musicplayer.abClear"), Styles.flatBordert, () -> {
                MusicPlayer.clearAb();
                rebuild();
            }).height(Scl.scl(32f)).width(Scl.scl(86f));
        }).growX().padTop(2f).row();

        // —— 底部：循环模式 / 停止 / 添加曲目（uniform 等宽防文字变化抖动）——
        cont.table(bottom -> {
            bottom.defaults().growX().height(Scl.scl(38f)).pad(2f).uniform();
            TextButton loop = new TextButton(Core.bundle.get("musicplayer.loopmode." + MusicPlayer.loopMode()), Styles.flatBordert);
            loop.clicked(() -> {
                MusicPlayer.cycleLoopMode();
                loop.setText(Core.bundle.get("musicplayer.loopmode." + MusicPlayer.loopMode()));
            });
            bottom.add(loop);

            bottom.button(Core.bundle.get("musicplayer.stop"), Styles.flatBordert, MusicPlayer::stop);

            bottom.button(Core.bundle.get("musicplayer.addTrack"), Styles.flatBordert, this::showAddDialog);
        }).growX().padTop(2f).row();

        // —— 更多设置：启用开关 + 悬浮条复位（紧凑面板，尽量缩小留白） ——
        cont.table(more -> {
            more.background(Styles.grayPanel);
            more.margin(4f, 8f, 4f, 8f);
            more.defaults().pad(2f);
            CheckBox enable = new CheckBox(Core.bundle.get("musicplayer.enable"));
            enable.setChecked(MusicPlayer.isEnabled());
            enable.changed(() -> MusicPlayer.setEnabled(enable.isChecked()));
            more.add(enable).left().growX();
            more.button(Core.bundle.get("musicplayer.resetPos"), Styles.flatBordert, MusicBar::resetPosition)
                    .height(Scl.scl(34f)).width(Scl.scl(120f)).right();
        }).growX().padTop(2f).row();

        // —— 曲目列表（置于底部并 growY 填满剩余高度，消除设置界面下方空白） ——
        trackTable = new Table();
        trackTable.top();
        trackTable.defaults().fillX().padBottom(2f);
        rebuildRows();

        ScrollPane pane = new ScrollPane(trackTable, Styles.defaultPane);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        cont.add(pane).growX().growY().minHeight(Scl.scl(140f)).padTop(6f).row();
    }

    private String nowPlayingLabel() {
        MusicTrack t = MusicPlayer.currentTrack();
        if (t == null) return Core.bundle.get("musicplayer.none");
        return t.name;
    }

    /** 秒 → m:ss 格式 */
    private static String formatTime(float sec, float len) {
        return fmt(sec) + " / " + (len > 0f ? fmt(len) : "--:--");
    }

    private static String fmt(float sec) {
        if (sec < 0f) sec = 0f;
        int total = (int) sec;
        return (total / 60) + ":" + (total % 60 < 10 ? "0" : "") + (total % 60);
    }

    /** A-B 区间状态文字：未设置显示「未设置」；已设置显示 lo–hi 区间与开关态 */
    private static String abStatusText() {
        if (!MusicPlayer.hasAb()) return Core.bundle.get("musicplayer.abUnset");
        float lo = Math.min(MusicPlayer.abA(), MusicPlayer.abB());
        float hi = Math.max(MusicPlayer.abA(), MusicPlayer.abB());
        return Core.bundle.get("musicplayer.abOn") + "  " + fmt(lo) + " - " + fmt(hi);
    }

    // 倍速对数映射（0.1–16x）：speed = 0.1 * 160^cursor，160 = 16/0.1
    private static final float LOG_MIN = 0.1f;
    private static final float LOG_RATIO = 160f;

    private static float speedToCursor(float speed) {
        speed = Math.max(LOG_MIN, Math.min(16f, speed));
        return (float) (Math.log(speed / LOG_MIN) / Math.log(LOG_RATIO));
    }

    private static float cursorToSpeed(float cursor) {
        cursor = Math.max(0f, Math.min(1f, cursor));
        return (float) (LOG_MIN * Math.pow(LOG_RATIO, cursor));
    }

    private static String formatSpeed(float s) {
        if (Math.abs(s - 1f) < 0.001f) return "1x";
        if (Math.abs(s - Math.round(s)) < 0.001f) return Math.round(s) + "x";
        return String.format("%.2fx", s);
    }

    private void togglePlay() {
        if (MusicPlayer.isPlaying()) {
            MusicPlayer.pause();
        } else {
            MusicPlayer.resume();
        }
        rebuild();
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
        // 过滤：仅显示当前激活专辑内的曲目（filterAlbum != null 时）
        int count = 0;
        for (int i = 0; i < tracks.size; i++) {
            MusicTrack t = tracks.get(i);
            if (filterAlbum != null && !isInAlbum(filterAlbum, t.cacheHash)) continue;
            count++;
            int idx = i;
            boolean isCurrent = current == i;
            String[] type = {"  [gray](" + Core.bundle.get(t.typeKey) + ")"};
            Table row = new Table();
            if (isCurrent) row.background(Styles.grayPanel);
            row.defaults().pad(2f);
            // 曲名 + 类型；当前曲高亮
            TextButton name = new TextButton((isCurrent ? "[accent]> " : "") + t.name + type[0], Styles.flatBordert);
            name.getLabel().setWrap(false);
            name.getLabel().setEllipsis(true);
            name.clicked(() -> { MusicPlayer.play(idx); rebuild(); });
            row.add(name).growX().height(Scl.scl(38f));
            // 音频信息：时长 + 文件大小（右对齐固定宽列，保持各行对齐美观）
            row.add(trackInfoLabel(t)).width(Scl.scl(100f)).right().padLeft(8f).padRight(4f).color(Color.gray);
            // 专辑归属按钮：点击弹出「加入/移出专辑」菜单
            ImageButton albumBtn = new ImageButton(Icon.folder, Styles.cleari);
            albumBtn.resizeImage(Scl.scl(16f));
            albumBtn.clicked(() -> albumAssignDialog(idx));
            row.add(albumBtn).size(Scl.scl(32f)).padLeft(4f);
            ImageButton del = new ImageButton(Icon.trash, Styles.cleari);
            del.resizeImage(Scl.scl(18f));
            del.clicked(() -> removeTrack(idx));
            row.add(del).size(Scl.scl(36f)).padLeft(4f);
            trackTable.add(row).growX().row();
        }
        if (count == 0) {
            trackTable.add(Core.bundle.get("musicplayer.albumEmpty")).color(Color.lightGray).pad(10f);
        }
    }

    /** 判断某曲目 hash 是否属于指定专辑 */
    private static boolean isInAlbum(String albumName, String hash) {
        for (MusicPlayer.Album a : MusicPlayer.albums()) {
            if (albumName.equals(a.name) && a.hashes.contains(hash)) return true;
        }
        return false;
    }

    /** 专辑归属菜单：把当前曲目加入/移出某个专辑 */
    private void albumAssignDialog(int trackIndex) {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.addToAlbum"));
        Table list = new Table();
        list.top();
        MusicTrack t = MusicPlayer.trackAt(trackIndex);
        if (t == null) return;
        list.add(Core.bundle.get("musicplayer.playing") + ": [accent]" + t.name + "[]").left().pad(4f).row();
        Seq<MusicPlayer.Album> albums = MusicPlayer.albums();
        if (albums.size == 0) {
            list.add(Core.bundle.get("musicplayer.noAlbum")).color(Color.lightGray).pad(6f).row();
        }
        for (int i = 0; i < albums.size; i++) {
            MusicPlayer.Album a = albums.get(i);
            final int ai = i;
            boolean inAlbum = a.hashes.contains(t.cacheHash);
            String label = (inAlbum ? "[accent]✓ [/]" : "  ") + a.name;
            TextButton b = new TextButton(label, Styles.flatBordert);
            b.clicked(() -> {
                if (a.hashes.contains(t.cacheHash)) MusicPlayer.removeFromAlbum(ai, trackIndex);
                else MusicPlayer.addToAlbum(ai, trackIndex);
                dlg.hide();
                rebuildRows();
            });
            list.add(b).growX().height(Scl.scl(34f)).pad(2f).row();
        }
        ScrollPane pane = new ScrollPane(list, Styles.defaultPane);
        dlg.cont.add(pane).grow().height(Scl.scl(220f));
        dlg.buttons.button(Core.bundle.get("musicplayer.confirm"), Styles.flatBordert, dlg::hide).width(Scl.scl(120f)).height(Scl.scl(40f));
        dlg.closeOnBack();
        dlg.show();
    }

    /** 删除当前正在筛选的专辑（仅 filterAlbum 非空时可删） */
    private void deleteCurrentAlbum() {
        if (filterAlbum == null) return;
        Seq<MusicPlayer.Album> albums = MusicPlayer.albums();
        for (int i = 0; i < albums.size; i++) {
            if (filterAlbum.equals(albums.get(i).name)) {
                MusicPlayer.removeAlbum(i);
                break;
            }
        }
        // 若正在播放的曲目原来在删除专辑内且当前专辑作用域是它，会由 removeAlbum 复位 activeAlbum；
        // 这里把筛选也复位到全部
        filterAlbum = null;
        rebuild();
    }

    /** 新建专辑弹窗：输入名称创建 */
    private void newAlbumDialog() {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.newAlbum"));
        TextField field = new TextField();
        field.setMessageText(Core.bundle.get("musicplayer.albumName"));
        dlg.cont.add(field).growX().pad(10f).row();
        dlg.cont.button(Core.bundle.get("musicplayer.confirm"), Styles.flatBordert, () -> {
            String name = field.getText().trim();
            if (!name.isEmpty()) {
                MusicPlayer.addAlbum(name);
                filterAlbum = name;
                rebuild();
            }
            dlg.hide();
        }).width(Scl.scl(120f)).height(Scl.scl(40f));
        dlg.closeOnBack();
        dlg.show();
    }

    private void removeTrack(int idx) {
        MusicPlayer.removeTrack(idx);
        rebuildRows();
    }

    /** 批量导入完成后的信息确认弹窗：逐条列出文件名/大小/时长；
     *  @param afterClose 关闭确认弹窗后执行（如本地导入重新弹出导入界面继续追加） */
    private void showImportResult(Seq<MusicTrack> added, Runnable afterClose) {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.importResult"));
        dlg.cont.table(list -> {
            list.top();
            list.defaults().pad(1f);
            for (MusicTrack t : added) {
                String info = trackInfoLabel(t);
                String line = "[accent]>[/] " + t.name + (info.isEmpty() ? "" : "  [gray]" + info + "[]");
                list.add(new arc.scene.ui.Label(line, Styles.defaultLabel)).growX().left().row();
            }
        }).grow().pad(10f);
        dlg.buttons.button(Core.bundle.get("musicplayer.confirm"), Styles.flatBordert, () -> {
            dlg.hide();
            if (afterClose != null) afterClose.run();
        }).width(Scl.scl(120f)).height(Scl.scl(40f));
        dlg.closeOnBack();
        dlg.show();
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
        String[] keys = MusicPlayer.internalKeys();
        for (String k : keys) {
            list.button(k, Styles.flatBordert, () -> {
                MusicTrack t = MusicPlayer.trackByHash("int-" + k);
                if (t != null) {
                    int idx = MusicPlayer.tracks().indexOf(t);
                    if (idx >= 0) MusicPlayer.play(idx);
                }
                dlg.hide();
                rebuild();
            }).growX().height(Scl.scl(38f)).pad(2f).row();
        }
        ScrollPane pane = new ScrollPane(list, Styles.defaultPane);
        dlg.cont.add(pane).grow().height(Scl.scl(260f));
        dlg.closeOnBack();
        dlg.show();
    }

    private void showSourceInput(int type) {
        if (type == MusicTrack.LOCAL) {
            // 本地文件：唤起系统文件选择框（支持多选；导入完成后弹出信息确认）
            mindustry.ui.FileChooser.FileChooserParams params = new mindustry.ui.FileChooser.FileChooserParams();
            params.open = true;
            params.extensions = new String[]{"ogg", "mp3", "wav", "flac", "m4a", "wma", "aac", "opus"};
            params.title = Core.bundle.get("musicplayer.addLocal");
            params.submitMulti(files -> {
                // 桌面 Platform 用后台 daemon 线程回调，UI 更新与 tracks 改动必须切回主线程
                final arc.struct.Seq<arc.files.Fi> picked = new arc.struct.Seq<>(files);
                Core.app.post(() -> {
                    Seq<MusicTrack> added = new Seq<>();
                    for (arc.files.Fi f : picked) {
                        String src = f.absolutePath();
                        if (src == null || src.isEmpty()) continue;
                        MusicTrack t = MusicPlayer.addTrack(MusicTrack.LOCAL, src, f.name());
                        if (t != null) added.add(t);
                    }
                    if (added.size > 0) {
                        this.rebuild();
                        // 本地导入不退出导入界面：确认结果后重新弹出导入界面，便于继续追加
                        showImportResult(added, this::showAddDialog);
                    } else {
                        showAddDialog();
                    }
                });
            });
            return;
        }
        // URL：文本输入框
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.addUrl"));
        TextField field = new TextField();
        field.setMessageText(Core.bundle.get("musicplayer.placeholderUrl"));
        dlg.cont.add(field).growX().pad(10f).row();
        // 无效输入提示（初始隐藏，输入不合法时显示）
        final arc.scene.ui.Label err = new arc.scene.ui.Label(Core.bundle.get("musicplayer.invalid"), Styles.defaultLabel);
        err.setColor(Color.scarlet);
        err.visible = false;
        dlg.cont.add(err).growX().padTop(2f).row();
        dlg.cont.button(Core.bundle.get("musicplayer.confirm"), Styles.flatBordert, () -> {
            String src = field.getText().trim();
            if (src.isEmpty()) { dlg.hide(); return; }
            String name = null;
            int slash = Math.max(src.lastIndexOf('/'), src.lastIndexOf('\\'));
            if (slash >= 0 && slash < src.length() - 1) name = src.substring(slash + 1);
            MusicTrack t = MusicPlayer.addTrack(MusicTrack.URL, src, name);
            if (t == null) {
                // 非法来源（无匹配扩展名等）：提示并保持弹窗，不静默关闭
                err.visible = true;
                return;
            }
            dlg.hide();
            rebuild();
        }).width(Scl.scl(120f)).height(Scl.scl(40f));
        dlg.closeOnBack();
        dlg.show();
    }

    /** 曲目信息标签：时长 + 文件大小（右对齐固定列宽保持对齐；未知显示占位符避免各行宽闪跳） */
    private static String trackInfoLabel(MusicTrack t) {
        StringBuilder sb = new StringBuilder();
        float len = MusicPlayer.trackLengthOf(t);
        if (len > 0f) {
            int total = (int) len;
            sb.append((total / 60) + ":" + (total % 60 < 10 ? "0" : "") + (total % 60));
        } else {
            sb.append("--:--");
        }
        long size = MusicPlayer.trackSizeOf(t);
        if (size > 0f) {
            sb.append("  ");
            if (size > 1048576) sb.append(String.format("%.1fM", size / 1048576.0));
            else if (size > 1024) sb.append(String.format("%.0fK", size / 1024.0));
            else sb.append(size).append("B");
        }
        return sb.toString();
    }
}
