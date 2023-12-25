package com.bairimeng.keeplearning.javacv.multiplexing;

import com.bairimeng.keeplearning.javacv.multiplexing.handle.LiveHandler;
import com.bairimeng.keeplearning.javacv.multiplexing.model.Device;
import com.google.common.eventbus.Subscribe;
import io.netty.channel.ChannelHandlerContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Data
public class TransferToFlv implements Runnable {

    private volatile boolean running = false;

    private FFmpegFrameGrabber grabber;

    private FFmpegFrameRecorder recorder;

    public ByteArrayOutputStream bos = new ByteArrayOutputStream();

    private Device currentDevice;

    private MediaChannel mediaChannel;

    public ConcurrentHashMap<String, ChannelHandlerContext> httpClients = new ConcurrentHashMap<>();

    /**
     * 创建拉流器
     *
     * @return
     */
    protected void createGrabber(String url) throws FFmpegFrameGrabber.Exception {

        grabber = new FFmpegFrameGrabber(url);
        //拉流超时时间(10秒)
        grabber.setOption("stimeout", "10000000");
        grabber.setOption("threads", "1");
        grabber.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
        // 设置缓存大小，提高画质、减少卡顿花屏
        grabber.setOption("buffer_size", "1024000");
        // 读写超时，适用于所有协议的通用读写超时
        grabber.setOption("rw_timeout", "15000000");
        // 探测视频流信息，为空默认5000000微秒
        // grabber.setOption("probesize", "5000000");
        // 解析视频流信息，为空默认5000000微秒
        //grabber.setOption("analyzeduration", "5000000");
        grabber.start();
    }

    /**
     * 创建录制器
     *
     * @return
     */
    protected void createTransterOrRecodeRecorder() throws FFmpegFrameRecorder.Exception {
        recorder = new FFmpegFrameRecorder(bos, grabber.getImageWidth(), grabber.getImageHeight(),
                grabber.getAudioChannels());
        setRecorderParams(recorder);
        recorder.start();
    }

    /**
     * 设置录制器参数
     *
     * @param fFmpegFrameRecorder
     */
    private void setRecorderParams(FFmpegFrameRecorder fFmpegFrameRecorder) {
        fFmpegFrameRecorder.setFormat("flv");
        // 转码
        fFmpegFrameRecorder.setInterleaved(false);
        fFmpegFrameRecorder.setVideoOption("tune", "zerolatency");
        fFmpegFrameRecorder.setVideoOption("preset", "ultrafast");
        fFmpegFrameRecorder.setVideoOption("crf", "23");
        fFmpegFrameRecorder.setVideoOption("threads", "1");
        fFmpegFrameRecorder.setFrameRate(25);// 设置帧率
        fFmpegFrameRecorder.setGopSize(25);// 设置gop,与帧率相同
        //recorder.setVideoBitrate(500 * 1000);// 码率500kb/s
        fFmpegFrameRecorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        fFmpegFrameRecorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
        fFmpegFrameRecorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
        fFmpegFrameRecorder.setOption("keyint_min", "25");  //gop最小间隔
        fFmpegFrameRecorder.setTrellis(1);
        fFmpegFrameRecorder.setMaxDelay(0);// 设置延迟
    }

    /**
     * 获取flv格式header数据
     *
     * @return
     * @throws FFmpegFrameRecorder.Exception
     */
    public byte[] getFlvHeader() throws FFmpegFrameRecorder.Exception {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        FFmpegFrameRecorder fFmpegFrameRecorder = new FFmpegFrameRecorder(byteArrayOutputStream, grabber.getImageWidth(), grabber.getImageHeight(),
                grabber.getAudioChannels());
        setRecorderParams(fFmpegFrameRecorder);
        fFmpegFrameRecorder.start();
        return byteArrayOutputStream.toByteArray();
    }


    /**
     * 将视频源转换为flv
     */
    protected void transferToFlv() {
        //创建拉流器
        try {
            createGrabber(currentDevice.getRtmpUrl());
            //创建录制器
            createTransterOrRecodeRecorder();

            grabber.flush();
            running = true;
            // 时间戳计算
            long startTime = 0;
            long lastTime = System.currentTimeMillis();
            while (running) {
                // 转码
                Frame frame = grabber.grab();
                if (frame != null && frame.image != null) {
                    lastTime = System.currentTimeMillis();
                    recorder.setTimestamp((1000 * (System.currentTimeMillis() - startTime)));
                    recorder.record(frame);
                    if (bos.size() > 0) {
                        byte[] b = bos.toByteArray();
                        bos.reset();
                        sendFrameData(b);
                        continue;
                    }
                }
                //10秒内读不到视频帧，则关闭连接
                if ((System.currentTimeMillis() / 1000 - lastTime / 1000) > 10) {
                    System.out.println(currentDevice.getDeviceId() + "：10秒内读不到视频帧");
                    break;
                }
            }
        } catch (FFmpegFrameRecorder.Exception | FrameGrabber.Exception e) {
            throw new RuntimeException(e);
        } finally {
            try {
                recorder.close();
                grabber.close();
                bos.close();
                closeMedia();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }
    }

    /**
     * 发送帧数据
     *
     * @param data
     */
    private void sendFrameData(byte[] data) {
        mediaChannel.sendData(data);
    }


    /**
     * 关闭流媒体
     */
    private void closeMedia() {
        running = false;
        MediaServer.deviceContext.remove(currentDevice.getDeviceId());
        mediaChannel.closeChannel();
    }

    /**
     * 通知关闭推流
     *
     * @param device
     */
    @Subscribe
    public void checkChannel(Device device) {
        if (device.getDeviceId().equals(currentDevice.getDeviceId())) {
            closeMedia();
            System.out.println("关闭推流完成");
        }
    }


    @Override
    public void run() {
        transferToFlv();
    }

}
