package com.datanest.governance.dto;

import lombok.Data;

@Data
public class MetadataDatasourceDTO {

    private Long id;

    private String name;

    private String type;

    private Boolean exists;

    private String sourceType;
}
