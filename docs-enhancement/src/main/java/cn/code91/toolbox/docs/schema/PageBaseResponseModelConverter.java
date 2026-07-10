package cn.code91.toolbox.docs.schema;

import cn.code91.facility.web.response.PageBaseResponse;

/**
 * {@code PageBaseResponse<X>} schema 命名修饰（裁定 C-2）：{@code {X}PageResponse}
 * （如 {@code UserDtoPageResponse}）。判定/冲突/退回语义同父类，仅目标类型与后缀不同。
 */
public class PageBaseResponseModelConverter extends BaseResponseModelConverter {

    public PageBaseResponseModelConverter() {
        super(PageBaseResponse.class, "PageResponse");
    }
}
