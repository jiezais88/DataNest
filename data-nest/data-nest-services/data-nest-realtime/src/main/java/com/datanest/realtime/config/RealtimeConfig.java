package com.datanest.realtime.config;

import com.datanest.common.config.EncryptionConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式声明 common 的 EncryptionConfig（数据源密码 AES 解密）。
 * <p>
 * 本服务启动类按约定只扫描 com.datanest.realtime / com.datanest.common.internal / api 包，
 * 而 EncryptionConfig 是 com.datanest.common.config 下的普通 @Component，不在扫描范围内，
 * 故在此显式注册；类上的 @ConfigurationProperties(datanest.security.encryption)
 * 对 @Bean 方式注册的实例同样生效，密钥由 shared-security.yaml 注入。
 */
@Configuration
public class RealtimeConfig {

    @Bean
    public EncryptionConfig encryptionConfig() {
        return new EncryptionConfig();
    }
}
