package com.datanest.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * MyBatis-Plus 拦截器自动配置（common 域，所有消费方共享）。
 * <p>
 * 2026-08-12 由 task-core 迁入 common（alert/realtime/system 等不依赖 task-core 的持库服务同样需要），
 * 统一提供分页 + 乐观锁拦截器，避免部分模块缺分页插件或乐观锁插件导致的问题。
 * <p>
 * {@link ConditionalOnMissingBean} 保证消费方自带的配置优先（行为不变），
 * 未配置的服务由本自动配置兜底提供；{@link ConditionalOnClass} 保证无 MyBatis-Plus 的无库服务
 * （worker/job/gateway）不加载。
 */
@AutoConfiguration
@ConditionalOnClass(MybatisPlusInterceptor.class)
public class MybatisPlusInterceptorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MybatisPlusInterceptor.class)
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    /**
     * 默认 ID 生成器（雪花算法）。
     * <p>
     * 2026-08-12 从 system 本地 MyBatisPlusConfig 迁入：system 的 UserService 显式注入
     * {@link IdentifierGenerator}，删除本地配置类后由本兜底提供，行为与原 DefaultIdentifierGenerator 一致；
     * {@link ConditionalOnMissingBean} 保证消费方自定义生成器优先。
     */
    @Bean
    @ConditionalOnMissingBean(IdentifierGenerator.class)
    public IdentifierGenerator identifierGenerator() {
        return new DefaultIdentifierGenerator();
    }
}
