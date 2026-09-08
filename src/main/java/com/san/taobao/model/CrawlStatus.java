package com.san.taobao.model;

public enum CrawlStatus {

    OK("成功"),

    BAD_INPUT("输入无效"),

    SKIPPED("跳过"),

    FAILED("失败");

    private final String label;

    CrawlStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
