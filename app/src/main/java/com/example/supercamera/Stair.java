package com.example.supercamera;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import timber.log.Timber;

public class Stair {
    private int maxStair = 8;
    private int max1Stair = 4;
    private int totalStair = 12;
    private int hurtTime = 0;
    private int hurtTimeMaxMax = 2;
    private int hurtTimeMax = 1;
    private int totalPosb = 0;
    private List<Integer[]> posbList = new ArrayList<>();
    private static final String OUTPUT_FILE = "/storage/emulated/0/Download/stair_paths.txt"; // 输出文件路径
    private BufferedWriter writer;
    public Stair() {
        climbStair(0, 0, maxStair, new ArrayList<Integer>());
        Timber.i("totalPosb:%d", totalPosb);

        // 初始化时清空文件（可选，若需要追加则移除 "false" 参数）
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE, false))) {
            // 写入文件头（可选）
            writer.write("Valid paths for climbing stairs:\n");
            writer.write("Total possible paths: " + totalPosb + "\n");
            for(Integer[] posb : posbList) {
                writer.write(Arrays.stream(posb).toArray() + "\n");
            }
        } catch (IOException e) {
            Timber.e(e, "Failed to initialize output file");
        }
    }
    private void climbStair(int current, int hurtCount, int maxStair, List<Integer> eachStep) {
        if (current >= totalStair) {
            if (current == totalStair) {
                totalPosb++;
                posbList.add(eachStep.toArray(new Integer[eachStep.size()]));
            }
            return;
        }
        if (hurtCount >= hurtTimeMaxMax) return;

        for (int step = 1; step <= maxStair; step++) {
            boolean isHurt = (step == maxStair);
            int newHurt = isHurt ? hurtCount + 1 : hurtCount;
            if (newHurt > hurtTimeMaxMax) continue;

            eachStep.add(step);
            int newMaxStair = (newHurt == hurtTimeMax) ? max1Stair : maxStair;
            climbStair(current + step, newHurt, newMaxStair, eachStep);
            eachStep.remove(eachStep.size() - 1);
        }
    }

    private void writeStepToFile(List<Integer> steps) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE, true))) {
            writer.write(steps.toString() + "\n"); // 格式示例: [1, 2, 3]
        } catch (IOException e) {
            Timber.e(e, "Failed to write steps to file");
        }
    }
}
