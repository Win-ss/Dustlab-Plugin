package com.winss.dustlab.media;

import com.winss.dustlab.packed.PackedParticleArray;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public class PixelToParticleMapper {
    
    // optimized pixel-to-particle conversion with better coverage and performance

    public static PackedParticleArray imageToParticlesOptimized(BufferedImage image, int blockWidth, int blockHeight, int maxParticles, float particleScale) {
        PackedParticleArray.Builder builder = PackedParticleArray.builder(maxParticles);
        
        int imgWidth = image.getWidth();
        int imgHeight = image.getHeight();
        
        if (imgWidth == 0 || imgHeight == 0) {
            return builder.build();
        }
        
        // Pre-calculate RGB array for faster access
        int[] rgbArray = new int[imgWidth * imgHeight];
        image.getRGB(0, 0, imgWidth, imgHeight, rgbArray, 0, imgWidth);
        
        int totalPixels = imgWidth * imgHeight;
        double density = (double) maxParticles / totalPixels;
        int step = Math.max(1, (int) Math.ceil(Math.sqrt(1.0 / density)));
        
        double scaleX = (double) blockWidth / imgWidth;
        double scaleY = (double) blockHeight / imgHeight;
        
        List<double[]> recentPositions = new ArrayList<>();
        double minDistance = Math.min(scaleX, scaleY) * 0.3;
        int maxRecentCheck = Math.min(100, maxParticles / 10);
        
        int particleCount = 0;
        
        // Primary sampling pass with natural distribution
        for (int y = 0; y < imgHeight && particleCount < maxParticles; y += step) {
            for (int x = 0; x < imgWidth && particleCount < maxParticles; x += step) {
                int rgbIndex = y * imgWidth + x;
                int rgb = rgbArray[rgbIndex];
                int alpha = (rgb >> 24) & 0xFF;
                
                if (alpha < 20) {
                    continue;
                }
                
                double particleX = (x * scaleX) - (blockWidth / 2.0);
                double particleY = (blockHeight / 2.0) - (y * scaleY);
                
                // Only check distance to recent particles for performance
                boolean tooClose = false;
                int checkStart = Math.max(0, recentPositions.size() - maxRecentCheck);
                for (int i = checkStart; i < recentPositions.size(); i++) {
                    double[] pos = recentPositions.get(i);
                    double dx = particleX - pos[0];
                    double dy = particleY - pos[1];
                    if (dx * dx + dy * dy < minDistance * minDistance) {
                        tooClose = true;
                        break;
                    }
                }
                
                if (tooClose) {
                    continue;
                }
                
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                builder.add(
                    particleX,
                    particleY,
                    0.0d,
                    red / 255.0,
                    green / 255.0,
                    blue / 255.0,
                    0,
                    particleScale
                );
                recentPositions.add(new double[]{particleX, particleY});
                particleCount++;
                
                // Keep recentPositions list manageable
                if (recentPositions.size() > maxRecentCheck * 2) {
                    recentPositions = new ArrayList<>(recentPositions.subList(maxRecentCheck, recentPositions.size()));
                }
            }
        }
        
        // Fill gaps with offset sampling - add some randomness for natural look
        if (particleCount < maxParticles * 0.85 && step > 1) {
            int halfStep = step / 2;
            for (int y = halfStep; y < imgHeight && particleCount < maxParticles; y += step) {
                for (int x = halfStep; x < imgWidth && particleCount < maxParticles; x += step) {
                    int rgbIndex = y * imgWidth + x;
                    int rgb = rgbArray[rgbIndex];
                    int alpha = (rgb >> 24) & 0xFF;
                    
                    if (alpha < 20) {
                        continue;
                    }
                    
                    double offsetX = (Math.random() - 0.5) * scaleX * 0.3;
                    double offsetY = (Math.random() - 0.5) * scaleY * 0.3;
                    double particleX = (x * scaleX) - (blockWidth / 2.0) + offsetX;
                    double particleY = (blockHeight / 2.0) - (y * scaleY) + offsetY;
                    
                    boolean tooClose = false;
                    int checkStart = Math.max(0, recentPositions.size() - maxRecentCheck);
                    for (int i = checkStart; i < recentPositions.size(); i++) {
                        double[] pos = recentPositions.get(i);
                        double dx = particleX - pos[0];
                        double dy = particleY - pos[1];
                        if (dx * dx + dy * dy < minDistance * minDistance) {
                            tooClose = true;
                            break;
                        }
                    }
                    
                    if (tooClose) {
                        continue;
                    }
                    
                    int red = (rgb >> 16) & 0xFF;
                    int green = (rgb >> 8) & 0xFF;
                    int blue = rgb & 0xFF;

                    builder.add(
                        particleX,
                        particleY,
                        0.0d,
                        red / 255.0,
                        green / 255.0,
                        blue / 255.0,
                        0,
                        particleScale
                    );
                    recentPositions.add(new double[]{particleX, particleY});
                    particleCount++;
                    
                    if (recentPositions.size() > maxRecentCheck * 2) {
                        recentPositions = new ArrayList<>(recentPositions.subList(maxRecentCheck, recentPositions.size()));
                    }
                }
            }
        }
        
        return builder.build();
    }
}
