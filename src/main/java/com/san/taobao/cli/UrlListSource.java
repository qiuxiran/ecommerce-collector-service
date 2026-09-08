package com.san.taobao.cli;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 从文件拿待抓链接：识别列表文件、读行、弹文件选择框。 */
public final class UrlListSource {

    private static final List<String> LIST_EXTENSIONS = List.of(".txt", ".csv", ".list");

    private UrlListSource() {
    }

    /** 输入看起来像列表文件名（不管存不存在）。用来把「文件路径打错」和「单条链接」区分开。 */
    public static boolean looksLikeListFilename(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return LIST_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /** @return 存在且是列表文件时返回绝对路径，否则 null */
    public static Path asExistingListFile(String input) {
        try {
            Path p = Path.of(input);
            if (Files.isRegularFile(p) && looksLikeListFilename(p.getFileName().toString())) {
                return p.toAbsolutePath().normalize();
            }
        } catch (RuntimeException ignored) {
            // 不是合法路径，按单条链接处理
        }
        return null;
    }

    /** 读链接列表。空行和 {@code #} 注释忽略，首行 BOM 剥掉。 */
    public static List<String> readLines(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        List<String> raw = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < raw.size(); i++) {
            String line = raw.get(i);
            if (i == 0 && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
                line = line.substring(1);
            }
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                out.add(line);
            }
        }
        return out;
    }

    /** @return 用户选中的文件，取消则 null */
    public static Path pickFile() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 系统皮肤失败就用默认
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择商品链接列表（.txt，一行一条）");
        chooser.setFileFilter(new FileNameExtensionFilter("文本文件 (*.txt)", "txt"));
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setCurrentDirectory(Path.of(".").toAbsolutePath().normalize().toFile());

        // 选择框默认可能藏在 Chrome 窗口后面，挂一个置顶的空 frame 当父窗口
        JFrame parent = new JFrame();
        parent.setAlwaysOnTop(true);
        parent.setUndecorated(true);
        parent.setLocationRelativeTo(null);
        parent.setVisible(true);
        try {
            int ret = chooser.showOpenDialog(parent);
            if (ret == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                return chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            }
            return null;
        } finally {
            parent.dispose();
        }
    }
}
