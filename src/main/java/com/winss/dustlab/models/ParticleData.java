package com.winss.dustlab.models;

import com.google.gson.annotations.SerializedName;
import org.bukkit.Color;
import org.bukkit.Particle;

public class ParticleData {
    
    @SerializedName("x")
    private double x = 0.0;
    
    @SerializedName("y")
    private double y = 0.0;
    
    @SerializedName("z")
    private double z = 0.0;
    
    @SerializedName(value = "r", alternate = {"red"})
    private double r = 1.0; 
    
    @SerializedName(value = "g", alternate = {"green"})
    private double g = 0.0;
    
    @SerializedName(value = "b", alternate = {"blue"})
    private double b = 0.0; 
    
    @SerializedName("delay")
    private int delay = 0;
    
    @SerializedName("scale")
    private float scale = 1.0f; 
    
    public ParticleData() {
    }
    
    public ParticleData(double x, double y, double z, double r, double g, double b) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.r = r;
        this.g = g;
        this.b = b;
    }
    
    public double getX() {
        return x;
    }
    
    public void setX(double x) {
        this.x = x;
    }
    
    public double getY() {
        return y;
    }
    
    public void setY(double y) {
        this.y = y;
    }
    
    public double getZ() {
        return z;
    }
    
    public void setZ(double z) {
        this.z = z;
    }
    
    public double getR() {
        return r;
    }
    
    public void setR(double r) {
        this.r = Math.max(0.0, Math.min(1.0, r)); 
    }
    
    public double getG() {
        return g;
    }
    
    public void setG(double g) {
        this.g = Math.max(0.0, Math.min(1.0, g)); 
    }
    
    public double getB() {
        return b;
    }
    
    public void setB(double b) {
        this.b = Math.max(0.0, Math.min(1.0, b)); 
    }
    
    public int getDelay() {
        return delay;
    }
    
    public void setDelay(int delay) {
        this.delay = delay;
    }
    
    public float getScale() {
        return scale;
    }
    
    public void setScale(float scale) {
        this.scale = Math.max(0.1f, Math.min(5.0f, scale)); 
    }
    
    /**
     * Get the Bukkit Color object from RGB values
     */
    public Color getColor() {
        int red = (int) (r * 255);
        int green = (int) (g * 255);
        int blue = (int) (b * 255);
        return Color.fromRGB(red, green, blue);
    }
    
    /**
     * Get the dust options for colored dust particles
     */
    public Particle.DustOptions getDustOptions() {
        return new Particle.DustOptions(getColor(), scale);
    }
}
