package com.datanest.engineering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class TestConnectionRequest {

    @NotBlank(message = "数据源类型不能为空")
    @Pattern(regexp = "^(MYSQL|POSTGRESQL|DORIS)$", message = "数据源类型只能是 MYSQL、POSTGRESQL 或 DORIS")
    private String type;

    @NotBlank(message = "主机地址不能为空")
    @Size(max = 255, message = "主机地址最多 255 个字符")
    private String host;

    @NotNull(message = "端口不能为空")
    private Integer port;

    @NotBlank(message = "数据库名不能为空")
    @Size(max = 100, message = "数据库名最多 100 个字符")
    private String databaseName;

    @Size(max = 100, message = "Schema 名最多 100 个字符")
    private String schemaName;

    @NotBlank(message = "用户名不能为空")
    @Size(max = 100, message = "用户名最多 100 个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

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
}
