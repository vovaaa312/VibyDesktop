package com.viby.core;

import com.viby.core.platform.AppDirs;

/** Manual check of the stored Google sign-in: ./gradlew :core:devAccount (uses the real app dir). */
public final class DevAccount {

    public static void main(String[] args) throws Exception {
        try (VibyEngine engine = new VibyEngine(AppDirs.current())) {
            var account = engine.account();
            System.out.println("configured=" + account.isConfigured() + " signedIn=" + account.isSignedIn());
            try {
                System.out.println("channel ok: " + !account.channel().title().isEmpty());
            } catch (Exception e) {
                System.out.println("channel failed: " + e);
            }
        }
    }
}
