package com.datanest.realtime.service;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Savepoint 文件物理清理（Sprint 9 F2，T3）。
 * <p>
 * 用 MinIO Java Client 删除管道已知的 savepoint 文件（s3a://bucket/savepoints/savepoint-xxx 前缀下全部对象，
 * 含目录对象）。配置复用 shared-minio.yaml（endpoint/access-key/secret-key/bucket）。
 * 删除失败 warn 留痕并返回 false，不抛异常（调用方不阻断主流程，R5：删文件失败不阻断删除管道）。
 * 全局孤儿 savepoint 扫描不做（NG10，运维侧兜底）。
 */
@Service
public class SavepointFileCleaner {

    private static final Logger logger = LoggerFactory.getLogger(SavepointFileCleaner.class);

    private final MinioClient minioClient;
    private final String bucket;

    public SavepointFileCleaner(@Value("${datanest.minio.endpoint}") String endpoint,
                                @Value("${datanest.minio.access-key}") String accessKey,
                                @Value("${datanest.minio.secret-key}") String secretKey,
                                @Value("${datanest.minio.bucket:datalake}") String bucket) {
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
    }

    /**
     * 删除 savepoint 路径对应的全部对象（前缀匹配，递归）。
     *
     * @param savepointPath s3a://bucket/savepoints/savepoint-xxx
     * @return true 已清理或无文件；false 清理失败（warn 已留痕，调用方按需记管道日志）
     */
    public boolean deleteSavepoint(String savepointPath) {
        if (savepointPath == null || savepointPath.isBlank()) {
            return true;
        }
        ParsedPath parsed = parse(savepointPath);
        if (parsed == null) {
            logger.warn("savepoint 路径格式无法解析，跳过清理: {}", savepointPath);
            return false;
        }
        // bucket 与配置不一致：跳过（避免误删别的 bucket；配置校验失败 warn 留痕）
        if (!bucket.equals(parsed.bucket)) {
            logger.warn("savepoint 路径 bucket 与配置不一致，跳过清理: path={}, configuredBucket={}",
                    savepointPath, bucket);
            return false;
        }
        try {
            List<String> keys = listObjectKeys(parsed.bucket, parsed.prefix);
            if (keys.isEmpty()) {
                logger.info("savepoint 前缀下无对象，无需清理: path={}", savepointPath);
                return true;
            }
            removeObjects(parsed.bucket, keys);
            logger.info("savepoint 文件已清理: path={}, objects={}", savepointPath, keys.size());
            return true;
        } catch (Exception e) {
            logger.warn("删除 savepoint 文件失败（不阻断主流程）: path={}, error={}", savepointPath, e.getMessage());
            return false;
        }
    }

    /** 列出前缀下全部对象名（递归，含目录对象） */
    private List<String> listObjectKeys(String bucket, String prefix) throws Exception {
        List<String> keys = new ArrayList<>();
        Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucket)
                .prefix(prefix)
                .recursive(true)
                .build());
        for (Result<Item> result : results) {
            keys.add(result.get().objectName());
        }
        return keys;
    }

    /** 批量删除对象（逐条吞掉 DeleteError，记录 warn） */
    private void removeObjects(String bucket, List<String> keys) throws Exception {
        List<DeleteObject> deleteObjects = keys.stream().map(DeleteObject::new).toList();
        Iterable<Result<DeleteError>> results = minioClient.removeObjects(RemoveObjectsArgs.builder()
                .bucket(bucket)
                .objects(deleteObjects)
                .build());
        for (Result<DeleteError> result : results) {
            DeleteError error = result.get();
            if (error != null) {
                logger.warn("savepoint 对象删除失败: bucket={}, object={}, error={}",
                        bucket, error.objectName(), error.message());
            }
        }
    }

    /** 解析 s3a://bucket/object-prefix（无前缀返回 null） */
    private ParsedPath parse(String path) {
        if (!path.startsWith("s3a://")) {
            return null;
        }
        String rest = path.substring("s3a://".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        return new ParsedPath(rest.substring(0, slash), rest.substring(slash + 1));
    }

    private record ParsedPath(String bucket, String prefix) {
    }
}
