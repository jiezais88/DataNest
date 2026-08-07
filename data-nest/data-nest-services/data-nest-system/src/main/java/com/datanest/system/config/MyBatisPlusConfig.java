package com.datanest.system.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 微服务化 4.4：SysUser 体系（实体/mapper/service）已迁入本模块 com.datanest.system.*，
 * SysUserService 自带 @Service 由组件扫描装配，无需 @Import；
 * task.core.mapper 已无内容，扫描移除。
 */
@Configuration
@MapperScan("com.datanest.system.mapper")
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    @Bean
    public IdentifierGenerator idGenerator() {
        return new com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator();
    }
}
