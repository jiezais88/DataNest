#!/usr/bin/env node
/**
 * DataNest Sprint 14 测试用 Mock OIDC IdP
 * ---------------------------------------------------------------
 * 实现标准 OIDC 授权码流程（供 E2E 自测，非生产代码）：
 *   GET  /.well-known/openid-configuration   OIDC Discovery
 *   GET  /authorize?user=<uid>&...           授权端点（user 参数指定 mock 用户，302 发 code）
 *   POST /token                              令牌端点（返回 access_token + RS256 id_token）
 *   GET  /jwks                               RSA 公钥 JWK（验证 id_token 签名用）
 * 用法： node server.js [port=9040]
 * 测试用户：alice(组 datanest-engineers) / bob(组 datanest-admins) / carol(无组)
 */
const http = require('node:http');
const crypto = require('node:crypto');

const PORT = parseInt(process.argv[2] || '9040', 10);
// 用法： node server.js [port] [issuer]（issuer 用于系统容器内访问，如 http://host.docker.internal:9040）
const ISSUER = process.argv[3] || `http://localhost:${PORT}`;
const CLIENT_ID = 'datanest';
const CLIENT_SECRET = 'mock-client-secret';
const REDIRECT_URI = 'http://localhost:8080/api/system/auth/sso/oidc/callback';
const KID = 'mock-idp-1';

// 内存 RSA 密钥对（RS256 签名 + JWK 发布）
const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const publicJwk = publicKey.export({ format: 'jwk' });
publicJwk.kid = KID;
publicJwk.use = 'sig';
publicJwk.alg = 'RS256';

const USERS = {
  alice: { sub: 'mock-sub-alice-001', email: 'alice@datanest.local', name: 'Alice Chen', preferred_username: 'alice', groups: ['datanest-engineers'] },
  bob: { sub: 'mock-sub-bob-002', email: 'bob@datanest.local', name: 'Bob Li', preferred_username: 'bob', groups: ['datanest-admins'] },
  carol: { sub: 'mock-sub-carol-003', email: 'carol@datanest.local', name: 'Carol Wang', preferred_username: 'carol', groups: [] },
  dave: { sub: 'mock-sub-dave-004', email: 'dave@datanest.local', name: 'Dave Sun', preferred_username: 'dave', groups: ['datanest-engineers'] },
};

const codes = new Map(); // code -> { userId, exp }

function b64url(buf) {
  return Buffer.from(buf).toString('base64url');
}

function signJwt(claims) {
  const header = { alg: 'RS256', typ: 'JWT', kid: KID };
  const now = Math.floor(Date.now() / 1000);
  const full = {
    iss: ISSUER,
    aud: CLIENT_ID,
    iat: now,
    exp: now + 600,
    ...claims,
  };
  const head = b64url(JSON.stringify(header));
  const payload = b64url(JSON.stringify(full));
  const signer = crypto.createSign('RSA-SHA256');
  signer.update(`${head}.${payload}`);
  const sig = signer.sign(privateKey);
  return `${head}.${payload}.${b64url(sig)}`;
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', () => resolve(data));
  });
}

function writeJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const path = url.pathname;

  if (path === '/.well-known/openid-configuration') {
    return writeJson(res, 200, {
      issuer: ISSUER,
      authorization_endpoint: `${ISSUER}/authorize`,
      token_endpoint: `${ISSUER}/token`,
      jwks_uri: `${ISSUER}/jwks`,
      response_types_supported: ['code'],
      subject_types_supported: ['public'],
      id_token_signing_alg_values_supported: ['RS256'],
    });
  }

  if (path === '/jwks') {
    return writeJson(res, 200, { keys: [publicJwk] });
  }

  if (path === '/authorize' && req.method === 'GET') {
    const response_type = url.searchParams.get('response_type');
    const client_id = url.searchParams.get('client_id');
    const redirect_uri = url.searchParams.get('redirect_uri');
    const state = url.searchParams.get('state');
    const user = url.searchParams.get('user');
    if (response_type !== 'code' || client_id !== CLIENT_ID || redirect_uri !== REDIRECT_URI) {
      return writeJson(res, 400, { error: 'invalid_request', error_description: '参数不合法（client_id/redirect_uri 需匹配）' });
    }
    const uid = user || 'alice';
    if (!USERS[uid]) {
      return writeJson(res, 400, { error: 'invalid_user', error_description: `mock 用户不存在: ${uid}（可选: alice/bob/carol）` });
    }
    const code = crypto.randomBytes(16).toString('hex');
    codes.set(code, { userId: uid, exp: Date.now() + 60_000 });
    const target = `${redirect_uri}?code=${code}&state=${state || ''}`;
    res.writeHead(302, { Location: target });
    return res.end();
  }

  if (path === '/token' && req.method === 'POST') {
    const body = await readBody(req);
    const params = new URLSearchParams(body);
    const { grant_type, code, redirect_uri, client_id, client_secret } = Object.fromEntries(params);
    if (grant_type !== 'authorization_code' || client_id !== CLIENT_ID || client_secret !== CLIENT_SECRET
        || redirect_uri !== REDIRECT_URI) {
      return writeJson(res, 400, { error: 'invalid_grant', error_description: 'client 校验失败' });
    }
    const entry = codes.get(code);
    if (!entry || entry.exp < Date.now()) {
      return writeJson(res, 400, { error: 'invalid_grant', error_description: 'code 无效或已过期' });
    }
    codes.delete(code);
    const user = USERS[entry.userId];
    const idToken = signJwt({
      sub: user.sub,
      email: user.email,
      email_verified: true,
      name: user.name,
      preferred_username: user.preferred_username,
      groups: user.groups,
    });
    return writeJson(res, 200, {
      access_token: crypto.randomBytes(24).toString('hex'),
      token_type: 'Bearer',
      expires_in: 600,
      id_token: idToken,
    });
  }

  writeJson(res, 404, { error: 'not_found', error_description: path });
});

server.listen(PORT, () => {
  console.log(`[mock-idp] listening on ${ISSUER}`);
  console.log(`[mock-idp] users: ${Object.keys(USERS).join(', ')}`);
});
