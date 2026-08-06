package com.datanest.governance.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MetadataRemarkRequest {

    @NotNull
    private String remark;
}
