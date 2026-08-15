package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.engineering.dto.ExecutionQueueCreateRequest;
import com.datanest.engineering.dto.ExecutionQueueQueryRequest;
import com.datanest.engineering.dto.ExecutionQueueUpdateRequest;
import com.datanest.engineering.dto.ExecutionQueueVO;
import com.datanest.engineering.entity.Dag;
import com.datanest.engineering.entity.DagExecution;
import com.datanest.engineering.entity.ExecutionQueue;
import com.datanest.engineering.mapper.DagExecutionMapper;
import com.datanest.engineering.mapper.DagMapper;
import com.datanest.engineering.mapper.ExecutionQueueMapper;
import com.datanest.system.api.SystemUserApi;
import com.datanest.task.core.support.SystemUserResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 执行队列服务（Sprint 11 F3 任务资源队列，PRD §6.3 QU-1~7）
 * <p>
 * 队列管理：default 内置队列不可删/名称不可改（QU-5）；删除前校验 DAG 绑定（QU-3）；
 * 自定义队列改名时联动更新绑定 DAG 的 queue_name（保证引用一致）。
 * 运行/等待统计：队列「当前运行数」= dag_execution 该 queue RUNNING 数（PRD B6 允许秒级延迟）。
 */
@Service
public class ExecutionQueueService {

    /** 内置默认队列名（Flyway 种子，V1.7.0） */
    public static final String DEFAULT_QUEUE = "default";

    private static final Pattern QUEUE_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{2,32}$");

    private final ExecutionQueueMapper executionQueueMapper;
    private final DagMapper dagMapper;
    private final DagExecutionMapper dagExecutionMapper;
    private final SystemUserApi systemUserApi;

    public ExecutionQueueService(ExecutionQueueMapper executionQueueMapper,
                                 DagMapper dagMapper,
                                 DagExecutionMapper dagExecutionMapper,
                                 SystemUserApi systemUserApi) {
        this.executionQueueMapper = executionQueueMapper;
        this.dagMapper = dagMapper;
        this.dagExecutionMapper = dagExecutionMapper;
        this.systemUserApi = systemUserApi;
    }

    // ==================== 查询 ====================

    /** 队列列表（全量，给运维/调试用；UI 走 pageQueues） */
    public List<ExecutionQueueVO> listQueues() {
        List<ExecutionQueue> queues = executionQueueMapper.selectList(
                new QueryWrapper<ExecutionQueue>().orderByAsc("id"));
        List<ExecutionQueueVO> vos = enrichStats(queues);
        fillUsernameNames(vos);
        return vos;
    }

    /**
     * 队列分页查询（UI 列表页）。
     * 统计列（running/waiting/dagCount）走批量 SQL，避免 N+1；
     * 用户名回填走 task-core SystemUserResolver（远端不可用降级空 Map，不阻断）。
     */
    public PageResult<ExecutionQueueVO> pageQueues(ExecutionQueueQueryRequest request) {
        Page<ExecutionQueue> page = new Page<>(request.getPage(), request.getPageSize());
        QueryWrapper<ExecutionQueue> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(request.getKeyword())) {
            String keyword = request.getKeyword().trim();
            wrapper.and(w -> w.like("queue_name", keyword).or().like("description", keyword));
        }
        wrapper.orderByAsc("id");

        IPage<ExecutionQueue> result = executionQueueMapper.selectPage(page, wrapper);
        List<ExecutionQueueVO> records = enrichStats(result.getRecords());
        fillUsernameNames(records);
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 按名查队列（用于 DAG 保存校验队列存在）；不存在抛异常 */
    public ExecutionQueue getQueueByName(String queueName) {
        ExecutionQueue queue = executionQueueMapper.selectOne(
                new QueryWrapper<ExecutionQueue>().eq("queue_name", queueName));
        if (queue == null) {
            throw new BusinessException(ErrorCode.EXECUTION_QUEUE_NOT_FOUND, "执行队列不存在: " + queueName);
        }
        return queue;
    }

    /** 所有队列（供 job 调度器/internal 使用） */
    public List<ExecutionQueue> listAll() {
        return executionQueueMapper.selectList(null);
    }

    // ==================== 写操作 ====================

