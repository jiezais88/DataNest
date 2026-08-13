// Sprint 10 F4：CDC 管道详情抽屉「实时订阅」页签。
// 订阅文档（地址/认证/协议/示例代码，全角色可看）+ 连接监控（在线连接/今日事件/延迟/失败 + 订阅方 Key 列表）。
// 订阅地址/示例代码由当前浏览器 host 推导（复制即用）；连接监控 RUNNING 时 5s 轮询。
import {useCallback, useEffect, useState} from 'react';
import {Spin, Table} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {HiOutlineClipboardDocument, HiOutlineExclamationTriangle} from 'react-icons/hi2';
import {getSubscriptionStats} from '@/api/data-service';
import DsStatusBadge from '@/components/DsStatusBadge';
import {usePollingWhile} from '@/hooks/usePollingWhile';
import type {CdcPipeline} from '@/types/cdc';
import type {SubscriberItem, SubscriptionStats} from '@/types/data-service';
import {formatDateTime, formatDuration, formatNumber} from '@/utils/format';
import {notify} from '@/utils/notify';
import {KpiCard} from './shared';

/** 分区容器 */
function Section({title, children}: { title: string; children: React.ReactNode }) {
    return (
        <div className="mb-ds-5 last:mb-0">
            <div className="text-ds-small font-semibold text-ds-text-primary mb-ds-2">{title}</div>
            {children}
        </div>
    );
}

/** 键值行 */
function Row({label, children}: { label: string; children: React.ReactNode }) {
    return (
        <div className="flex items-start gap-ds-4 py-ds-1">
            <span className="w-24 flex-shrink-0 text-ds-tiny text-ds-text-muted pt-0.5">{label}</span>
            <span className="text-ds-small text-ds-text-secondary break-all min-w-0">{children}</span>
        </div>
    );
}

/** 代码块（mono + 复制按钮） */
function CodeBlock({code, onCopy}: { code: string; onCopy: () => void }) {
    return (
        <div className="relative bg-ds-bg-hover border border-ds-border-subtle rounded-ds-sm p-ds-3">
            <button
                type="button"
                onClick={onCopy}
                className="absolute top-2 right-2 flex items-center gap-1 text-ds-nano text-ds-text-muted hover:text-ds-accent transition-colors"
            >
                <HiOutlineClipboardDocument size={12}/>
                复制
            </button>
            <pre className="text-ds-tiny font-mono text-ds-text-secondary whitespace-pre-wrap break-all leading-relaxed pr-12">
                {code}
            </pre>
        </div>
    );
}

