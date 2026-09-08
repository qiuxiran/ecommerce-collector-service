package com.san.taobao.cli;

/**
 * 标准输入已到 EOF。
 * <p>
 * 非交互环境下 {@code readLine()} 会立刻返回 null。把 null 当成「重试」会让等待输入的循环退化成
 * 高频请求循环 —— 之前就是这么把淘宝首页刷了两百多秒，所以必须硬性区分 EOF 和空行。
 */
public final class NonInteractiveException extends RuntimeException {

    NonInteractiveException() {
        super("标准输入已到 EOF，需要交互式控制台");
    }
}
