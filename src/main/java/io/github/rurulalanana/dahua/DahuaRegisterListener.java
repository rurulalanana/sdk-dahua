package io.github.rurulalanana.dahua;

import com.netsdk.lib.NetSDKLib;
import com.sun.jna.Pointer;
import io.github.rurulalanana.deviceaccess.RegisterListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 大华「主动注册」监听服务（服务器模式）。
 *
 * <h3>设备主动注册的完整流程</h3>
 * <pre>
 *   平台                                    设备（NAT 后 / 4G / 无固定 IP）
 *    │ CLIENT_ListenServer(0.0.0.0:9500)      │
 *    │ ◄────── TCP 连上来 + 上报序列号 ────────│  ← 方向是设备→平台
 *    │ 回调 NET_DEV_AUTOREGISTER_RETURN        │
 *    │ 记下 sn / ip / port                     │
 *    │ CLIENT_LoginWithHighLevelSecurity       │
 *    │   emSpecCap=2（主动注册）, pCapParam=sn  │
 *    │ ◄────────── 登录成功 ──────────────────│
 * </pre>
 *
 * <p><b>关键点：注册 ≠ 登录</b>。注册只解决「知道设备在哪」，
 * 后续所有操作仍然要登录拿句柄 —— 而登录时必须以「主动注册方式」
 * （{@code emSpecCap=2}）并带上序列号，否则设备会拒绝。
 * 很多接入失败就是因为只开了监听、没做这一步。
 *
 * <h3>为什么回调里不能直接登录</h3>
 * 回调跑在 SDK 的原生线程上，登录是阻塞操作（网络往返 + 可能的超时）。
 * 在里面同步登录会阻塞整个监听线程，后续设备注册全部排队 ——
 * 设备多时表现为「前几台能连、后面的都超时」。
 * 因此这里只做「记表」，登录交给调用方按需触发。
 */
public class DahuaRegisterListener implements RegisterListener {

    private static final Logger log = LoggerFactory.getLogger(DahuaRegisterListener.class);

    /** 国标设备编码/序列号基本都是 ASCII；用 GBK 兜底是为了兼容少数固件的原始字节。 */
    private static final Charset SN_CHARSET = Charset.forName("GBK");

    private final DahuaRegisterTable table;
    /** 回调对象必须由 Java 侧强引用：JNA 只弱引用，被 GC 后原生回调会崩。 */
    private final NetSDKLib.fServiceCallBack callback = new ServiceCallback();
    private final AtomicLong registered = new AtomicLong();

    private volatile NetSDKLib.LLong handle;
    private volatile int boundPort;

    public DahuaRegisterListener(DahuaRegisterTable table) {
        this.table = table;
    }

    @Override
    public boolean running() {
        NetSDKLib.LLong h = handle;
        return h != null && h.longValue() != 0;
    }

    @Override
    public int port() {
        return boundPort;
    }

    @Override
    public long registeredCount() {
        return registered.get();
    }

    @Override
    public synchronized void start(String bindIp, int port) {
        if (running()) {
            log.debug("大华注册监听已在运行，端口 {}", boundPort);
            return;
        }
        String ip = (bindIp == null || bindIp.isBlank()) ? "0.0.0.0" : bindIp.trim();
        // 第三个参数是「通道超时」（毫秒），不是连接超时；SDK 示例与二次开发手册都用 1000
        NetSDKLib.LLong h = NetSDKLib.NETSDK_INSTANCE.CLIENT_ListenServer(ip, port, 1000, callback, null);
        if (h == null || h.longValue() == 0) {
            // 失败必须抛，不能只打日志：端口占用是部署期最常见的错误，
            // 静默不工作会让运维以为「设备有问题」而白查很久
            int err = NetSDKLib.NETSDK_INSTANCE.CLIENT_GetLastError();
            throw new IllegalStateException(String.format(
                    "大华主动注册监听启动失败: %s:%d，错误码 0x%08X（端口被占用或地址不可绑定）",
                    ip, port, err));
        }
        handle = h;
        boundPort = port;
        log.info("大华主动注册监听已启动: {}:{}", ip, port);
    }

    @Override
    public synchronized void stop() {
        NetSDKLib.LLong h = handle;
        handle = null;
        if (h == null || h.longValue() == 0) {
            return;
        }
        try {
            NetSDKLib.NETSDK_INSTANCE.CLIENT_StopListenServer(h);
            log.info("大华主动注册监听已停止（原端口 {}）", boundPort);
        } catch (Throwable t) {
            log.warn("停止大华注册监听失败: {}", t.toString());
        }
        boundPort = 0;
    }

    /** 注册回调：只记表，不做任何阻塞操作（登录由调用方触发）。 */
    private final class ServiceCallback implements NetSDKLib.fServiceCallBack {
        @Override
        public int invoke(NetSDKLib.LLong lHandle, String pIp, int wPort,
                          int lCommand, Pointer pParam, int dwParamLen,
                          Pointer dwUserData) {
            try {
                String sn = readSerial(pParam, dwParamLen);
                switch (lCommand) {
                    case NetSDKLib.EM_LISTEN_TYPE.NET_DEV_AUTOREGISTER_RETURN:
                    case NetSDKLib.EM_LISTEN_TYPE.NET_DVR_SERIAL_RETURN:
                    case NetSDKLib.EM_LISTEN_TYPE.NET_DEV_NOTIFY_IP_RETURN:
                        if (sn == null || sn.isBlank()) {
                            log.warn("收到大华注册回调但序列号为空: ip={}:{} cmd={}", pIp, wPort, lCommand);
                            return 0;
                        }
                        table.register(sn, pIp, wPort, "cmd=" + lCommand);
                        registered.incrementAndGet();
                        return 0;
                    case NetSDKLib.EM_LISTEN_TYPE.NET_DVR_DISCONNECT:
                        // 验证期间设备断线：清掉记录，避免拿死地址去登录
                        if (sn != null && !sn.isBlank()) {
                            table.unregister(sn);
                        }
                        return 0;
                    default:
                        log.debug("未处理的大华注册回调类型: cmd={} ip={}:{}", lCommand, pIp, wPort);
                        return 0;
                }
            } catch (Throwable t) {
                // 回调里绝不能抛：异常穿过原生栈会直接让进程崩溃
                log.warn("处理大华注册回调失败: {}", t.toString());
                return -1;
            }
        }
    }

    /**
     * 从回调参数里读序列号。
     *
     * <p>SDK 对 {@code NET_DVR_SERIAL_RETURN} 的定义是 {@code char* szDevSerial}，
     * 即一个 C 字符串，长度由 {@code dwParamLen} 给出。这里按长度读取再 trim，
     * <b>不能用 {@code Pointer.getString()}</b> —— 那样会依赖 NUL 结尾，
     * 而部分固件会把长度算进去导致尾部带填充字节。
     */
    private static String readSerial(Pointer p, int len) {
        if (p == null || len <= 0) {
            return null;
        }
        byte[] buf = new byte[len];
        p.read(0, buf, 0, len);
        String s = new String(buf, SN_CHARSET).trim();
        // 顺手去掉可能存在的尾部 NUL
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul).trim() : s;
    }
}
