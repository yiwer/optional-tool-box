package cn.code91.toolbox.compare.testfixtures;

import cn.code91.toolbox.compare.annotation.CompareLabel;

import java.math.BigDecimal;

public class ItemBean {

    @CompareLabel("商品名")
    private String name;

    @CompareLabel("单价")
    private BigDecimal price;

    public ItemBean() {
    }

    public ItemBean(String name, BigDecimal price) {
        this.name = name;
        this.price = price;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
