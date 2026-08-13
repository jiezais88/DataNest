package com.datanest.dataservice.service;

import com.datanest.dataservice.entity.ApiCallLog;
import com.datanest.dataservice.mapper.ApiCallLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * API 调用统计异步写入（Sprint 10 F3，对齐技术文档 D-D8）。
 * <p>
 * 对外调用事件（apiId/keyId/statusCode/durationMs）经内存队列 + 虚拟线程异步落 api_call_log，
 * 不阻塞对外 API 主链路（NAC-6）。队列满时 CallerRunsPolicy 退化为主线程同步写，避免丢统计。
 */
@Service
public class ApiCallLogWriter {

    private static final Logger logger = LoggerFactory.getLogger(ApiCallLogWriter.class);

    /** 调用统计异步写线程池（虚拟线程，队列背压保护） */
    private static final ExecutorService WRITER = new ThreadPoolExecutor(
            1, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(5000),
            Thread.ofVirtual().name("api-call-log-", 0).factory(),
            new ThreadPoolExecutor.CallerRunsPolicy());

    private final ApiCallLogMapper callLogMapper;

    public ApiCallLogWriter(ApiCallLogMapper callLogMapper) {
        this.callLogMapper = callLogMapper;
    }

    /**
     * 异步写入一条调用事件。
     *
     * @param apiId      API ID（可空，如 Key 无效阶段无 API）
     * @param keyId      Key ID（可空）
     * @param keyName    Key 名称快照（Key 物理删除后统计仍显示原名）
     * @param statusCode HTTP 状态码（200/401/404/429/500/503）
     * @param durationMs 耗时毫秒（限流/认证阶段为 null）
     */
    public void write(Long apiId, Long keyId, String keyName, int statusCode, Integer durationMs) {
        if (apiId == null && keyId == null) {
            return; // 无归属的调用事件（Key 无效）不落库
        }
        ApiCallLog log = new ApiCallLog();
        log.setApiId(apiId);
        log.setKeyId(keyId);
        log.setKeyName(keyName);
        log.setStatusCode(statusCode);
        log.setDurationMs(durationMs);
        log.setCreatedAt(LocalDateTime.now());
        CompletableFuture.runAsync(() -> {
            try {
                callLogMapper.insert(log);
            } catch (Exception e) {
                logger.warn("写入 API 调用统计失败: apiId={}, keyId={}, err={}", apiId, keyId, e.getMessage());
            }
        }, WRITER);
    }
}
