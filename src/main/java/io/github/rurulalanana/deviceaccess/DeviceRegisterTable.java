package io.github.rurulalanana.deviceaccess;

import java.util.List;

/**
 * 「设备主动注册」会话表。
 *
 * <h3>为什么需要它</h3>
 * 公网部署时设备在 NAT 后面，没有固定可达地址，平台<b>无法主动连过去</b>。
 * 唯一可行的方向是：平台开一个监听端口，设备起来后主动连上来并上报
 * 自己的序列号与实际地址 —— 这就是**主动注册 / 服务器模式**。
 *
 * <p>于是产生一份「运行时状态」：谁的 SN 对应什么 IP:端口、最后一次
 * 心跳是什么时候。这份状态有三个特性，决定了它<b>不能放进数据库</b>：
 * <ol>
 *   <li><b>随进程消失</b> —— 设备重连时会重新上报，持久化没有意义</li>
 *   <li><b>更新极频繁</b> —— 每台设备几十秒一次心跳，写库会成为热点</li>
 *   <li><b>地址是易失的</b> —— 设备换网、重拨号后地址就变了，
 *       存下来的旧地址反而会误导连接</li>
 * </ol>
 *
 * <p>平台据此判断「这台设备现在是否在线、该往哪连」；
 * 设备行本身（{@code gb_device}）仍然存在数据库里，两者职责不同。
 */
public interface DeviceRegisterTable {

    /**
     * 设备注册/心跳。
     *
     * @param serial 设备序列号（厂商侧唯一 ID，登录时还要用它）
     * @param ip     设备实际来源地址（由监听服务捕获，不是配置值）
     * @param port   设备实际来源端口
     * @param extra  附加信息（可选，厂商自定义），可为 {@code null}
     */
    void register(String serial, String ip, int port, String extra);

    /**
     * 取某序列号的注册信息；{@code null} 表示从未注册或已过期。
     */
    RegisteredDevice lookup(String serial);

    /** 全部当前注册记录（诊断/列表用）。 */
    List<RegisteredDevice> list();

    /** 显式注销（设备主动断开，或平台判定失效）。 */
    void unregister(String serial);

    /**
     * 清理超时未心跳的记录。
     *
     * @return 被清理的条数
     */
    int pruneExpired();

    /**
     * 一条注册记录。
     *
     * @param serial       设备序列号
     * @param ip           实际来源 IP
     * @param port         实际来源端口
     * @param registeredAt 首次注册时间（毫秒）
     * @param lastSeenAt   最后一次心跳时间（毫秒）
     * @param extra        附加信息
     */
    record RegisteredDevice(
            String serial,
            String ip,
            int port,
            long registeredAt,
            long lastSeenAt,
            String extra) {
    }
}
