package com.san.taobao.config;

import java.nio.file.Path;

/** 系统属性读取，只在 config 与 cli 入口使用。 */
public final class SystemProps {

    private SystemProps() {
    }

    public static String text(String key, String def) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    public static boolean flag(String key, boolean def) {
        return Boolean.parseBoolean(text(key, String.valueOf(def)));
    }

    public static int number(String key, int def) {
        try {
            return Integer.parseInt(text(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static Path path(String key, Path def) {
        String v = text(key, "");
        return v.isEmpty() ? def : Path.of(v);
    }

    public static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
