// DsRangePicker：时间范围选择器（antd RangePicker + dayjs 封装）
// 统一执行历史等页面的时间筛选，替换原生 <input type="datetime-local">。
// 值采用与后端一致的 "YYYY-MM-DDTHH:mm:ss" 字符串（from/to 两个字段），
// 组件内部用 dayjs 与 antd RangePicker 互转，onChange 回传格式化字符串。
//
// 注意：dayjs 中文 locale 必须显式 import 后传入 dayjs.locale()，不能仅用
// `import 'dayjs/locale/zh-cn'` 副作用 import——dayjs locale 文件是 UMD，在
// vite 浏览器构建下走 globalThis.dayjs 分支，但 vite 不会把 dayjs 挂到全局，
// 导致月份永远是英文（Jan/Feb/Mar）。explicit import + dayjs.locale(zhCN)
// 通过 ESM/CJS 互操作直接拿 locale 对象，绕过 UMD 全局依赖。
//
// antd v6 的月份面板本地化：vite 把 antd 依赖的 dayjs 单独打进 vendor-antd chunk，
// 与主入口的 dayjs 是两个独立模块实例。即便在主入口 dayjs.locale('zh-cn')，
// antd RangePicker 内部用的 dayjs 实例仍是英文（monthShortMonths 走 luxon/dayjs
// 都不可靠）。这里直接给 RangePicker 显式传带 shortMonths 的 locale prop，
// 让 antd Panel 直接用我们提供的月份数组，不走 dayjs localeData().monthsShort()。
import dayjs, {Dayjs} from 'dayjs';
import zhCNLocale from 'dayjs/locale/zh-cn';
import {DatePicker} from 'antd';
import datePickerZhCN from 'antd/es/date-picker/locale/zh_CN';
import {formatDateTimeLocalInput} from '@/utils/format';

dayjs.locale(zhCNLocale);

const {RangePicker} = DatePicker;

// 给 antd date-picker/locale/zh_CN 显式补中文月份短名（"1月"~"12月"）和
// 星期短名（"日"~"六"）。antd 面板月份走 locale.shortMonths、星期走
// locale.shortWeekDays，缺省时才回退到 antd 内部 dayjs（独立实例，英文）。
// 静态构造在模块加载时执行，DsRangePicker 每次渲染直接复用，避免 useMemo。
const ZH_CN_SHORT_MONTHS = ['1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月', '9月', '10月', '11月', '12月'];
const ZH_CN_SHORT_WEEK_DAYS = ['日', '一', '二', '三', '四', '五', '六'];
const dsRangePickerLocale = {
    ...datePickerZhCN,
    lang: {
        ...datePickerZhCN.lang,
        shortMonths: ZH_CN_SHORT_MONTHS,
        shortWeekDays: ZH_CN_SHORT_WEEK_DAYS,
    },
};

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
            locale={dsRangePickerLocale}
            style={{width: width ?? 320}}
        />
    );
}
