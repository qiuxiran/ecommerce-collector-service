package com.san.taobao.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * 输出模型，字段名与顺序严格对齐目标 JSON 结构（snake_case 由 {@link SerializedName} 保证，
 * Java 侧仍用驼峰）。Gson 按声明顺序序列化，因此字段顺序即输出顺序。
 * <p>
 * 抓不到的字段保持 {@code null}（而非空串或空数组），便于一眼看出是"商品没有"还是"解析失败"。
 * 序列化时开启 serializeNulls，所以 {@code null} 字段会以 {@code "desc": null} 的形式保留。
 */
public class ItemDetail {

    @SerializedName("item_id")
    public String itemId;

    @SerializedName("item_code")
    public String itemCode;

    @SerializedName("item_name")
    public String itemName;

    @SerializedName("category_id")
    public String categoryId = "";

    @SerializedName("desc")
    public String desc;

    @SerializedName("main_video_arr")
    public List<String> mainVideoArr;

    @SerializedName("desc_video_arr")
    public List<String> descVideoArr;

    @SerializedName("main_img_arr")
    public List<String> mainImgArr;

    @SerializedName("desc_img_arr")
    public List<String> descImgArr;

    @SerializedName("attr_list")
    public List<Attr> attrList = new ArrayList<>();

    @SerializedName("sku_list")
    public List<Sku> skuList = new ArrayList<>();

    /** 平台标识，取值见 {@link Platform#code()} */
    @SerializedName("plt_id")
    public String pltId = Platform.TAOBAO.code();

    /**
     * 数据质量诊断：价格取自整品级兜底（而非逐 SKU）的 SKU 条数。
     * <p>
     * 大于 0 说明这些 SKU 的价格是整品展示价，同一商品内不区分规格。
     * {@code transient} 保证它不进导出 JSON，导出结构与目标 schema 保持一致。
     */
    public transient int itemLevelPricedSkuCount;

    /** 数据质量诊断：价格靠浏览器点选规格补回来的 SKU 条数。同样 {@code transient}，不进导出 JSON。 */
    public transient int clickPricedSkuCount;

    /** 商品属性，如 {@code {"name":"食物适配类型","val":"西瓜"}}。多值用英文逗号拼接。 */
    public static class Attr {
        @SerializedName("name")
        public String name;

        @SerializedName("val")
        public String val;

        public Attr(String name, String val) {
            this.name = name;
            this.val = val;
        }
    }

    public static class Sku {
        @SerializedName("img_url")
        public String imgUrl;

        /** 原价／划线价，取不到时回落为 price */
        @SerializedName("cost_price")
        public Double costPrice;

        @SerializedName("price")
        public Double price;

        @SerializedName("stock")
        public Integer stock;

        @SerializedName("sku_prop_list")
        public List<SkuProp> skuPropList = new ArrayList<>();
    }

    public static class SkuProp {
        @SerializedName("prop_id")
        public String propId;

        @SerializedName("prop_name")
        public String propName;

        @SerializedName("val_id")
        public String valId;

        @SerializedName("val")
        public String val;

        @SerializedName("img_url")
        public String imgUrl;
    }
}
