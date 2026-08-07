package com.datanest.engineering.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 通用 ID 列表批量查询请求。
 */
@Data
public class IdsRequest {

    private List<Long> ids;
}
