package com.san.taobao.model;

public enum PriceSource {

    SKU("sku"),
    CLICK("click"),
    ITEM("item"),
    MIXED("mixed"),
    NONE("none");

    private final String code;

    PriceSource(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
