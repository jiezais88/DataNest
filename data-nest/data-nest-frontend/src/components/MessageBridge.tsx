import {useEffect} from 'react';
import {App as AntdApp} from 'antd';
import {injectMessageApi} from '../utils/notify';

// 把 antd <App> 上下文的 message 实例注入 utils/notify，消灭静态 message
// "Static function can not consume context like dynamic theme" 警告
export default function MessageBridge() {
    const {message} = AntdApp.useApp();
    useEffect(() => {
        injectMessageApi(message);
    }, [message]);
    return null;
}
