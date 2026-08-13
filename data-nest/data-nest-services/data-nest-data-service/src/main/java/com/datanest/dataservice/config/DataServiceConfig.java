package com.datanest.dataservice.config;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.task.core.config.DorisDataSourceConfig;
import com.datanest.task.core.service.DorisSqlExecutor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 显式声明 common / task-core / Kafka 组件（数据服务扫描包不含这些包）。
 * <p>
 * - EncryptionConfig：数据源密码 AES 解密（@ConfigurationProperties(datanest.security.encryption)
 *   对 @Bean 方式注册同样生效，密钥由 shared-security.yaml 注入）。
 * - DorisDataSourceConfig：内置 Doris 连接信息注入静态字段（@Value + @PostConstruct，
 *   懒建 Hikari 连接池，不可达降级 DriverManager）。
 * - DorisSqlExecutor：内置 Doris SQL 执行器（无状态，内部经静态 getter 拿连接）。
 * - Kafka ConsumerFactory / ListenerContainerFactory：F4 事件总线消费。显式声明（Spring Boot 4
 *   对 spring-kafka 的自动配置未创建默认 factory，避免 @KafkaListener 无 factory 报错）。
 */
@Configuration
public class DataServiceConfig {

    @Bean
    public EncryptionConfig encryptionConfig() {
        return new EncryptionConfig();
    }

    @Bean
    public DorisDataSourceConfig dorisDataSourceConfig() {
        return new DorisDataSourceConfig();
    }

    @Bean
    public DorisSqlExecutor dorisSqlExecutor() {
        return new DorisSqlExecutor();
    }

    @Bean
    public ConsumerFactory<String, String> kafkaConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers:middleware-kafka:9092}") String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "datanest-data-service");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // F4：topicPattern 消费者需及时感知「管道启动后新建的 cdc-events-*」topic，
        // 否则默认 metadata 刷新（5min）期间新管道事件无法被消费。缩短到 10s。
        props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 10000);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }
}
