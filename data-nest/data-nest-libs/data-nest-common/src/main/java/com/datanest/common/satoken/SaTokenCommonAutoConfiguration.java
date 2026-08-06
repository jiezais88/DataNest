package com.datanest.common.satoken;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Sa-Token 公共组件自动配置。
 * <p>
 * 向所有消费 data-nest-common 的微服务注入 StpInterfaceImpl，使 @SaCheckRole 注解能够跨服务生效。
 */
@AutoConfiguration
@ComponentScan("com.datanest.common.satoken")
public class SaTokenCommonAutoConfiguration {
}
