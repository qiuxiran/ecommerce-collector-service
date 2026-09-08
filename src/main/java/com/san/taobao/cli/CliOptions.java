package com.san.taobao.cli;

import com.san.taobao.config.SystemProps;

/**
 * 只跟命令行入口有关的选项。抓取行为的配置在 {@code CrawlConfig}，两者分开，
 * 免得服务化后带一堆终端专属字段。
 *
 * @param presetInput      启动时就给定的输入（链接列表文件或单条链接），无则为 null
 * @param forceInteractive 终端可交互但检测有误时强制继续
 */
public record CliOptions(String presetInput, boolean forceInteractive) {

    public static CliOptions from(String[] args) {
        return new CliOptions(
                firstNonBlank(SystemProps.text("taobao.urls.file", ""), args),
                SystemProps.flag("taobao.force-interactive", false));
    }

    public boolean hasPresetInput() {
        return presetInput != null;
    }

    private static String firstNonBlank(String preset, String[] args) {
        if (!preset.isBlank()) {
            return preset.trim();
        }
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            return args[0].trim();
        }
        return null;
    }
}