    @Transactional
    public ExecutionQueueVO createQueue(ExecutionQueueCreateRequest request) {
        validateQueueName(request.getQueueName());
        long exists = executionQueueMapper.selectCount(
                new QueryWrapper<ExecutionQueue>().eq("queue_name", request.getQueueName()));
        if (exists > 0) {
            throw new BusinessException(ErrorCode.EXECUTION_QUEUE_NAME_EXISTS);
        }
        ExecutionQueue queue = new ExecutionQueue();
        queue.setQueueName(request.getQueueName());
        queue.setMaxConcurrency(request.getMaxConcurrency());
        queue.setDescription(request.getDescription());
        queue.setIsSystem(false);
        queue.setCreatedBy(currentUserId());
        queue.setCreatedAt(LocalDateTime.now());
        executionQueueMapper.insert(queue);
        return toVOWithStats(queue);
    }

    @Transactional
    public ExecutionQueueVO updateQueue(Long id, ExecutionQueueUpdateRequest request) {
        ExecutionQueue queue = getQueue(id);
        boolean isSystem = Boolean.TRUE.equals(queue.getIsSystem());
        // 系统内置队列：名称不可改（QU-5），并发/描述可改
        if (request.getQueueName() != null && !request.getQueueName().isEmpty()) {
            if (isSystem && !DEFAULT_QUEUE.equals(request.getQueueName())) {
                throw new BusinessException(ErrorCode.EXECUTION_QUEUE_BUILTIN_READONLY, "系统内置队列不可修改名称");
            }
            if (!queue.getQueueName().equals(request.getQueueName())) {
                validateQueueName(request.getQueueName());
                long dup = executionQueueMapper.countByNameExcludeId(request.getQueueName(), id);
                if (dup > 0) {
                    throw new BusinessException(ErrorCode.EXECUTION_QUEUE_NAME_EXISTS);
                }
                // 改名联动：更新绑定该队列的全部 DAG 的 queue_name（保证引用一致）
                dagMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Dag>()
                                .eq("queue_name", queue.getQueueName())
                                .set("queue_name", request.getQueueName()));
                queue.setQueueName(request.getQueueName());
            }
        }
        queue.setMaxConcurrency(request.getMaxConcurrency());
        queue.setDescription(request.getDescription());
        queue.setUpdatedBy(currentUserId());
        queue.setUpdatedAt(LocalDateTime.now());
        executionQueueMapper.updateById(queue);
        return toVOWithStats(queue);
    }

    @Transactional
    public void deleteQueue(Long id) {
        ExecutionQueue queue = getQueue(id);
        if (Boolean.TRUE.equals(queue.getIsSystem())) {
            throw new BusinessException(ErrorCode.EXECUTION_QUEUE_BUILTIN_READONLY, "系统内置队列不可删除");
        }
        // 删除前校验 DAG 绑定（QU-3）：有绑定拒绝删除
        long dagCount = dagMapper.selectCount(new QueryWrapper<Dag>().eq("queue_name", queue.getQueueName()));
        if (dagCount > 0) {
            throw new BusinessException(ErrorCode.EXECUTION_QUEUE_REFERENCED,
                    "执行队列已被 " + dagCount + " 个 DAG 绑定，无法删除");
        }
        executionQueueMapper.deleteById(id);
    }

    // ==================== 队列调度辅助（trigger 排队 + job 调度器共用） ====================

    /** 队列当前运行数（RUNNING 执行数） */
    public int runningCount(String queueName) {
        return Math.toIntExact(dagExecutionMapper.selectCount(
                new QueryWrapper<DagExecution>().eq("queue_name", queueName).eq("status", "RUNNING")));
    }

    /** 队列等待数（WAITING 执行数） */
    public int waitingCount(String queueName) {
        return Math.toIntExact(dagExecutionMapper.selectCount(
                new QueryWrapper<DagExecution>().eq("queue_name", queueName).eq("status", "WAITING")));
    }

    // ==================== 内部辅助 ====================

    private ExecutionQueue getQueue(Long id) {
        ExecutionQueue queue = executionQueueMapper.selectById(id);
        if (queue == null) {
            throw new BusinessException(ErrorCode.EXECUTION_QUEUE_NOT_FOUND);
        }
        return queue;
    }

    private void validateQueueName(String name) {
        if (name == null || !QUEUE_NAME_PATTERN.matcher(name).matches()) {
            throw new BusinessException(ErrorCode.EXECUTION_QUEUE_NAME_INVALID);
        }
    }

    /**
     * 把 entity 拷贝成 VO（不含统计列），用于批查后填充 running/waiting/dagCount。
     */
    private ExecutionQueueVO toBaseVO(ExecutionQueue queue) {
        ExecutionQueueVO vo = new ExecutionQueueVO();
        vo.setId(queue.getId());
        vo.setQueueName(queue.getQueueName());
        vo.setMaxConcurrency(queue.getMaxConcurrency());
        vo.setDescription(queue.getDescription());
        vo.setIsSystem(queue.getIsSystem());
        vo.setCreatedAt(queue.getCreatedAt());
        vo.setUpdatedAt(queue.getUpdatedAt());
        vo.setCreatedBy(queue.getCreatedBy());
        vo.setUpdatedBy(queue.getUpdatedBy());
        return vo;
    }

    /** 单条 VO：含统计列（创建/更新/详情返回场景，单条 N+1 影响可忽略） */
    private ExecutionQueueVO toVOWithStats(ExecutionQueue queue) {
        ExecutionQueueVO vo = toBaseVO(queue);
        vo.setRunningCount(runningCount(queue.getQueueName()));
        vo.setWaitingCount(waitingCount(queue.getQueueName()));
        vo.setDagCount(dagMapper.selectCount(new QueryWrapper<Dag>().eq("queue_name", queue.getQueueName())));
        return vo;
    }

    /**
     * 批量 enrich：一次查全队列的 running/waiting/dagCount 三组 Map，避免每条 3 次 count 的 N+1。
     */
    private List<ExecutionQueueVO> enrichStats(List<ExecutionQueue> queues) {
        if (queues == null || queues.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> queueNames = queues.stream().map(ExecutionQueue::getQueueName).toList();

        // 运行/等待：dag_execution WHERE queue_name IN (...) AND status IN ('RUNNING','WAITING') GROUP BY
        Map<String, Integer> runningMap = new HashMap<>();
        Map<String, Integer> waitingMap = new HashMap<>();
        List<DagExecution> executions = dagExecutionMapper.selectList(
                new QueryWrapper<DagExecution>()
                        .select("queue_name", "status")
                        .in("queue_name", queueNames)
                        .in("status", List.of("RUNNING", "WAITING")));
        for (DagExecution e : executions) {
            Map<String, Integer> target = "RUNNING".equals(e.getStatus()) ? runningMap : waitingMap;
            target.merge(e.getQueueName(), 1, Integer::sum);
        }

        // DAG 绑定：dag WHERE queue_name IN (...) GROUP BY queue_name
        Map<String, Long> dagCountMap = new HashMap<>();
        List<Dag> dags = dagMapper.selectList(
                new QueryWrapper<Dag>().select("queue_name").in("queue_name", queueNames));
        for (Dag d : dags) {
            dagCountMap.merge(d.getQueueName(), 1L, Long::sum);
        }

        return queues.stream()
                .map(q -> {
                    ExecutionQueueVO vo = toBaseVO(q);
                    vo.setRunningCount(runningMap.getOrDefault(q.getQueueName(), 0));
                    vo.setWaitingCount(waitingMap.getOrDefault(q.getQueueName(), 0));
                    vo.setDagCount(dagCountMap.getOrDefault(q.getQueueName(), 0L));
                    return vo;
                })
                .toList();
    }

    /** 用户名批量回填（委托 task-core SystemUserResolver） */
    private void fillUsernameNames(List<ExecutionQueueVO> vos) {
        if (vos == null || vos.isEmpty()) {
            return;
        }
        List<Long> userIds = vos.stream()
                .flatMap(v -> java.util.stream.Stream.of(v.getCreatedBy(), v.getUpdatedBy()))
                .filter(java.util.Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, String> usernameMap = SystemUserResolver.usernames(systemUserApi, userIds);
        for (ExecutionQueueVO vo : vos) {
            if (vo.getCreatedBy() != null) {
                vo.setCreatedByName(usernameMap.get(vo.getCreatedBy()));
            }
            if (vo.getUpdatedBy() != null) {
                vo.setUpdatedByName(usernameMap.get(vo.getUpdatedBy()));
            }
        }
    }

    private long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }
}
