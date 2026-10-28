package io.github.rurulalanana.dahua;

import io.github.rurulalanana.deviceaccess.DeviceRegisterTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大华「设备主动注册」会话表的内存实现。
 *
 * <h3>为什么是内存，且必须如此</h3>
 * SERVER 模式下设备换网、重新拨号后会连到不同的地址，注册记录随之失效。
 * 把它落库会带来三个麻烦，且没有任何收益：
 * <ol>
 *   <li>心跳几十秒一次，写库成热点；</li>
 *   <li>进程重启后地址already 过期，读出来反而会去连一个错地址；</li>
 *   <li>设备重连时会立刻重新上报，本来就不需要「记住」。</li>
 * </ol>
 * 因此这里只做进程内缓存，重启后靠设备下一次注册恢复。
 *
 * <h3>为什么要 pruneExpired</h3>
 * 设备断电/断网时不会发「我走了」，只能靠超时判定。
 * 不清的话 {@link #list()} 会一直显示一堆已离线的设备，
 * 而平台会拿这些僵尸地址去连、每次都要等满超时。
 */
public class DahuaRegisterTable implements DeviceRegisterTable {

    private static final Logger log = LoggerFactory.getLogger(DahuaRegisterTable.class);

    /** 超过这个时间没有任何注册/心跳就判定失效（毫秒）。 */
    private final long expireMs;
    private final Map<String, RegisteredDevice> bySerial = new ConcurrentHashMap<>();

    /**
     * @param expireMs 失效阈值。大华设备的注册心跳间隔通常是 30~60 秒，
     *                 取 3 倍留出网络抖动与设备卡顿的余量
     */
    public DahuaRegisterTable(long expireMs) {
        this.expireMs = expireMs <= 0 ? 180_000L : expireMs;
    }

    public DahuaRegisterTable() {
        this(180_000L);
    }

    @Override
    public void register(String serial, String ip, int port, String extra) {
        if (serial == null || serial.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        bySerial.compute(serial, (k, old) -> {
            long first = old == null ? now : old.registeredAt();
            boolean moved = old != null && (!eq(old.ip(), ip) || old.port() != port);
            if (old == null) {
                log.info("大华设备主动注册: sn={} ip={}:{}", serial, ip, port);
            } else if (moved) {
                // 地址变了必须显式记一笔：这类问题在现场最难查（设备在线但连不上）
                log.info("大华设备注册地址变更: sn={} {}:{} -> {}:{}", serial,
                        old.ip(), old.port(), ip, port);
            }
            return new RegisteredDevice(serial, ip, port, first, now, extra);
        });
    }

    @Override
    public RegisteredDevice lookup(String serial) {
        if (serial == null || serial.isBlank()) {
            return null;
        }
        RegisteredDevice d = bySerial.get(serial.trim());
        if (d == null) {
            return null;
        }
        if (System.currentTimeMillis() - d.lastSeenAt() > expireMs) {
            // 已过期就不返回：返回一个过期的地址只会让调用方去连死地址
            bySerial.remove(serial.trim(), d);
            log.info("大华设备注册已过期并移除: sn={} 最后心跳 {} 秒前",
                    serial, (System.currentTimeMillis() - d.lastSeenAt()) / 1000);
            return null;
        }
        return d;
    }

    @Override
    public List<RegisteredDevice> list() {
        pruneExpired();
        return new ArrayList<>(bySerial.values());
    }

    @Override
    public void unregister(String serial) {
        if (serial == null) {
            return;
        }
        if (bySerial.remove(serial.trim()) != null) {
            log.info("大华设备已注销注册: sn={}", serial);
        }
    }

    @Override
    public int pruneExpired() {
        long now = System.currentTimeMillis();
        int[] removed = {0};
        bySerial.entrySet().removeIf(e -> {
            boolean stale = now - e.getValue().lastSeenAt() > expireMs;
            if (stale) {
                removed[0]++;
                log.info("清理过期的大华注册记录: sn={}（{} 秒无心跳）",
                        e.getKey(), (now - e.getValue().lastSeenAt()) / 1000);
            }
            return stale;
        });
        return removed[0];
    }

    /** 当前记录数（诊断用）。 */
    public int size() {
        return bySerial.size();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
