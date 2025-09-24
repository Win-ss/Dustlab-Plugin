package com.winss.dustlab.effects;

import com.winss.dustlab.models.ParticleData;
import org.bukkit.Location;




public class ParticleEffects {
    

     

    public static Location applyRotation(ParticleData particle, Location baseLocation, double rotationAngle, boolean rotateX, boolean rotateY, boolean rotateZ) {
        double x = particle.getX();
        double y = particle.getY();
        double z = particle.getZ();
        
        
        if (rotateX) {
            double cosX = Math.cos(rotationAngle);
            double sinX = Math.sin(rotationAngle);
            double newY = y * cosX - z * sinX;
            double newZ = y * sinX + z * cosX;
            y = newY;
            z = newZ;
        }
        
        
        if (rotateY) {
            double cosY = Math.cos(rotationAngle);
            double sinY = Math.sin(rotationAngle);
            double newX = x * cosY - z * sinY;
            double newZ = x * sinY + z * cosY;
            x = newX;
            z = newZ;
        }
        
        
        if (rotateZ) {
            double cosZ = Math.cos(rotationAngle);
            double sinZ = Math.sin(rotationAngle);
            double newX = x * cosZ - y * sinZ;
            double newY = x * sinZ + y * cosZ;
            x = newX;
            y = newY;
        }
        
        return baseLocation.clone().add(x, y, z);
    }
    

    public static Location applyRotation(ParticleData particle, Location baseLocation, double rotationAngle) {
        return applyRotation(particle, baseLocation, rotationAngle, false, true, false);
    }
    

    public static Location applyOscillation(ParticleData particle, Location baseLocation, double oscillationPhase, double amplitude) {
        double oscillationValue = 1.0 + Math.sin(oscillationPhase) * amplitude;
        double x = particle.getX() * oscillationValue;
        double y = particle.getY() * oscillationValue;
        double z = particle.getZ() * oscillationValue;
        
        return baseLocation.clone().add(x, y, z);
    }
    

    public static Location applyPulse(ParticleData particle, Location baseLocation, double pulsePhase, double pulseAmplitude) {
        double pulseValue = 1.0 + Math.sin(pulsePhase * 2) * pulseAmplitude;
        double x = particle.getX() * pulseValue;
        double y = particle.getY() * pulseValue;
        double z = particle.getZ() * pulseValue;
        
        return baseLocation.clone().add(x, y, z);
    }
    

    public static Location applyBounce(ParticleData particle, Location baseLocation, double bouncePhase, double bounceHeight) {
        double bounceOffset = Math.abs(Math.sin(bouncePhase)) * bounceHeight;
        return baseLocation.clone().add(particle.getX(), particle.getY() + bounceOffset, particle.getZ());
    }
    

    public static Location applyFlow(ParticleData particle, Location baseLocation, double flowPhase, double flowSpeed) {
        double flowOffset = Math.sin(flowPhase) * flowSpeed;
        return baseLocation.clone().add(particle.getX() + flowOffset, particle.getY(), particle.getZ());
    }

    public static Location applySwirl(ParticleData particle, Location baseLocation, double swirlPhase, double swirlRadius) {
        double distance = Math.sqrt(particle.getX() * particle.getX() + particle.getZ() * particle.getZ());
        double angle = Math.atan2(particle.getZ(), particle.getX()) + swirlPhase * distance;
        
        double x = distance * Math.cos(angle);
        double z = distance * Math.sin(angle);
        
        return baseLocation.clone().add(x, particle.getY(), z);
    }
    

    public static Location applyWaveEffect(ParticleData particle, Location baseLocation, double wavePhase, double waveAmplitude) {
        double distance = Math.sqrt(particle.getX() * particle.getX() + particle.getZ() * particle.getZ());
        double waveOffset = Math.sin(wavePhase + distance * 0.5) * waveAmplitude;
        
        return baseLocation.clone().add(particle.getX(), particle.getY() + waveOffset, particle.getZ());
    }
    

    public static Location applyOrbit(ParticleData particle, Location baseLocation, double orbitAngle, double orbitRadius) {
        
        Location particlePos = baseLocation.clone().add(particle.getX(), particle.getY(), particle.getZ());
        
        
        double orbitX = Math.cos(orbitAngle) * orbitRadius;
        double orbitZ = Math.sin(orbitAngle) * orbitRadius;
        
        return particlePos.add(orbitX, 0, orbitZ);
    }
    

