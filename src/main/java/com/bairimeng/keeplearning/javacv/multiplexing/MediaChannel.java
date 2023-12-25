package com.bairimeng.keeplearning.javacv.multiplexing;

import com.bairimeng.keeplearning.javacv.multiplexing.model.Device;
import com.google.common.eventbus.EventBus;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.bytedeco.javacv.FFmpegFrameRecorder;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @author： Zhou
 * @date： 2023/12/16 10:18
 */
@Data
@AllArgsConstructor
public class MediaChannel {

    private Device currentDevice;

    public ConcurrentHashMap<String, ChannelHandlerContext> httpClients;

    private ScheduledFuture<?> checkFuture;

    private final ScheduledExecutorService scheduler;

    protected EventBus eventBus;

    public MediaChannel(Device currentDevice) {
        this.currentDevice = currentDevice;
        this.httpClients = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.eventBus = new EventBus();
    }

    public void addChannel(ChannelHandlerContext ctx, boolean needSendFlvHeader) throws InterruptedException, FFmpegFrameRecorder.Exception {
        if (ctx.channel().isWritable()) {
            ChannelFuture channelFuture = null;
            if (needSendFlvHeader) {
                //如果当前设备正在有channel播放，则先发送flvheader，再发送视频数据。
                byte[] flvHeader = MediaServer.deviceContext.get(currentDevice.getDeviceId()).getFlvHeader();
                channelFuture = ctx.writeAndFlush(Unpooled.copiedBuffer(flvHeader));
            } else {
                channelFuture = ctx.writeAndFlush(Unpooled.copiedBuffer(new ByteArrayOutputStream().toByteArray()));
            }
            channelFuture.addListener(future -> {
                if (future.isSuccess()) {
                    httpClients.put(ctx.channel().id().toString(), ctx);
                }
            });
            this.checkFuture = scheduler.scheduleAtFixedRate(this::checkChannel, 0, 10, TimeUnit.SECONDS);
            System.out.println(currentDevice.getDeviceId() + "：channel：" + ctx.channel().id() + "创建成功");
        }
        Thread.sleep(50);
    }

    /**
     * 检查是否存在channel
     */
    private void checkChannel() {
        if (httpClients.isEmpty()) {
            System.out.println("通知关闭推流");
            eventBus.post(this.currentDevice);
            this.checkFuture = null;
            scheduler.shutdown();
        }
    }

    /**
     * 关闭通道
     */
    public void closeChannel() {
        for (Map.Entry<String, ChannelHandlerContext> entry : httpClients.entrySet()) {
            entry.getValue().close();
        }
    }

    /**
     * 发送数据
     *
     * @param data
     */
    public void sendData(byte[] data) {
        for (Map.Entry<String, ChannelHandlerContext> entry : httpClients.entrySet()) {
            if (entry.getValue().channel().isWritable()) {
                entry.getValue().writeAndFlush(Unpooled.copiedBuffer(data));
            } else {
                httpClients.remove(entry.getKey());
                System.out.println(currentDevice.getDeviceId() + "：channel：" + entry.getKey() + "已被去除");
            }
        }
    }


}
