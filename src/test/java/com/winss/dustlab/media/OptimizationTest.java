package com.winss.dustlab.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import com.winss.dustlab.models.ParticleData;
import com.winss.dustlab.packed.PackedParticleArray;

//  Test class to verify the optimized pixel-to-particle conversion
public class OptimizationTest {
    
    public static void main(String[] args) {
        // Create a test image with a simple pattern
        BufferedImage testImage = createTestImage(100, 100);
        
        // Test the optimized conversion
        PackedParticleArray packed = PixelToParticleMapper.imageToParticlesOptimized(
            testImage, 20, 20, 1000, 1.0f);
        List<ParticleData> particles = packed.toParticleDataList();
        
        System.out.println("Test Results:");
        System.out.println("Image size: " + testImage.getWidth() + "x" + testImage.getHeight());
        System.out.println("Generated particles: " + particles.size());
        System.out.println("Coverage: " + (particles.size() / (double)(testImage.getWidth() * testImage.getHeight()) * 100) + "%");
        
        double minX = particles.stream().mapToDouble(ParticleData::getX).min().orElse(0);
        double maxX = particles.stream().mapToDouble(ParticleData::getX).max().orElse(0);
        double minY = particles.stream().mapToDouble(ParticleData::getY).min().orElse(0);
        double maxY = particles.stream().mapToDouble(ParticleData::getY).max().orElse(0);
        
        System.out.println("X range: " + minX + " to " + maxX);
        System.out.println("Y range: " + minY + " to " + maxY);
        
        // This should show good distribution across the entire image area
        // instead of just diagonal lines or edges
    }
    
    private static BufferedImage createTestImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        
        // Fill with a gradient to test particle coverage
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = (int) (255.0 * x / width);
                int green = (int) (255.0 * y / height);
                int blue = 128;
                Color color = new Color(red, green, blue);
                image.setRGB(x, y, color.getRGB());
            }
        }
        
        g.dispose();
        return image;
    }
}
