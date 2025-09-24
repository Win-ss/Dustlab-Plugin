package com.winss.dustlab.media;

import com.winss.dustlab.models.ParticleData;
import java.util.List;

public class FrameData {
    private final List<ParticleData> particles;
    private final int frameIndex;
    private final int delayMs;
    
    public FrameData(List<ParticleData> particles, int frameIndex, int delayMs) {
        this.particles = particles;
        this.frameIndex = frameIndex;
        this.delayMs = delayMs;
    }
    
    public List<ParticleData> getParticles() {
        return particles;
    }
    
    public int getFrameIndex() {
        return frameIndex;
    }
    
    public int getDelayMs() {
        return delayMs;
    }
    
    public int getParticleCount() {
        return particles.size();
    }
}
