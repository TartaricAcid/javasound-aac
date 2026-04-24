package net.sourceforge.jaad.test;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.File;

public class TSPlayerExample {
    public static void main(String[] args) throws Exception {
        File tsFile = new File("data.ts");

        AudioInputStream audioIn = AudioSystem.getAudioInputStream(tsFile);

        Clip clip = AudioSystem.getClip();
        clip.open(audioIn);
        clip.start();

        // 保持主线程存活等待播放完毕
        Thread.sleep(clip.getMicrosecondLength() / 1000);
    }
}