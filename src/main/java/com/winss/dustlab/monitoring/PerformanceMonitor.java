package com.winss.dustlab.monitoring;

import com.winss.dustlab.DustLab;
import com.winss.dustlab.managers.ParticleModelManager;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Simple process performance monitor that samples CPU and heap usage periodically
 * so commands like /dustlab stats can display rolling averages without spawning
 * expensive OS calls on the main thread.
 */
public class PerformanceMonitor {

    private static final long SAMPLE_PERIOD_TICKS = 20L; // 1 second
    private static final long MAX_WINDOW_MILLIS = Duration.ofMinutes(5).toMillis();

    private final DustLab plugin;
    private final Deque<MetricSample> samples = new ConcurrentLinkedDeque<>();
    private final Runtime runtime = Runtime.getRuntime();
    private final com.sun.management.OperatingSystemMXBean osBean;

    private BukkitTask task;

    public PerformanceMonitor(DustLab plugin) {
        this.plugin = plugin;
        this.osBean = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
    }

    public synchronized void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::collectSample, SAMPLE_PERIOD_TICKS, SAMPLE_PERIOD_TICKS);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        samples.clear();
    }

    public PerformanceSnapshot snapshot() {
        WindowAverages tenSeconds = computeWindow(Duration.ofSeconds(10).toMillis());
        WindowAverages oneMinute = computeWindow(Duration.ofMinutes(1).toMillis());
        WindowAverages fiveMinutes = computeWindow(Duration.ofMinutes(5).toMillis());
        return new PerformanceSnapshot(tenSeconds, oneMinute, fiveMinutes);
    }

    private void collectSample() {
        long now = System.currentTimeMillis();
        double cpuPercent = sampleCpu();
        double ramMb = sampleHeapUsageMb();

        samples.addLast(new MetricSample(now, cpuPercent, ramMb));
        trimOldSamples(now);
    }

    private double sampleCpu() {
        if (osBean == null) {
            return -1.0D;
        }
        double load = osBean.getProcessCpuLoad();
        if (Double.isNaN(load) || load < 0) {
            return -1.0D;
        }
        return load * 100.0D;
    }

    private double sampleHeapUsageMb() {
        ParticleModelManager manager = plugin.getParticleModelManager();
        if (manager != null) {
            ParticleModelManager.MemoryUsageReport report = manager.estimateMemoryUsage();
            return report.totalBytes() / (1024.0D * 1024.0D);
        }
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();
        return usedBytes / (1024.0D * 1024.0D);
    }

    private void trimOldSamples(long now) {
        long cutoff = now - MAX_WINDOW_MILLIS;
        while (true) {
            MetricSample first = samples.peekFirst();
            if (first == null) {
                return;
            }
            if (first.timestamp < cutoff) {
                samples.pollFirst();
            } else {
                return;
            }
        }
    }

    private WindowAverages computeWindow(long windowMillis) {
        long now = System.currentTimeMillis();
        long cutoff = Math.max(0L, now - windowMillis);

        double cpuSum = 0.0D;
        int cpuCount = 0;
        double ramSum = 0.0D;
        int ramCount = 0;

        Iterator<MetricSample> iterator = samples.descendingIterator();
        while (iterator.hasNext()) {
            MetricSample sample = iterator.next();
            if (sample.timestamp < cutoff) {
                break;
            }
            if (sample.cpuPercent >= 0) {
                cpuSum += sample.cpuPercent;
                cpuCount++;
            }
            ramSum += sample.ramMb;
            ramCount++;
        }

        double cpuAverage = cpuCount > 0 ? (cpuSum / cpuCount) : -1.0D;
        double ramAverage = ramCount > 0 ? (ramSum / ramCount) : 0.0D;

        return new WindowAverages(cpuAverage, ramAverage, ramCount);
    }

    private record MetricSample(long timestamp, double cpuPercent, double ramMb) {
    }

    public record WindowAverages(double cpuPercent, double ramMb, int samples) {
        public boolean hasCpuData() {
            return cpuPercent >= 0;
        }
    }

    public record PerformanceSnapshot(WindowAverages tenSeconds, WindowAverages oneMinute, WindowAverages fiveMinutes) {
        public Collection<WindowAverages> all() {
            Collection<WindowAverages> list = new ArrayList<>(3);
            list.add(tenSeconds);
            list.add(oneMinute);
            list.add(fiveMinutes);
            return list;
        }
    }
}
