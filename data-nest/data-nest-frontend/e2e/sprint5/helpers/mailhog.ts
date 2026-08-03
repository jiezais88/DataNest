import {type APIRequestContext, request as pwRequest} from '@playwright/test';

const MAILHOG_API = 'http://localhost:8025/api/v2';

export interface MailhogAddress {
    Mailbox: string;
    Domain: string;
}

export interface MailhogMessage {
    ID: string;
    To: MailhogAddress[];
    Content: {
        Headers: Record<string, string[]>;
        Body: string;
    };
    Created: string;
}

/** 解码 MIME encoded-word（=?UTF-8?Q?xxx?= / =?UTF-8?B?xxx?=），Q 编码按 UTF-8 字节还原 */
function decodeMimeEncoded(str: string): string {
    return str.replace(/=\?([^?]+)\?([QqBb])\?([^?]*)\?=/g, (_m, _charset, enc, text) => {
        if (enc.toLowerCase() === 'q') {
            const bytes: number[] = [];
            let i = 0;
            const s = text.replace(/_/g, ' ');
            while (i < s.length) {
                if (s[i] === '=' && i + 2 < s.length && /^[0-9A-Fa-f]{2}$/.test(s.slice(i + 1, i + 3))) {
                    bytes.push(parseInt(s.slice(i + 1, i + 3), 16));
                    i += 3;
                } else {
                    const code = s.charCodeAt(i);
                    if (code < 128) bytes.push(code);
                    else Buffer.from(s[i], 'utf8').forEach((b) => bytes.push(b));
                    i++;
                }
            }
            return Buffer.from(bytes).toString('utf8');
        }
        return Buffer.from(text, 'base64').toString('utf8');
    });
}

export class Mailhog {
    private ctx?: APIRequestContext;

    async init(): Promise<void> {
        this.ctx = await pwRequest.newContext();
    }

    private async list(): Promise<MailhogMessage[]> {
        if (!this.ctx) throw new Error('Mailhog 未初始化');
        const res = await this.ctx.fetch(`${MAILHOG_API}/messages`);
        const json = await res.json();
        return json.items ?? [];
    }

    /** 当前邮件总数 */
    async count(): Promise<number> {
        const res = await this.ctx!.fetch(`${MAILHOG_API}/messages`);
        const json = await res.json();
        return json.total ?? 0;
    }

    /** 清空所有邮件 */
    async deleteAll(): Promise<void> {
        await this.ctx!.fetch(`${MAILHOG_API}/messages`, {method: 'DELETE'});
    }

    /** 查找主题解码后包含关键词的邮件 */
    async find(subjectKeyword: string): Promise<MailhogMessage[]> {
        const msgs = await this.list();
        return msgs.filter((m) => {
            const raw = m.Content.Headers?.Subject?.[0] ?? '';
            return decodeMimeEncoded(raw).includes(subjectKeyword);
        });
    }

    async dispose(): Promise<void> {
        await this.ctx?.dispose();
    }
}
