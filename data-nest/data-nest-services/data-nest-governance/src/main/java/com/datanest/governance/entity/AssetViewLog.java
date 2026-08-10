package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 资产热度按天聚合（Sprint 8 F1 DC-09）：(table_id, view_date) 唯一，埋点 upsert 累加。
 */
@Data
@TableName("asset_view_log")
public class AssetViewLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** metadata_table.id */
    private Long tableId;

    /** 访问日期 */
    private LocalDate viewDate;

    /** 当日访问数 */
    private Integer viewCount;

    /** 最近累加时间 */
    private LocalDateTime updatedAt;
}
