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
 * Sprint 4 架构调整：统一由 task-core 提供分页 + 乐观锁拦截器，
 * engineering/worker/job/governance 不再各自维护 MybatisPlusConfig，
 * 避免部分模块缺分页插件或乐观锁插件导致的问题。
 * <p>
 * {@link ConditionalOnMissingBean} 保证消费方自带的配置优先（行为不变），
 * 未配置的服务由本自动配置兜底提供。
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
