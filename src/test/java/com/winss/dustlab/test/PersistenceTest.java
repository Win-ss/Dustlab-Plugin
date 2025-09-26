package com.winss.dustlab.test;

public class PersistenceTest {
    
    public static void main(String[] args) {
        // Test the shouldBePersistent logic
        System.out.println("Testing persistence logic:");
        
        System.out.println("Infinite (-1): " + shouldBePersistent(-1)); 
        System.out.println("One-time (0): " + shouldBePersistent(0)); 
        System.out.println("30 seconds: " + shouldBePersistent(30));
        System.out.println("60 seconds: " + shouldBePersistent(60));
        System.out.println("61 seconds: " + shouldBePersistent(61));
        System.out.println("3000 seconds: " + shouldBePersistent(3000));
    }
    
    private static boolean shouldBePersistent(int lifetimeSeconds) {
        if (lifetimeSeconds == -1) {
            return true;
        }
        if (lifetimeSeconds == 0) {
            return false;
        }
        return lifetimeSeconds > 60;
    }
}