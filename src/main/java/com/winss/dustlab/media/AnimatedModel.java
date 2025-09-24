package com.winss.dustlab.media;

import com.winss.dustlab.models.ParticleModel;
import com.winss.dustlab.models.ParticleData;
import java.util.List;
@SuppressWarnings("unused")
public class AnimatedModel extends ParticleModel {
    private final List<FrameData> frames;
    private final boolean looping;
    private final int totalFrames;
    private final String sourceUrl;
    private final long createdTime;
    private final int blockWidth;
    private final int blockHeight;
    private final int maxParticleCount;
    private final boolean tickAligned; // true if all frame delays are >=50ms and divisible by 50
    
    public AnimatedModel(String name, List<FrameData> frames, boolean looping, String sourceUrl, 
                        int blockWidth, int blockHeight, int maxParticleCount) {
        super();
        this.setName(name);
        this.frames = frames;
        this.looping = looping;
        this.totalFrames = frames.size();
        this.sourceUrl = sourceUrl;
        this.createdTime = System.currentTimeMillis();
        this.blockWidth = blockWidth;
        this.blockHeight = blockHeight;
        this.maxParticleCount = maxParticleCount;
        this.tickAligned = determineTickAligned(frames);
        
        // Set the base duration and particles from the first frame for compatibility
        if (!frames.isEmpty()) {
            this.setDuration(calculateTotalDuration());
            this.setParticles(frames.get(0).getParticles());
        }
    }
    
    public List<FrameData> getFrames() {
        return frames;
    }
    
    public FrameData getFrame(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= frames.size()) {
            return null;
        }
        return frames.get(frameIndex);
    }
    
    public int getTotalFrames() {
        return totalFrames;
    }
    
    public boolean isLooping() {
        return looping;
    }
    
    public String getSourceUrl() {
        return sourceUrl;
    }
    
    public long getCreatedTime() {
        return createdTime;
    }
    
    public int getBlockWidth() {
        return blockWidth;
    }
    
    public int getBlockHeight() {
        return blockHeight;
    }
    
    public int getMaxParticleCount() {
        return maxParticleCount;
    }
    
    public boolean isAnimated() {
        return true;
    }
    
    public boolean isTickAligned() {
        return tickAligned;
    }
    
    private boolean determineTickAligned(List<FrameData> frames) {
        if (frames == null || frames.isEmpty()) return false;
        for (FrameData f : frames) {
            int ms = f.getDelayMs();
            if (ms < 50 || (ms % 50) != 0) {
                return false;
            }
        }
        return true;
    }
    

    private int calculateTotalDuration() {
        if (tickAligned) {
            long totalTicks = frames.stream()
                .mapToLong(f -> Math.max(1, f.getDelayMs() / 50))
                .sum();
            return Math.max(1, (int) totalTicks);
        } else {
            long totalMs = frames.stream()
                .mapToLong(FrameData::getDelayMs)
                .sum();
            // Use rounding to better match real playback time (50 ms per tick)
            return Math.max(1, (int) Math.round(totalMs / 50.0));
        }
    }
    


    public FrameData getFrameAtTick(long currentTick) {
        if (frames.isEmpty()) return null;
        if (frames.size() == 1) return frames.get(0);
        
        long totalDurationTicks;
        if (tickAligned) {
            totalDurationTicks = frames.stream()
                .mapToLong(frame -> Math.max(1, frame.getDelayMs() / 50))
                .sum();
        } else {
            totalDurationTicks = frames.stream()
                .mapToLong(frame -> Math.max(1, Math.round(frame.getDelayMs() / 50.0)))
                .sum();
        }
        
        if (totalDurationTicks <= 0) {
            return frames.get(0);
        }
        
        long currentAnimationTick = currentTick;
        if (looping) {
            currentAnimationTick = currentTick % totalDurationTicks;
        }
        
        long accumulatedTicks = 0;
        for (FrameData frame : frames) {
            long frameTickDuration = tickAligned
                ? Math.max(1, frame.getDelayMs() / 50)
                : Math.max(1, Math.round(frame.getDelayMs() / 50.0));
            long frameEndTick = accumulatedTicks + frameTickDuration;
            
            if (currentAnimationTick >= accumulatedTicks && currentAnimationTick < frameEndTick) {
                return frame;
            }
            
            accumulatedTicks = frameEndTick;
        }
        
        return looping ? frames.get(0) : frames.get(frames.size() - 1);
    }
    

    public FrameData getFrameAtTime(long elapsedMs) {
        if (frames.isEmpty()) return null;
        if (frames.size() == 1) return frames.get(0);
        
        long totalDurationMs = frames.stream()
            .mapToLong(FrameData::getDelayMs)
            .sum();
        
        if (totalDurationMs <= 0) {
            return frames.get(0);
        }
        
        long currentAnimationTime = elapsedMs;
        if (looping) {
            currentAnimationTime = elapsedMs % totalDurationMs;
        }
        
        long accumulatedTime = 0;
        for (FrameData frame : frames) {
            long frameEndTime = accumulatedTime + frame.getDelayMs();
            
            if (currentAnimationTime >= accumulatedTime && currentAnimationTime < frameEndTime) {
                return frame;
            }
            
            accumulatedTime = frameEndTime;
        }
        
        return looping ? frames.get(0) : frames.get(frames.size() - 1);
    }
    
    public int getTotalParticleCount() {
        return frames.stream().mapToInt(FrameData::getParticleCount).sum();
    }
}
