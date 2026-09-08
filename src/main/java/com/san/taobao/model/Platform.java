package com.san.taobao.model;

public enum Platform {

    TAOBAO("tb"),
    TMALL("tm");

    private final String code;

    Platform(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
