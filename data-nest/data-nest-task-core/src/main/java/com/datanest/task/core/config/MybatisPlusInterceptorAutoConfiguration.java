package com.datanest.task.core.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * MyBatis-Plus 拦截器自动配置（task-core 域，所有消费方共享）。
 * <p>
 * engineering/governance/system 各自注册过 MybatisPlusInterceptor，
 * 但 job/worker 模块没有——导致 job 模块分页查询退化为全量查询、
 * node_execution 的 {@code @Version} 乐观锁不生效。
 * <p>
 * {@link ConditionalOnMissingBean} 保证消费方自带的配置优先（行为不变），
 * 未配置的服务（job/worker）由本自动配置兜底提供。
 */
@AutoConfiguration
public class MybatisPlusInterceptorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MybatisPlusInterceptor.class)
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
