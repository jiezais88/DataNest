package com.datanest.common.jackson;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * 全局 Jackson 序列化配置：将 Long / long 输出为字符串，避免前端 JavaScript 精度丢失。
 * <p>
 * 通过 Spring Boot 自动配置机制（META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports）
 * 向所有依赖 data-nest-common 的服务自动生效，无需在主类上额外扫描。
 * <p>
 * Spring Boot 4.x 默认使用 Jackson 3，仅声明 {@link SimpleModule} Bean 无法保证被自动注册到 JsonMapper，
 * 需通过 {@link JsonMapperBuilderCustomizer#addModule(tools.jackson.databind.Module)} 显式初始化并注册模块。
 */
@AutoConfiguration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("long-to-string");
            module.addSerializer(Long.class, new LongToStringSerializer());
            module.addSerializer(Long.TYPE, new LongToStringSerializer());
            builder.addModule(module);
        };
    }

    public static class LongToStringSerializer extends ValueSerializer<Long> {

        @Override
        public void serialize(Long value, JsonGenerator gen, SerializationContext ctxt)
                throws JacksonException {
            if (value == null) {
                gen.writeNull();
            } else {
                gen.writeString(value.toString());
            }
        }
    }
}