    public static Location applySpiralEffect(ParticleData particle, Location baseLocation, double spiralPhase, double spiralExpansion) {
        double distance = Math.sqrt(particle.getX() * particle.getX() + particle.getZ() * particle.getZ());
        double angle = Math.atan2(particle.getZ(), particle.getX()) + spiralPhase;
        double newDistance = distance + spiralExpansion;
        
        double newX = Math.cos(angle) * newDistance;
        double newZ = Math.sin(angle) * newDistance;
        
        return baseLocation.clone().add(newX, particle.getY(), newZ);
    }
    

    public static Location applyEffects(ParticleData particle, Location baseLocation, EffectSettings settings, long tickTime) {
        Location result = baseLocation.clone().add(particle.getX(), particle.getY(), particle.getZ());
        
        
        if (settings.rotationSpeed != 0) {
            double rotationAngle = (tickTime * settings.rotationSpeed) % (2 * Math.PI);
            result = applyRotation(particle, baseLocation, rotationAngle, settings.rotateX, settings.rotateY, settings.rotateZ);
        }
        
        
        if (settings.oscillationAmplitude > 0) {
            double oscillationPhase = tickTime * settings.oscillationSpeed;
            result = applyOscillation(particle, result.subtract(baseLocation), oscillationPhase, settings.oscillationAmplitude);
            result = baseLocation.clone().add(result.getX(), result.getY(), result.getZ());
        }
        
        
        if (settings.pulseAmplitude > 0) {
            double pulsePhase = tickTime * settings.pulseSpeed;
            result = applyPulse(particle, baseLocation, pulsePhase, settings.pulseAmplitude);
        }
        
        
        if (settings.bounceHeight > 0) {
            double bouncePhase = tickTime * settings.bounceSpeed;
            result = applyBounce(particle, result, bouncePhase, settings.bounceHeight);
        }
        
        
        if (settings.flowAmplitude > 0) {
            double flowPhase = tickTime * settings.flowSpeed;
            result = applyFlow(particle, result, flowPhase, settings.flowAmplitude);
        }
        
        
        if (settings.swirlRadius > 0) {
            double swirlPhase = tickTime * settings.swirlSpeed;
            result = applySwirl(particle, baseLocation, swirlPhase, settings.swirlRadius);
        }
        
        
        if (settings.waveAmplitude > 0) {
            double wavePhase = tickTime * settings.waveSpeed;
            result = applyWaveEffect(particle, baseLocation, wavePhase, settings.waveAmplitude);
        }
        
        
        if (settings.orbitRadius > 0) {
            double orbitAngle = tickTime * settings.orbitSpeed;
            result = applyOrbit(particle, result, orbitAngle, settings.orbitRadius);
        }
        
        
        if (settings.spiralExpansion != 0) {
            double spiralPhase = tickTime * settings.spiralSpeed;
            result = applySpiralEffect(particle, baseLocation, spiralPhase, settings.spiralExpansion);
        }
        
        return result;
    }
    

    public static class EffectSettings {
        public double rotationSpeed = 0.0;      
        public boolean rotateX = false;         
        public boolean rotateY = true;          
        public boolean rotateZ = false;         
        public double oscillationSpeed = 0.1;   
        public double oscillationAmplitude = 0.0; 
        public double pulseSpeed = 0.2;         
        public double pulseAmplitude = 0.0;     
        public double bounceSpeed = 0.15;       
        public double bounceHeight = 0.0;       
        public double flowSpeed = 0.1;          
        public double flowAmplitude = 0.0;      
        public double swirlSpeed = 0.05;        
        public double swirlRadius = 0.0;        
        public double waveSpeed = 0.1;          
        public double waveAmplitude = 0.0;      
        public double orbitSpeed = 0.05;        
        public double orbitRadius = 0.0;        
        public double spiralSpeed = 0.02;       
        public double spiralExpansion = 0.0;    
        
