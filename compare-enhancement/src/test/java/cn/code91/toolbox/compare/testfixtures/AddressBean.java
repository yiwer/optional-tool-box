package cn.code91.toolbox.compare.testfixtures;

import cn.code91.toolbox.compare.annotation.CompareLabel;

public class AddressBean {

    @CompareLabel("城市")
    private String city;

    @CompareLabel("详细地址")
    private String detail;

    public AddressBean() {
    }

    public AddressBean(String city, String detail) {
        this.city = city;
        this.detail = detail;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
