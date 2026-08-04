import crypto from 'crypto';

/**
 * 复刻后端 EncryptionConfig（AES-256-GCM）的加密逻辑，供播种时生成可解密的数据源密码密文。
 *
 * 与后端一致：
 * - 密钥：SHA-256(配置 key) 截取 32 字节作为 AES 密钥
 * - IV：12 字节随机
 * - 输出：Base64( IV || ciphertext )，GCM tag 128-bit
 *
 * 后端 key 默认 `DataNestDefaultEncryptionKey2026`（shared-security.yaml 的
 * `datanest.security.encryption.key`，可被 DATANEST_ENCRYPTION_KEY 覆盖）。
 */

const TRANSFORMATION = 'aes-256-gcm';
const GCM_IV_LENGTH = 12;

export function encryptDataSourcePassword(
    plaintext: string,
    key: string = 'DataNestDefaultEncryptionKey2026',
): string {
    const secretKey = crypto.createHash('sha256').update(key, 'utf-8').digest();
    const iv = crypto.randomBytes(GCM_IV_LENGTH);
    const cipher = crypto.createCipheriv(TRANSFORMATION, secretKey, iv);
    const ciphertext = Buffer.concat([cipher.update(plaintext, 'utf-8'), cipher.final()]);
    // authTag 默认 128-bit，须拼接在密文后（与后端 doFinal 输出一致）
    const authTag = cipher.getAuthTag();
    return Buffer.concat([iv, ciphertext, authTag]).toString('base64');
}