        public EffectSettings() {}
        
        
        public boolean hasEffects() {
            return rotationSpeed != 0.0 || oscillationAmplitude != 0.0 || pulseAmplitude != 0.0 
                || bounceHeight != 0.0 || flowAmplitude != 0.0 || swirlRadius != 0.0
                || waveAmplitude != 0.0 || orbitRadius != 0.0 || spiralExpansion != 0.0;
        }
        
        
        public static EffectSettings createRotation(double speed, String axes) {
            EffectSettings settings = new EffectSettings();
            settings.rotationSpeed = speed * 0.05; 
            
            
            settings.rotateX = axes.toLowerCase().contains("x");
            settings.rotateY = axes.toLowerCase().contains("y");
            settings.rotateZ = axes.toLowerCase().contains("z");
            
            
            if (!settings.rotateX && !settings.rotateY && !settings.rotateZ) {
                settings.rotateY = true;
            }
            
            return settings;
        }
        
        
        public static EffectSettings createRotation(double speed) {
            return createRotation(speed, "y"); 
        }
        
        public static EffectSettings createOscillation(double speed) {
            EffectSettings settings = new EffectSettings();
            settings.oscillationSpeed = speed * 0.1;
            settings.oscillationAmplitude = 0.3; 
            return settings;
        }
        
        public static EffectSettings createPulse(double speed) {
            EffectSettings settings = new EffectSettings();
            settings.pulseSpeed = speed * 0.2;
            settings.pulseAmplitude = 0.4; 
            return settings;
        }
        
        public static EffectSettings createBounce(double speed) {
            EffectSettings settings = new EffectSettings();
            settings.bounceSpeed = speed * 0.15;
            settings.bounceHeight = 1.5; 
            return settings;
        }
        
        public static EffectSettings createFlow(double speed) {
            EffectSettings settings = new EffectSettings();
            settings.flowSpeed = speed * 0.1;
            settings.flowAmplitude = 2.0; 
            return settings;
        }
        
        public static EffectSettings createSwirl(double speed) {
            EffectSettings settings = new EffectSettings();
            settings.swirlSpeed = speed * 0.05;
            settings.swirlRadius = 1.0; 
            return settings;
        }
        
        public static EffectSettings createWave(double speed) {
            EffectSettings settings = new EffectSettings();
            settings.waveSpeed = speed * 0.1;
            settings.waveAmplitude = 1.0; 
            return settings;
        }
        
        public static EffectSettings createOrbit(double speed) {
            EffectSettings settings = new EffectSettings();
            settings.orbitSpeed = speed * 0.05;
            settings.orbitRadius = 2.0; 
            return settings;
        }
        
        public static EffectSettings createSpiral(double speed) {
            EffectSettings settings = new EffectSettings();
            settings.spiralSpeed = speed * 0.02;
            settings.spiralExpansion = 0.01; 
            return settings;
        }
        
        
        public static EffectSettings rotate(double speed) {
            return createRotation(speed);
        }
        
        public static EffectSettings breathe(double amplitude) {
            EffectSettings settings = new EffectSettings();
            settings.oscillationAmplitude = amplitude;
            return settings;
        }
        
        public static EffectSettings wave(double amplitude) {
            EffectSettings settings = new EffectSettings();
            settings.waveAmplitude = amplitude;
            return settings;
        }
        
        public static EffectSettings orbit(double radius) {
            EffectSettings settings = new EffectSettings();
            settings.orbitRadius = radius;
            return settings;
        }
        
        public static EffectSettings spiral(double expansion) {
            EffectSettings settings = new EffectSettings();
            settings.spiralExpansion = expansion;
            return settings;
        }
        

        public static EffectSettings parseEffect(String effectString) {
            if (effectString == null || effectString.trim().isEmpty()) {
                return null;
            }
            
            String effect = effectString.toLowerCase().trim();
            
            
            if (effect.startsWith("rotate")) {
                if (effect.contains("-")) {
                    String[] parts = effect.split("-", 2);
                    if (parts.length == 2) {
                        String axes = parts[1];
                        return createRotation(1.0, axes);
                    }
                }
                return createRotation(1.0); 
            }

            switch (effect) {
                case "oscillate":
                case "breathe":
                    return createOscillation(1.0);
                case "pulse":
                    return createPulse(1.0);
                case "bounce":
                    return createBounce(1.0);
                case "flow":
                    return createFlow(1.0);
                case "swirl":
                    return createSwirl(1.0);
                case "wave":
                    return createWave(1.0);
                case "orbit":
                    return createOrbit(1.0);
                case "spiral":
                    return createSpiral(1.0);
                default:
                    return null;
            }
        }
    }
}
