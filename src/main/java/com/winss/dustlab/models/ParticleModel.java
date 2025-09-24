package com.winss.dustlab.models;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

public class ParticleModel {
    
    @SerializedName("metadata")
    private Map<String, Object> metadata;
    
    @SerializedName("particles")
    private List<ParticleData> particles;
    
    private transient String name;
    private transient int duration = 100; 
    
    public ParticleModel() {
    }
    
    public ParticleModel(String name, List<ParticleData> particles) {
        this.name = name;
        this.particles = particles;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public List<ParticleData> getParticles() {
        return particles;
    }
    
    public void setParticles(List<ParticleData> particles) {
        this.particles = particles;
    }
    
    public int getDuration() {
        return duration;
    }
    
    public void setDuration(int duration) {
        this.duration = duration;
    }
    

    @SuppressWarnings("unchecked")
    public <T> T getMetadataField(String key) {
        if (metadata == null) return null;
        return (T) metadata.get(key);
    }
    

    public int getParticleCount() {
        if (metadata != null && metadata.containsKey("particleCount")) {
            Object count = metadata.get("particleCount");
            if (count instanceof Number) {
                return ((Number) count).intValue();
            }
        }
        return particles != null ? particles.size() : 0;
    }
    

    public String getGeneratedBy() {
        return getMetadataField("generatedBy");
    }
    

    public String getWebsite() {
        return getMetadataField("website");
    }
    
    public String getGeneratedOn() {
        return getMetadataField("generatedOn");
    }
    
    public String getSourceFile() {
        return getMetadataField("sourceFile");
    }
    
    public Map<String, Object> getSettings() {
        return getMetadataField("settings");
    }
}
