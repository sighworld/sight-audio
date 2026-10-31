package com.sight.audio.wasapi;

/**
 * 一个音频会话 = 一个正在使用播放设备的进程。
 *
 * @param pid          进程 ID
 * @param name         显示名（一般是 exe 文件名）
 * @param active       Windows 认为它当前正在出声
 * @param systemSounds 是否系统提示音会话（这种没有独立进程，一律排除）
 */
public record AudioSession(int pid, String name, boolean active, boolean systemSounds) {
}
