package com.datanest.engineering.dto;

import com.datanest.common.constant.DataSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "数据源创建请求")
public class DataSourceCreateRequest {

    @Schema(description = "数据源名称")
    @NotBlank(message = "数据源名称不能为空")
    @Size(max = 100, message = "数据源名称最多 100 个字符")
    private String name;

    @Schema(description = "数据源类型（MYSQL/POSTGRESQL/DORIS/ORACLE/SQLSERVER）")
    @NotBlank(message = "数据源类型不能为空")
    @Pattern(regexp = DataSourceType.PATTERN, message = "数据源类型只能是 " + DataSourceType.ALLOWED_LABELS)
    private String type;

    @Schema(description = "主机地址")
    @NotBlank(message = "主机地址不能为空")
    @Size(max = 255, message = "主机地址最多 255 个字符")
    private String host;

    @Schema(description = "端口")
    @NotNull(message = "端口不能为空")
    private Integer port;

    @Schema(description = "数据库名")
    @NotBlank(message = "数据库名不能为空")
    @Size(max = 100, message = "数据库名最多 100 个字符")
    private String databaseName;

    @Schema(description = "Schema 名")
    @Size(max = 100, message = "Schema 名最多 100 个字符")
    private String schemaName;

    @Schema(description = "用户名")
    @NotBlank(message = "用户名不能为空")
    @Size(max = 100, message = "用户名最多 100 个字符")
    private String username;

    @Schema(description = "密码")
    @NotBlank(message = "密码不能为空")
    private String password;

    @Schema(description = "描述")
    @Size(max = 500, message = "描述最多 500 个字符")
    private String description;

    @Schema(description = "保存后是否自动触发元数据采集")
    private Boolean autoCollectOnSave;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getAutoCollectOnSave() {
        return autoCollectOnSave;
    }

    public void setAutoCollectOnSave(Boolean autoCollectOnSave) {
        this.autoCollectOnSave = autoCollectOnSave;
    }
}
