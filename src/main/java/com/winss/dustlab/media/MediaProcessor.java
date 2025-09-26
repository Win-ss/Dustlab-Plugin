package com.winss.dustlab.media;

import com.winss.dustlab.DustLab;
import com.winss.dustlab.models.ParticleData;
import com.winss.dustlab.packed.PackedParticleArray;
import org.bukkit.plugin.Plugin;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
@SuppressWarnings("unused")
public class MediaProcessor {
    
    // Limits are now driven by config (DustLabConfig)
    private static final int DEFAULT_FRAME_DELAY = 50;
    private static final int MIN_FRAME_DELAY = 40;
    private static final int MAX_FRAME_DELAY = 1000;
    private static final int DOWNLOAD_TIMEOUT = 20000;
    
    private static final ExecutorService MEDIA_PROCESSING_EXECUTOR = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors() / 2), new ThreadFactory() {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "DustLab-MediaProcessor-" + threadNumber.getAndIncrement());
            t.setDaemon(false);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    });
    
    private final Plugin plugin;
    
    public MediaProcessor(Plugin plugin) {
        this.plugin = plugin;
    }
    

    public static void shutdown() {
        if (!MEDIA_PROCESSING_EXECUTOR.isShutdown()) {
            MEDIA_PROCESSING_EXECUTOR.shutdown();
            try {
                while (!MEDIA_PROCESSING_EXECUTOR.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    java.util.logging.Logger.getLogger(MediaProcessor.class.getName())
                        .info("Waiting for media processing tasks to finish...");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Submit a task to the shared media processing executor. Prefer this over Bukkit async tasks
     * so shutdown() can gracefully drain remaining work.
     */
    public static void submitAsync(Runnable task) {
        MEDIA_PROCESSING_EXECUTOR.submit(task);
    }

    /**
     * Submit a task and get a Future for cancellation or waiting.
     */
    public static java.util.concurrent.Future<?> submitAsyncFuture(Runnable task) {
        return MEDIA_PROCESSING_EXECUTOR.submit(task);
    }
    

    public CompletableFuture<AnimatedModel> processMediaUrl(String url, String modelName, 
                                                          int blockWidth, int blockHeight, 
                                                          int maxParticleCount, boolean persistent) {
        return processMediaUrl(url, modelName, blockWidth, blockHeight, maxParticleCount, persistent, 1);
    }
    
    public CompletableFuture<AnimatedModel> processMediaUrl(String url, String modelName, 
                                                          int blockWidth, int blockHeight, 
                                                          int maxParticleCount, boolean persistent, int frameSkip) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] mediaData = downloadMedia(url);
                if (mediaData == null) {
                    throw new RuntimeException("Failed to download media from URL");
                }
                
                AnimatedModel result = processMediaData(mediaData, modelName, blockWidth, blockHeight, maxParticleCount, url, persistent, frameSkip);
                
                return result;
                
            } catch (Exception e) {
                plugin.getLogger().severe("Error in async processing: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Media processing failed: " + e.getMessage());
            }
        }, MEDIA_PROCESSING_EXECUTOR); 
    }
    
