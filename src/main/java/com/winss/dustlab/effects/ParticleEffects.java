package com.winss.dustlab.effects;

import com.winss.dustlab.models.ParticleData;
import org.bukkit.Location;
import org.bukkit.util.Vector;

public final class ParticleEffects {

    private ParticleEffects() {
    }

    public static Location applyEffects(ParticleData particle, Location baseLocation, EffectSettings settings, long tickTime) {
        Location origin = baseLocation.clone();
        Vector offset = new Vector(particle.getX(), particle.getY(), particle.getZ());

        if (settings == null || !settings.hasEffects()) {
            return origin.add(offset);
        }

        if (settings.rotationSpeed != 0.0) {
            double rotationAngle = (tickTime * settings.rotationSpeed) % (Math.PI * 2.0);
            applyRotation(offset, rotationAngle, settings.rotateX, settings.rotateY, settings.rotateZ);
        }

        if (settings.oscillationAmplitude > 0.0) {
            double oscillationPhase = tickTime * settings.oscillationSpeed;
            applyOscillation(offset, oscillationPhase, settings.oscillationAmplitude);
        }

        if (settings.pulseAmplitude > 0.0) {
            double pulsePhase = tickTime * settings.pulseSpeed;
            applyPulse(offset, pulsePhase, settings.pulseAmplitude);
        }

        if (settings.bounceHeight > 0.0) {
            double bouncePhase = tickTime * settings.bounceSpeed;
            applyBounce(offset, bouncePhase, settings.bounceHeight);
        }

        if (settings.flowAmplitude > 0.0) {
            double flowPhase = tickTime * settings.flowSpeed;
            applyFlow(offset, flowPhase, settings.flowAmplitude);
        }

        if (settings.swirlRadius > 0.0) {
            double swirlPhase = tickTime * settings.swirlSpeed;
            applySwirl(offset, swirlPhase, settings.swirlRadius);
        }

        if (settings.waveAmplitude > 0.0) {
            double wavePhase = tickTime * settings.waveSpeed;
            applyWave(offset, wavePhase, settings.waveAmplitude);
        }

        if (settings.orbitRadius > 0.0) {
            double orbitAngle = tickTime * settings.orbitSpeed;
            applyOrbit(offset, orbitAngle, settings.orbitRadius);
        }

        if (settings.spiralExpansion != 0.0) {
            double spiralPhase = tickTime * settings.spiralSpeed;
            applySpiral(offset, spiralPhase, settings.spiralExpansion);
        }

        return origin.add(offset);
    }

    private static void applyRotation(Vector vector, double angle, boolean rotateX, boolean rotateY, boolean rotateZ) {
        double x = vector.getX();
        double y = vector.getY();
        double z = vector.getZ();

        if (rotateX) {
            double cosX = Math.cos(angle);
            double sinX = Math.sin(angle);
            double newY = y * cosX - z * sinX;
            double newZ = y * sinX + z * cosX;
            y = newY;
            z = newZ;
        }

        if (rotateY) {
            double cosY = Math.cos(angle);
            double sinY = Math.sin(angle);
            double newX = x * cosY - z * sinY;
            double newZ = x * sinY + z * cosY;
            x = newX;
            z = newZ;
        }

        if (rotateZ) {
            double cosZ = Math.cos(angle);
            double sinZ = Math.sin(angle);
            double newX = x * cosZ - y * sinZ;
            double newY = x * sinZ + y * cosZ;
            x = newX;
            y = newY;
        }

        vector.setX(x);
        vector.setY(y);
        vector.setZ(z);
    }

    private static void applyOscillation(Vector vector, double phase, double amplitude) {
        double scale = 1.0 + Math.sin(phase) * amplitude;
        vector.multiply(scale);
    }

    private static void applyPulse(Vector vector, double phase, double amplitude) {
        double scale = 1.0 + Math.sin(phase * 2.0) * amplitude;
        vector.multiply(scale);
    }

    private static void applyBounce(Vector vector, double phase, double height) {
        double offset = Math.abs(Math.sin(phase)) * height;
        vector.setY(vector.getY() + offset);
    }

    private static void applyFlow(Vector vector, double phase, double amplitude) {
        double offset = Math.sin(phase) * amplitude;
        vector.setX(vector.getX() + offset);
    }

    private static void applySwirl(Vector vector, double phase, double radius) {
        double distance = Math.sqrt(vector.getX() * vector.getX() + vector.getZ() * vector.getZ());
        double effectiveDistance = distance > 0.0 ? distance : radius;
        double angle = Math.atan2(vector.getZ(), vector.getX()) + phase * effectiveDistance;
        double newX = Math.cos(angle) * effectiveDistance;
        double newZ = Math.sin(angle) * effectiveDistance;
        vector.setX(newX);
        vector.setZ(newZ);
    }

    private static void applyWave(Vector vector, double phase, double amplitude) {
        double distance = Math.sqrt(vector.getX() * vector.getX() + vector.getZ() * vector.getZ());
        double waveOffset = Math.sin(phase + distance * 0.5) * amplitude;
        vector.setY(vector.getY() + waveOffset);
    }

    private static void applyOrbit(Vector vector, double angle, double radius) {
        double orbitX = Math.cos(angle) * radius;
        double orbitZ = Math.sin(angle) * radius;
        vector.add(new Vector(orbitX, 0.0, orbitZ));
    }

    private static void applySpiral(Vector vector, double phase, double expansion) {
        double distance = Math.sqrt(vector.getX() * vector.getX() + vector.getZ() * vector.getZ());
        double angle = Math.atan2(vector.getZ(), vector.getX()) + phase;
        double newDistance = distance + expansion;
        double newX = Math.cos(angle) * newDistance;
        double newZ = Math.sin(angle) * newDistance;
        vector.setX(newX);
        vector.setZ(newZ);
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

        public EffectSettings() {
        }

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