export default function SubscribeTab({detail}: { detail: CdcPipeline }) {
    const [stats, setStats] = useState<SubscriptionStats | null>(null);
    const [loading, setLoading] = useState(false);

    const load = useCallback(() => {
        return getSubscriptionStats(detail.id)
            .then(r => setStats(r.data ?? null))
            .catch(() => {/* 拦截器已提示 */});
    }, [detail.id]);

    useEffect(() => {
        setLoading(true);
        load().finally(() => setLoading(false));
    }, [detail.id, load]);

    // 运行中 5s 轮询连接监控；10 分钟兜底停止
    usePollingWhile(detail.status === 'RUNNING', load, {interval: 5000, timeout: 600000});

    const running = detail.status === 'RUNNING';
    const wsUrl = `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/api/data-service/ws/events`;
    // Long 主键以字符串持有；示例 JSON 直接拼字符串避免 Number 精度丢失（19 位超 2^53）
    const pipelineId = detail.id;
    const sampleTable = detail.tables?.[0]?.sourceTable ?? 'orders';

    const subscribeMsg = [
        '{',
        '  "op": "subscribe",',
        `  "pipelineId": ${pipelineId}`,
        '}',
    ].join('\n');
    const eventSample = [
        '{',
        `  "pipelineId": ${pipelineId},`,
        `  "table": ${JSON.stringify(sampleTable)},`,
        '  "opType": "INSERT",',
        '  "data": { "id": 1024, "amount": 99.9 },',
        '  "ts": "2026-08-12T10:00:01Z"',
        '}',
    ].join('\n');
    const jsSample = `// 业务端示例（Node.js ws 库；浏览器原生 WebSocket 无法自定义头）
const WebSocket = require('ws');
const ws = new WebSocket('${wsUrl}', {
  headers: { 'X-API-Key': 'K-xxxxxxxx' }
});
ws.on('open', () => ws.send(JSON.stringify({ op: 'subscribe', pipelineId: '${pipelineId}' })));
ws.on('message', (data) => handle(JSON.parse(data)));`;

    const copy = async (text: string, label: string) => {
        try {
            await navigator.clipboard.writeText(text);
            notify.success(`${label}已复制`);
        } catch {
            notify.warning('复制失败，请检查浏览器剪贴板权限');
        }
    };

    const columns: ColumnsType<SubscriberItem> = [
        {
            title: '订阅方 Key',
            dataIndex: 'keyName',
            key: 'keyName',
            render: (v: string) => <span className="text-ds-small font-medium text-ds-text-primary">{v}</span>,
        },
        {
            title: '连接状态',
            dataIndex: 'online',
            key: 'online',
            width: 100,
            render: (v: boolean) => v
                ? <DsStatusBadge variant="running" label="已连接"/>
                : <DsStatusBadge variant="pending" label="离线"/>,
        },
        {
            title: '接收事件',
            dataIndex: 'receivedEvents',
            key: 'receivedEvents',
            width: 100,
            render: (v: string) => <span className="tabular-nums">{formatNumber(v)}</span>,
        },
        {
            title: '最近事件时间',
            dataIndex: 'lastEventAt',
            key: 'lastEventAt',
            width: 170,
            render: (v?: string | null) => <span className="text-ds-tiny text-ds-text-muted">{formatDateTime(v ?? undefined)}</span>,
        },
        {
            title: '创建人',
            dataIndex: 'createdByName',
            key: 'createdByName',
            width: 100,
            render: (v?: string) => v ?? '—',
        },
        {
            title: '创建时间',
            dataIndex: 'createdAt',
            key: 'createdAt',
            width: 160,
            render: (v?: string) => <span className="text-ds-tiny text-ds-text-muted">{formatDateTime(v)}</span>,
        },
        {
            title: '修改人',
            dataIndex: 'updatedByName',
            key: 'updatedByName',
            width: 100,
            render: (v?: string) => v ?? '—',
        },
        {
            title: '修改时间',
            dataIndex: 'updatedAt',
            key: 'updatedAt',
            width: 160,
            render: (v?: string) => <span className="text-ds-tiny text-ds-text-muted">{formatDateTime(v)}</span>,
        },
    ];

    const p95Value = stats && Number(stats.p95Ms) > 0 ? formatDuration(stats.p95Ms) : '—';

    return (
        <div className="overflow-auto flex-1 min-h-0">
            {/* 非运行中提示 */}
            {!running && (
                <div className="mb-ds-4 flex items-start gap-ds-2 border border-ds-warning/30 bg-ds-warning/5 rounded-ds-md p-ds-3">
                    <HiOutlineExclamationTriangle size={14} className="text-ds-warning flex-shrink-0 mt-0.5"/>
                    <p className="text-ds-small text-ds-text-secondary leading-relaxed">
                        管道当前未运行，暂不可建立订阅连接（订阅校验要求管道 RUNNING）。以下订阅文档仍可提前查阅，
                        启动管道后即可按示例接入。
                    </p>
                </div>
            )}

            {/* 连接监控 */}
            <Section title="连接监控">
                <div className="grid grid-cols-4 gap-ds-3 mb-ds-4">
                    <KpiCard label="在线订阅连接"
                             value={loading && !stats ? '—' : String(stats?.onlineConnections ?? 0)}
                             unit="连接"
                             sub="实时"/>
                    <KpiCard label="今日变更事件"
                             value={loading && !stats ? '—' : formatNumber(stats?.todayEvents)}
                             unit="次"
                             sub="有订阅者时推送"/>
                    <KpiCard label="端到端延迟 P95"
                             value={loading && !stats ? '—' : p95Value}
                             sub="CDC 事件 → 推送"/>
                    <KpiCard label="推送失败"
                             value={loading && !stats ? '—' : formatNumber(stats?.failedSends)}
                             unit="次"
                             sub="fan-out 发送失败"
                             danger={Number(stats?.failedSends ?? 0) > 0}/>
                </div>
                <Table
                    rowKey="keyId"
                    columns={columns}
                    dataSource={stats?.subscribers ?? []}
                    loading={loading && !stats}
                    size="small"
                    pagination={false}
                    locale={{emptyText: '暂无订阅方 Key（可在「API Key 管理」将 Key 绑定本管道）'}}
                />
            </Section>

            {/* 订阅文档 */}
            <Section title="订阅地址">
                <CodeBlock code={wsUrl} onCopy={() => copy(wsUrl, '订阅地址')}/>
                <p className="text-ds-tiny text-ds-text-muted mt-1">
                    认证方式：WebSocket 握手请求头携带 <span className="font-mono">X-API-Key</span>（Key 需绑定本管道即获得订阅权，见「API Key 管理」）。
                </p>
            </Section>

            <Section title="订阅消息">
                <CodeBlock code={subscribeMsg} onCopy={() => copy(subscribeMsg, '订阅消息')}/>
            </Section>

            <Section title="变更事件示例（行级，端到端延迟 &lt; 10s）">
                <CodeBlock code={eventSample} onCopy={() => copy(eventSample, '变更事件示例')}/>
            </Section>

            <Section title="JavaScript 订阅示例">
                <CodeBlock code={jsSample} onCopy={() => copy(jsSample, '订阅示例')}/>
            </Section>

            <Section title="订阅说明">
                <Row label="连接心跳">服务端 60s ping/pong（客户端发 ping，服务端自动 pong）</Row>
                <Row label="断线处理">业务端负责重连；重连后从最新开始接收（无历史重放，仅增量）</Row>
                <Row label="推送延迟">变更事件端到端 P95 &lt; 10s（Flink CDC → Kafka → WebSocket）</Row>
                <Row label="机密表">目标表为机密级的管道不可订阅（订阅时返回拒绝）</Row>
            </Section>
        </div>
    );
}
