// Sprint 7 F1：资产详情页「字段列表」页签（首次激活时挂载拉取）
import {useEffect, useState} from 'react';
import {Table} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {listMetadataColumns} from '../../../api/metadata';
import DsTableEmpty from '../../../components/DsTableEmpty';
import {COL} from '../../../constants/table';
import type {MetadataColumn} from '../../../types/metadata';

const columns: ColumnsType<MetadataColumn> = [
    {
        title: '字段名',
        dataIndex: 'columnName',
        width: COL.NAME,
        ellipsis: true,
        render: (v?: string) => (
            <span className="text-ds-small text-ds-accent font-mono" title={v}>{v || '—'}</span>
        ),
    },
    {
        title: '数据类型',
        dataIndex: 'dataType',
        width: 140,
        ellipsis: true,
        render: (v?: string) => (
            <span className="text-ds-small text-ds-text-secondary font-mono">{v || '—'}</span>
        ),
    },
    {
        title: '中文注释',
        dataIndex: 'columnComment',
        width: COL.NAME,
        ellipsis: true,
        render: (v?: string, r?: MetadataColumn) => (
            <span className="text-ds-small text-ds-text-secondary" title={v || r?.manualComment || ''}>
                {v || r?.manualComment || '—'}
            </span>
        ),
    },
    {
        title: '是否可空',
        dataIndex: 'nullable',
        width: COL.STATUS,
        render: (v?: boolean) => (
            <span className="text-ds-small text-ds-text-secondary">
                {v === undefined || v === null ? '—' : v ? '是' : '否'}
            </span>
        ),
    },
    {
        title: '备注',
        dataIndex: 'remark',
        ellipsis: true,
        render: (v?: string) => (
            <span className="text-ds-small text-ds-text-secondary" title={v}>{v || '—'}</span>
        ),
    },
];

export default function ColumnsTab({tableId}: { tableId: string }) {
    const [list, setList] = useState<MetadataColumn[]>([]);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        listMetadataColumns(tableId)
            .then(res => {
                if (!cancelled) setList(res.data ?? []);
            })
            .catch(() => {
                if (!cancelled) setList([]);
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [tableId]);

    return (
        <Table
            rowKey={(r) => r.id}
            columns={columns}
            dataSource={list}
            loading={loading}
            pagination={false}
            className="prototype-table prototype-table-flush"
            locale={{emptyText: <DsTableEmpty description="暂无字段信息"/>}}
        />
    );
}
