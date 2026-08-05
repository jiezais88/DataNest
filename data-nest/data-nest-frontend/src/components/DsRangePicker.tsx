// DsRangePicker：时间范围选择器（antd RangePicker + dayjs 封装）
// 统一执行历史等页面的时间筛选，替换原生 <input type="datetime-local">。
// 值采用与后端一致的 "YYYY-MM-DDTHH:mm:ss" 字符串（from/to 两个字段），
// 组件内部用 dayjs 与 antd RangePicker 互转，onChange 回传格式化字符串。
import dayjs, {Dayjs} from 'dayjs';
import 'dayjs/locale/zh-cn';
import {DatePicker} from 'antd';
import {formatDateTimeLocalInput} from '../utils/format';

dayjs.locale('zh-cn');

const {RangePicker} = DatePicker;

interface DsRangePickerProps {
    /** 开始时间（"YYYY-MM-DDTHH:mm:ss"），可空 */
    from?: string;
    /** 结束时间（"YYYY-MM-DDTHH:mm:ss"），可空 */
    to?: string;
    /** 时间范围变化回调（dayjs → 字符串） */
    onChange: (from: string, to: string) => void;
    /** 是否允许清空（清空后 from/to 回 ''） */
    allowClear?: boolean;
    /** 自定义宽度（默认 320px） */
    width?: number | string;
}

/** 字符串 → dayjs，非法/空返回 null */
function toDayjs(v?: string): Dayjs | null {
    if (!v) return null;
    const d = dayjs(v);
    return d.isValid() ? d : null;
}

export default function DsRangePicker({from, to, onChange, allowClear = true, width}: DsRangePickerProps) {
    const value: [Dayjs | null, Dayjs | null] | null =
        from || to ? [toDayjs(from), toDayjs(to)] : null;

    const handleChange = (dates: [Dayjs | null, Dayjs | null] | null) => {
        if (!dates) {
            onChange('', '');
            return;
        }
        const [f, t] = dates;
        onChange(f ? formatDateTimeLocalInput(f.toDate()) : '', t ? formatDateTimeLocalInput(t.toDate()) : '');
    };

    return (
        <RangePicker
            showTime
            format="YYYY-MM-DD HH:mm"
            value={value}
            onChange={handleChange}
            allowClear={allowClear}
            placeholder={['开始时间', '结束时间']}
            style={{width: width ?? 320}}
        />
    );
}
