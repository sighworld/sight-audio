package com.sight.audio;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MergeClientSoundEvent;

/**
 * 把采集到的 PCM 混进 SVC 的**麦克风流**。
 *
 * <h2>为什么是 merge（混音）而不是替换</h2>
 * {@code MergeClientSoundEvent#mergeAudio} 是「往这一段麦克风音频里再混一层」，
 * 不是「把麦克风换成别的声音」。所以：
 * <ul>
 *   <li>放歌的时候照常说话，两边声音都会传出去，互不顶掉；</li>
 *   <li>不需要虚拟声卡去冒充麦克风，也不会把玩家的真实麦克风设备顶掉；</li>
 *   <li>SVC 自带的空间衰减、群组、距离逻辑全部自动生效 —— 因为对 SVC 来说，
 *       这跟"你说了话"没有任何区别。</li>
 * </ul>
 *
 * <p>回调节奏是每 20 ms 一次（一帧 960 点）。采集端的抖动缓冲在这里被消费掉，
 * 队列空就什么都不合并（返回 null），不会产生爆音。</p>
 */
public final class VoicechatHook implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "sight-audio";
    }

    @Override
    public void initialize(VoicechatApi api) {
        SightAudioClient.LOG.info("[sight-audio] SVC 客户端 API 就绪，等待音频注入");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MergeClientSoundEvent.class, VoicechatHook::onMerge);
        // 连没连上语音服务器要能显示出来：没连上的话分享是静默无效的
        registration.registerEvent(de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent.class,
                e -> connected = e.isConnected());
    }

    /** 当前是否已经连上语音服务器。 */
    public static boolean isConnected() {
        return connected;
    }

    private static volatile boolean connected;

    private static void onMerge(MergeClientSoundEvent event) {
        if (!CaptureManager.isActive()) {
            return;
        }
        short[] frame = CaptureManager.poll();
        if (frame != null && frame.length == CaptureManager.FRAME_SAMPLES) {
            event.mergeAudio(frame);
        }
    }
}
