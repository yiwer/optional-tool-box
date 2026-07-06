package cn.code91.toolbox.compare.testfixtures;

import cn.code91.toolbox.compare.annotation.CompareIgnore;
import cn.code91.toolbox.compare.annotation.CompareLabel;
import cn.code91.toolbox.compare.annotation.CompareWith;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 普通 JavaBean 测试夹具：覆盖叶子类型、嵌套对象、BY_INDEX 集合、忽略字段、自定义比较器优先级。
 */
public class OrderBean {

    @CompareIgnore
    private Long id;

    @CompareLabel(value = "订单金额", messageKey = "order.amount")
    private BigDecimal amount;

    private String remark;

    private OrderStatus status;

    private LocalDate orderDate;

    private LocalDateTime createdAt;

    private OffsetDateTime confirmedAt;

    @CompareLabel("收货地址")
    private AddressBean address;

    @CompareLabel("明细")
    private List<ItemBean> items;

    @CompareWith(AlwaysEqualComparator.class)
    private String code;

    public OrderBean() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public LocalDate getOrderDate() {
        return orderDate;
    }

    public void setOrderDate(LocalDate orderDate) {
        this.orderDate = orderDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(OffsetDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public AddressBean getAddress() {
        return address;
    }

    public void setAddress(AddressBean address) {
        this.address = address;
    }

    public List<ItemBean> getItems() {
        return items;
    }

    public void setItems(List<ItemBean> items) {
        this.items = items;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
