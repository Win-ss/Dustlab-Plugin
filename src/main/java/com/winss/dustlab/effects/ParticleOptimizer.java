package com.winss.dustlab.effects;

import com.winss.dustlab.models.ParticleData;
import org.bukkit.Color;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class ParticleOptimizer {
    
    private static final double COLOR_DIFF_THRESHOLD = 0.02;
    // Animation color diff threshold - may be used in future optimizations
    @SuppressWarnings("unused") 
    private static final double ANIMATION_COLOR_DIFF_THRESHOLD = 0.01; 
    
    private final Map<String, Map<String, ParticleState>> particleStates = new ConcurrentHashMap<>();
    

    private static class ParticleState {
        private Color lastColor;
        private long lastUpdateTick;
        private boolean needsUpdate;
        
        public ParticleState(Color color, long tick) {
            this.lastColor = color;
            this.lastUpdateTick = tick;
            this.needsUpdate = true; 
        }
        
        public boolean shouldUpdate(Color newColor, long currentTick, ParticleOptimizer optimizer) {
            if (needsUpdate) {
                needsUpdate = false;
                return true;
            }
            
            if (lastColor == null && newColor != null) {
                return true;
            }
            
            if (lastColor != null && newColor == null) {
                return true;
            }
            
            if (lastColor == null && newColor == null) {
                return false;
            }
            
            double colorDiff = optimizer.calculateColorDifference(lastColor, newColor);
            return colorDiff > COLOR_DIFF_THRESHOLD;
        }
        
        public void update(Color newColor, long currentTick) {
            this.lastColor = newColor;
            this.lastUpdateTick = currentTick;
        }
        
        public long getLastUpdateTick() {
            return lastUpdateTick;
        }
    }
    

    public boolean shouldUpdateParticle(String effectId, ParticleData particle, long currentTick) {
        String positionKey = getPositionKey(particle);
        
        Map<String, ParticleState> effectStates = particleStates.computeIfAbsent(effectId, k -> new ConcurrentHashMap<>());
        
        Color particleColor = getParticleColor(particle);
        ParticleState state = effectStates.get(positionKey);
        
        if (state == null) {
            state = new ParticleState(particleColor, currentTick);
            effectStates.put(positionKey, state);
            return true;
        }
        
        boolean shouldUpdate = state.shouldUpdate(particleColor, currentTick, this);
        if (shouldUpdate) {
            state.update(particleColor, currentTick);
        }
        
        return shouldUpdate;
    }
    

    public void cleanupEffect(String effectId, long currentTick, long maxAge) {
        Map<String, ParticleState> effectStates = particleStates.get(effectId);
        if (effectStates != null) {
            effectStates.entrySet().removeIf(entry -> 
                currentTick - entry.getValue().getLastUpdateTick() > maxAge);
            
            if (effectStates.isEmpty()) {
                particleStates.remove(effectId);
            }
        }
    }
    

    public void removeEffect(String effectId) {
        particleStates.remove(effectId);
    }

    public int getActiveEffectCount() {
        return particleStates.size();
    }

    public int getTotalParticleCount() {
        return particleStates.values().stream()
                .mapToInt(Map::size)
                .sum();
    }

    public void forceUpdateEffect(String effectId) {
        Map<String, ParticleState> effectStates = particleStates.get(effectId);
        if (effectStates != null) {
            effectStates.values().forEach(state -> state.needsUpdate = true);
        }
    }
    

    private String getPositionKey(ParticleData particle) {
        int x = (int) Math.round(particle.getX() * 100); 
        int y = (int) Math.round(particle.getY() * 100);
        int z = (int) Math.round(particle.getZ() * 100);
        return x + "," + y + "," + z;
    }
    

    private Color getParticleColor(ParticleData particle) {
        return particle.getColor();
    }
    

    private double calculateColorDifference(Color color1, Color color2) {
        if (color1 == null || color2 == null) {
            return color1 != color2 ? 1.0 : 0.0;
        }
        
        double rDiff = (color1.getRed() - color2.getRed()) / 255.0;
        double gDiff = (color1.getGreen() - color2.getGreen()) / 255.0;
        double bDiff = (color1.getBlue() - color2.getBlue()) / 255.0;
        
        return Math.sqrt(rDiff * rDiff + gDiff * gDiff + bDiff * bDiff) / Math.sqrt(3.0);
    }
}
