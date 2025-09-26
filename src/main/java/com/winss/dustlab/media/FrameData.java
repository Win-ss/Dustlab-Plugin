package com.winss.dustlab.media;

import com.winss.dustlab.models.ParticleData;
import com.winss.dustlab.packed.PackedParticleArray;

import java.util.Collections;
import java.util.List;

public class FrameData {
    private final List<ParticleData> particles;
    private final PackedParticleArray packedParticles;
    private final int frameIndex;
    private final int delayMs;
    
    public FrameData(List<ParticleData> particles, int frameIndex, int delayMs) {
        this(particles, null, frameIndex, delayMs);
    }

    public FrameData(PackedParticleArray packedParticles, int frameIndex, int delayMs) {
        this(null, packedParticles, frameIndex, delayMs);
    }

    private FrameData(List<ParticleData> particles, PackedParticleArray packedParticles, int frameIndex, int delayMs) {
        if (particles != null) {
            this.particles = particles;
        } else if (packedParticles != null) {
            this.particles = packedParticles.toParticleDataList();
        } else {
            this.particles = Collections.emptyList();
        }
        this.packedParticles = packedParticles;
        this.frameIndex = frameIndex;
        this.delayMs = delayMs;
    }
    
    public List<ParticleData> getParticles() {
        return particles;
    }

    public PackedParticleArray getPackedParticles() {
        return packedParticles;
    }
    
    public int getFrameIndex() {
        return frameIndex;
    }
    
    public int getDelayMs() {
        return delayMs;
    }
    
    public int getParticleCount() {
        if (packedParticles != null) {
            return packedParticles.size();
        }
        return particles.size();
    }
}