// download media with size limit and timeouts
    private byte[] downloadMedia(String urlString) throws IOException {
        URL url = new URL(urlString);
        URLConnection connection = url.openConnection();
        
        connection.setConnectTimeout(DOWNLOAD_TIMEOUT);
        connection.setReadTimeout(DOWNLOAD_TIMEOUT);
        
        connection.setRequestProperty("User-Agent", "DustLab-MediaProcessor/1.0");

        int contentLength = connection.getContentLength();
        long maxBytes = ((DustLab) plugin).getDustLabConfig().getMediaMaxFileSizeBytes();
        if (contentLength > 0 && contentLength > maxBytes) {
            throw new IOException("File too large: " + contentLength + " bytes (max: " + maxBytes + " bytes)");
        }
        
        try (InputStream inputStream = connection.getInputStream();
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(
                 contentLength > 0 ? contentLength : 8192)) {
            
            byte[] buffer = new byte[16384]; // increased the buffer for better perforamance :D
            int totalSize = 0;
            int bytesRead;
            
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                totalSize += bytesRead;
                if (totalSize > maxBytes) {
                    throw new IOException("File too large during download (exceeded " + maxBytes + " bytes)");
                }
                
                baos.write(buffer, 0, bytesRead);
            }
            
            return baos.toByteArray();
        }
    }
    
    
    // "Optimized" frame processing for GIFs and images

    private BufferedImage processFrameOptimized(BufferedImage frame,
                                               BufferedImage ignoredPreviousFrame,
                                               boolean isGif,
                                               int targetWidth,
                                               int targetHeight) {
        // Always treat each frame as a complete snapshot for particle generation.
        // Intentionally avoid blending with previous frames to prevent ghosting and overlapping.
        if (frame.getType() == BufferedImage.TYPE_INT_ARGB &&
            frame.getWidth() == targetWidth &&
            frame.getHeight() == targetHeight) {
            return frame;
        }

        frame = ensureARGBFormat(frame);

        if (frame.getWidth() != targetWidth || frame.getHeight() != targetHeight) {
            BufferedImage resized = resizeImageFast(frame, targetWidth, targetHeight);
            if (frame != resized) {
                frame.flush();
            }
            return resized;
        }

        return frame;
    }
    

    private BufferedImage simpleFrameComposition(BufferedImage currentFrame, BufferedImage previousFrame) {
        BufferedImage result = new BufferedImage(currentFrame.getWidth(), currentFrame.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED);
        
        if (previousFrame != null) {
            g.drawImage(previousFrame, 0, 0, null);
        }
        
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(currentFrame, 0, 0, null);
        g.dispose();
        
        return result;
    }
    
    private BufferedImage resizeImageFast(BufferedImage original, int targetWidth, int targetHeight) {
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED);
        
        g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        
        return resized;
    }
    
    private int getFrameDelayOptimized(ImageReader reader, int frameIndex, int frameSkip, int totalFrames) {
        try {
            int delay = getFrameDelaySimple(reader, frameIndex);
            
            // If frame skipping, accumulate delays from skipped frames
            if (frameSkip > 1) {
                int totalDelay = delay;
                for (int i = 1; i < frameSkip && frameIndex + i < totalFrames; i++) {
                    try {
                        totalDelay += getFrameDelaySimple(reader, frameIndex + i);
                    } catch (Exception e) {
                        totalDelay += delay; 
                    }
                }
                delay = totalDelay;
            }
            
            // Clamp and gently quantize near 50ms to ensure perfect tick alignment for 20 FPS GIFs
            int clamped = Math.max(MIN_FRAME_DELAY, Math.min(MAX_FRAME_DELAY, delay));
            return quantizeFrameDelay(clamped);
            
        } catch (Exception e) {
            return DEFAULT_FRAME_DELAY;
        }
    }

    // If a frame delay is very close to 50ms (Minecraft tick), snap it to 50ms so animations stay tick-aligned
    private int quantizeFrameDelay(int delayMs) {
        // Snap 45-55ms to exactly 50ms
        if (delayMs >= 45 && delayMs <= 55) return 50;
        return delayMs;
    }


    private AnimatedModel processMediaData(byte[] mediaData, String modelName, int blockWidth, 
                                         int blockHeight, int maxParticleCount, String sourceUrl, 
                                         boolean persistent, int frameSkip) throws IOException {
        
        List<FrameData> frames = new ArrayList<>();
        
        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(new ByteArrayInputStream(mediaData))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
            
            if (!readers.hasNext()) {
                throw new IOException("No suitable image reader found for this media format");
            }
            
            ImageReader reader = readers.next();
            reader.setInput(imageInputStream);
            
            int numFrames;
            try {
                numFrames = reader.getNumImages(true);
                if (numFrames <= 0) numFrames = 1;
            } catch (Exception e) {
                numFrames = 1;
            }
            
            // Calculate target frame count 
            int actualOriginalFrames = numFrames;
            int maxFrames = ((DustLab) plugin).getDustLabConfig().getMediaMaxFrames();
            int targetFrameCount = Math.min(maxFrames, (actualOriginalFrames + frameSkip - 1) / frameSkip);
            
            boolean isGif = "gif".equalsIgnoreCase(reader.getFormatName());
            
            plugin.getLogger().info("Processing " + targetFrameCount + " frames from " + actualOriginalFrames + " total");
            
            // pre-calculate
            int targetWidth = blockWidth * 16;
            int targetHeight = blockHeight * 16;
            
            int processedFrames = 0;
            BufferedImage previousFrame = null;
            
            for (int frameIndex = 0; frameIndex < numFrames && processedFrames < maxFrames; frameIndex += frameSkip) {
                
                BufferedImage frame;
                try {
                    frame = reader.read(frameIndex);
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not read frame " + frameIndex + ", stopping at " + frames.size() + " frames");
                    break;
                }
                
                if (frame == null) {
                    plugin.getLogger().warning("Frame " + frameIndex + " is null, stopping processing");
                    break;
                }
                
                // Important: do NOT composite GIF frames with previous ones here; each frame must be standalone
                frame = processFrameOptimized(frame, null, isGif, targetWidth, targetHeight);
                
                int frameDelay = getFrameDelayOptimized(reader, frameIndex, frameSkip, numFrames);
                
                float particleScale = ((DustLab) plugin).getDustLabConfig().getMediaParticleScale();
                PackedParticleArray packedParticles = PixelToParticleMapper.imageToParticlesOptimized(
                    frame, blockWidth, blockHeight, maxParticleCount, particleScale);

                frames.add(new FrameData(packedParticles, frameIndex, frameDelay));
                processedFrames++;
                
                // No dependency on previous frame composition
                if (previousFrame != null && previousFrame != frame) {
                    previousFrame.flush();
                }
                previousFrame = frame;
                
                // Progress reporting with cool ANSI progress bar
                if (processedFrames == 1 || processedFrames == targetFrameCount || 
                    processedFrames % Math.max(1, targetFrameCount / 10) == 0) {
                    
                    double progressPercent = (double) processedFrames / targetFrameCount * 100;
                    String progressBar = createProgressBar(progressPercent);
                    plugin.getLogger().info(String.format("Processing frames: %s %.0f%% (%d/%d)", 
                                          progressBar, progressPercent, processedFrames, targetFrameCount));
                }
                
                if (processedFrames % 10 == 0) {
                    Thread.yield();
                    
                    if (processedFrames % 50 == 0 && targetFrameCount > 100) {
                        System.gc();
                    }
                }
            }
            
            reader.dispose();
        }
        
        if (frames.isEmpty()) {
            throw new IOException("No frames could be processed from the media");
        }
        
        
        boolean looping = frames.size() > 1; 
        AnimatedModel model = new AnimatedModel(modelName, frames, looping, sourceUrl, 
                                              blockWidth, blockHeight, maxParticleCount);
        
        java.util.Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("generatedBy", "DustLab v1.1");
        metadata.put("website", "https://winss.xyz/dustlab");
        metadata.put("generatedOn", java.time.Instant.now().toString());
        metadata.put("sourceFile", sourceUrl);
        metadata.put("particleCount", model.getTotalParticleCount());
        metadata.put("frameCount", frames.size());
        metadata.put("globalParticleSize", (double) ((DustLab) plugin).getDustLabConfig().getMediaParticleScale());
        model.setMetadata(metadata);
        
        plugin.getLogger().info("Model creation complete! " + frames.size() + " frames, " + 
                              model.getTotalParticleCount() + " particles");
        
        return model;
    }

    private BufferedImage resizeImage(BufferedImage original, int targetWidth, int targetHeight) {
        double scaleX = (double) targetWidth / original.getWidth();
        double scaleY = (double) targetHeight / original.getHeight();
        double scale = Math.min(scaleX, scaleY);
        
        int newWidth = (int) (original.getWidth() * scale);
        int newHeight = (int) (original.getHeight() * scale);
        
        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, 
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(original, 0, 0, newWidth, newHeight, null);
        g2d.dispose();
        
        return resized;
    }
    

    private BufferedImage resizeImageHighQuality(BufferedImage original, int targetWidth, int targetHeight) {
        if (original == null) return null;
        
        double scaleX = (double) targetWidth / original.getWidth();
        double scaleY = (double) targetHeight / original.getHeight();
        double scale = Math.min(scaleX, scaleY);
        
        int newWidth = (int) (original.getWidth() * scale);
        int newHeight = (int) (original.getHeight() * scale);
        
        BufferedImage current = original;
        while (current.getWidth() > newWidth * 2 || current.getHeight() > newHeight * 2) {
            int tempWidth = Math.max(newWidth, current.getWidth() / 2);
            int tempHeight = Math.max(newHeight, current.getHeight() / 2);
            
            BufferedImage temp = new BufferedImage(tempWidth, tempHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = temp.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.drawImage(current, 0, 0, tempWidth, tempHeight, null);
            g2d.dispose();
            
            current = temp;
        }
        
        if (current.getWidth() != newWidth || current.getHeight() != newHeight) {
            BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = resized.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            g2d.drawImage(current, 0, 0, newWidth, newHeight, null);
            g2d.dispose();
            return resized;
        }
        
        return current;
    }
    

    private BufferedImage ensureARGBFormat(BufferedImage original) {
        if (original == null) return null;
        
        if (original.getType() == BufferedImage.TYPE_INT_ARGB) {
            return fixTransparencyIssues(original);
        }
        
        BufferedImage argbImage = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = argbImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        
        if (original.getColorModel().hasAlpha()) {
            g2d.setComposite(AlphaComposite.Src);
        } else {
            g2d.setComposite(AlphaComposite.SrcOver);
            g2d.setColor(new Color(0, 0, 0, 0));
            g2d.fillRect(0, 0, argbImage.getWidth(), argbImage.getHeight());
        }
        
        g2d.drawImage(original, 0, 0, null);
        g2d.dispose();
        
        return fixTransparencyIssues(argbImage);
    }
    

    private BufferedImage fixTransparencyIssues(BufferedImage image) {
        if (image == null) return null;
        
        BufferedImage fixed = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int alpha = (rgb >> 24) & 0xFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                
                boolean shouldBeTransparent = false;
                
                if (red == 0 && green == 0 && blue == 0 && alpha < 128) {
                    shouldBeTransparent = true;
                }
                
                if (alpha < 25 && red < 20 && green < 20 && blue < 20) {
                    shouldBeTransparent = true;
                }
                
                if (alpha < 10) {
                    shouldBeTransparent = true;
                }
                
                if (shouldBeTransparent) {
                    fixed.setRGB(x, y, 0x00000000); 
                } else {
                    if (alpha > 0 && alpha < 50) {
                        alpha = Math.max(alpha, 100); 
                        int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                        fixed.setRGB(x, y, newRgb);
                    } else {
                        fixed.setRGB(x, y, rgb); 
                    }
                }
            }
        }
        
        return fixed;
    }
    

    private int getFrameDelaySimple(ImageReader reader, int frameIndex) {
        try {
            IIOMetadata metadata = reader.getImageMetadata(frameIndex);
            if (metadata != null) {
                String formatName = reader.getFormatName();
                if ("gif".equalsIgnoreCase(formatName)) {
                    int delay = getGifFrameDelay(metadata);
                    return delay;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read frame delay for frame " + frameIndex + ": " + e.getMessage());
        }
        
        return DEFAULT_FRAME_DELAY;
    }
    


    private int getGifFrameDelay(IIOMetadata metadata) {
        try {
            String[] formatNames = metadata.getMetadataFormatNames();
            for (String formatName : formatNames) {
                if ("javax_imageio_gif_image_1.0".equals(formatName)) {
                    IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(formatName);
                    IIOMetadataNode graphicsControlExtension = getChild(root, "GraphicControlExtension");
                    if (graphicsControlExtension != null) {
                        String delayTime = graphicsControlExtension.getAttribute("delayTime");
                        if (delayTime != null && !delayTime.isEmpty()) {
                            try {
                                int centiseconds = Integer.parseInt(delayTime);
                                int delay = centiseconds * 10;
                                
                                if (delay <= 0) {

                                    delay = DEFAULT_FRAME_DELAY;
                                } else if (delay < 20) {
                                    // browsers clamp these to ~50ms anyway
                                    delay = Math.max(delay, 40); 
                                }
                                
                                return delay;
                            } catch (NumberFormatException e) {
                                plugin.getLogger().warning("Invalid delay time format: " + delayTime);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Could not extract GIF frame delay: " + e.getMessage());
        }
        
        return DEFAULT_FRAME_DELAY;
    }
    

    private int getFrameDelay(ImageReader reader, int frameIndex) {
        return getFrameDelaySimple(reader, frameIndex);
    }
    
    private IIOMetadataNode getChild(IIOMetadataNode parent, String childName) {
        for (int i = 0; i < parent.getLength(); i++) {
            if (childName.equals(parent.item(i).getNodeName())) {
                return (IIOMetadataNode) parent.item(i);
            }
        }
        return null;
    }
    
    /**
     * Legacy GIF frame composition - replaced by optimized version
     * Kept for compatibility but not used, (clearly made by someone who has no idea what they're doing)
     **/
    /*
    private BufferedImage composeGifFrame(ImageReader reader, int frameIndex, BufferedImage frame, 
                                         BufferedImage canvas, BufferedImage previousFrame) {
        try {
            IIOMetadata metadata = reader.getImageMetadata(frameIndex);
            int disposalMethod = getGifDisposalMethod(metadata);
            boolean hasTransparency = hasTransparency(frame);
            
            if (canvas == null) {
                canvas = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2d = canvas.createGraphics();
                g2d.setComposite(AlphaComposite.Src);
                g2d.setColor(new Color(0, 0, 0, 0)); 
                g2d.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
                g2d.dispose();
            }
            
            if (frameIndex > 0 && previousFrame != null) {
                Graphics2D g2d = canvas.createGraphics();
                
                switch (disposalMethod) {
                    case 0: 
                    case 1: 
                        break;
                    case 2: 
                        g2d.setComposite(AlphaComposite.Src);
                        g2d.setColor(new Color(0, 0, 0, 0)); 
                        g2d.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
                        break;
                    case 3: 
                        if (previousFrame != null) {
                            g2d.setComposite(AlphaComposite.Src);
                            g2d.drawImage(previousFrame, 0, 0, null);
                        }
                        break;
                }
                g2d.dispose();
            }
            
            Graphics2D g2d = canvas.createGraphics();
            if (hasTransparency) {
                g2d.setComposite(AlphaComposite.SrcOver);
            } else {
                g2d.setComposite(AlphaComposite.Src);
            }
            
            BufferedImage argbFrame = ensureARGBFormat(frame);
            g2d.drawImage(argbFrame, 0, 0, null);
            g2d.dispose();
            
            return copyImage(canvas);
            
        } catch (Exception e) {
            plugin.getLogger().warning("Error composing GIF frame " + frameIndex + ": " + e.getMessage());
            return ensureARGBFormat(frame); 
        }
    }
    */
    
    //Improved GIF frame composition with better disposal method handling - DISABLED FOR OPTIMIZATION
    /*
    private BufferedImage composeGifFrameImproved(ImageReader reader, int frameIndex, BufferedImage frame, 
                                                 BufferedImage canvas, BufferedImage previousCanvas) {
        try {
            IIOMetadata metadata = reader.getImageMetadata(frameIndex);
            int disposalMethod = getGifDisposalMethod(metadata);
            
            int frameX = 0, frameY = 0;
            try {
                String[] names = metadata.getMetadataFormatNames();
                for (String name : names) {
                    if ("javax_imageio_gif_image_1.0".equals(name)) {
                        Node root = metadata.getAsTree(name);
                        Node imageDescriptor = findChildNode(root, "ImageDescriptor");
                        if (imageDescriptor != null) {
                            Node leftPos = imageDescriptor.getAttributes().getNamedItem("imageLeft");
                            Node topPos = imageDescriptor.getAttributes().getNamedItem("imageTop");
                            if (leftPos != null) frameX = Integer.parseInt(leftPos.getNodeValue());
                            if (topPos != null) frameY = Integer.parseInt(topPos.getNodeValue());
                        }
                    }
                }
            } catch (Exception e) {
                // Use default positions if metadata reading fails
            }
            
            if (canvas == null) {
                int canvasWidth = Math.max(frame.getWidth() + frameX, frame.getWidth());
                int canvasHeight = Math.max(frame.getHeight() + frameY, frame.getHeight());
                canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2d = canvas.createGraphics();
                g2d.setComposite(AlphaComposite.Src);
                g2d.setColor(new Color(0, 0, 0, 0)); 
                g2d.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
                g2d.dispose();
            }
            
            if (frameIndex > 0) {
                Graphics2D g2d = canvas.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                switch (disposalMethod) {
                    case 0: 
                    case 1: 
                        break;
                    case 2: 
                        g2d.setComposite(AlphaComposite.Src);
                        g2d.setColor(new Color(0, 0, 0, 0)); 
                        g2d.fillRect(frameX, frameY, frame.getWidth(), frame.getHeight());
                        break;
                    case 3: 
                        if (previousCanvas != null) {
                            g2d.setComposite(AlphaComposite.Src);
                            g2d.drawImage(previousCanvas, 0, 0, null);
                        }
                        break;
                }
                g2d.dispose();
            }
            
            // Composite new frame with proper transparency handling
            Graphics2D g2d = canvas.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            
            // Ensure frame is properly formatted
            BufferedImage processedFrame = ensureARGBFormat(frame);
            
            g2d.setComposite(AlphaComposite.SrcOver);
            g2d.drawImage(processedFrame, frameX, frameY, null);
            g2d.dispose();
            
            return copyImage(canvas);
            
        } catch (Exception e) {
            plugin.getLogger().warning("Error in improved GIF composition for frame " + frameIndex + ": " + e.getMessage());
            return ensureARGBFormat(frame); 
        }
    }
    */
    
    // Extract GIF disposal method from metadata
    /*
    private int getGifDisposalMethod(IIOMetadata metadata) {
        if (metadata == null) return 0;
        
        try {
            String[] names = metadata.getMetadataFormatNames();
            for (String name : names) {
                if ("javax_imageio_gif_image_1.0".equals(name)) {
                    Node root = metadata.getAsTree(name);
                    Node gce = findChildNode(root, "GraphicControlExtension");
                    if (gce != null) {
                        Node disposal = gce.getAttributes().getNamedItem("disposalMethod");
                        if (disposal != null) {
                            return Integer.parseInt(disposal.getNodeValue());
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        
        return 0; 
    }
    */
    
    //Check if image has transparency
    /*
    private boolean hasTransparency(BufferedImage image) {
        if (image == null) return false;
        
        ColorModel cm = image.getColorModel();
        if (cm.hasAlpha()) {
            return true;
        }
        
        if (image.getType() == BufferedImage.TYPE_BYTE_INDEXED) {
            IndexColorModel icm = (IndexColorModel) cm;
            return icm.getTransparentPixel() >= 0;
        }
        
        return false;
    }
    */
    
    // Find child node by name
    /*
    private Node findChildNode(Node parent, String nodeName) {
        if (parent == null) return null;
        
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (nodeName.equals(child.getNodeName())) {
                return child;
            }
        }
        return null;
    }
    */
    
    /*
    private BufferedImage copyImage(BufferedImage original) {
        if (original == null) return null;
        
        BufferedImage copy = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = copy.createGraphics();
        g2d.drawImage(original, 0, 0, null);
        g2d.dispose();
        return copy;
    }
    */
    
    private String createProgressBar(double percent) {
        final int totalBars = 10;
        double clamped = Math.max(0.0, Math.min(100.0, percent));
        int filledBars = (int) Math.round((clamped / 100.0) * totalBars);
        if (filledBars > totalBars) filledBars = totalBars;

        StringBuilder progressBar = new StringBuilder();

        progressBar.append("[");

        progressBar.append("\u001B[34m");
        for (int i = 0; i < filledBars; i++) {
            progressBar.append("█");
        }
        progressBar.append("\u001B[0m");

        progressBar.append("\u001B[37m");
        for (int i = filledBars; i < totalBars; i++) {
            progressBar.append("░");
        }
        progressBar.append("\u001B[0m");

        progressBar.append("]");

        return progressBar.toString();
    }
}
