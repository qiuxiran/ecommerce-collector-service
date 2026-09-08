package com.san.taobao.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/** 控制台读入。唯一持有 {@code System.in} 的地方。 */
public final class ConsoleIo {

    private final BufferedReader reader;

    public ConsoleIo(InputStream stream) {
        this.reader = new BufferedReader(new InputStreamReader(stream));
    }

    /**
     * 这个程序全程依赖人工输入（登录、输链接、过验证），非交互环境下每个等待输入的点都会立刻 EOF。
     * 所以要在碰网络之前就拦掉，避免退化成对淘宝的无人值守请求循环。
     */
    public static boolean isInteractive(boolean force) {
        return force || System.console() != null;
    }

    /** 打印提示符并读一行，EOF 抛 {@link NonInteractiveException}。 */
    public String prompt() {
        System.out.print("> ");
        return readLine();
    }

    public String readLine() {
        String line;
        try {
            line = reader.readLine();
        } catch (IOException e) {
            throw new NonInteractiveException();
        }
        if (line == null) {
            throw new NonInteractiveException();
        }
        return line.trim();
    }
}
