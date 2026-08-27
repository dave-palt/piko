/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.readOnlyFollowButton;

import java.lang.reflect.Proxy;

import app.morphe.extension.shared.Logger;

/**
 * Supplies a cached no-op kotlin.jvm.functions.Function0.
 *
 * <p>The extension compiles as pure Java against stubs and has no
 * kotlin-stdlib on its compile classpath, so the lambda is materialised via a
 * dynamic proxy. Compose consumers null-check their onClick params, so null
 * is never an acceptable substitute.
 */
public final class ReadOnlyFollowButton {

    private static volatile Object cachedNoOp;

    /**
     * Returns a shared no-op Function0 to substitute for {@code original}.
     *
     * <p>If the proxy cannot be built (kotlin-stdlib missing — should never
     * happen inside Instagram), the original onClick is returned unchanged so
     * the button keeps working normally instead of crashing on invoke.
     */
    public static Object noop(Object original) {
        Object noOp = cachedNoOp;
        if (noOp == null) {
            synchronized (ReadOnlyFollowButton.class) {
                noOp = cachedNoOp;
                if (noOp == null) {
                    try {
                        Class<?> fn0 = Class.forName("kotlin.jvm.functions.Function0");
                        noOp =
                                Proxy.newProxyInstance(
                                        ReadOnlyFollowButton.class.getClassLoader(),
                                        new Class<?>[] {fn0},
                                        (proxy, method, args) -> null);
                        cachedNoOp = noOp;
                    } catch (Throwable t) {
                        // Never throw from a compose injection site; keep the
                        // real handler instead of risking a crash on invoke.
                        Logger.printException(() -> "no-op Function0 proxy failed", t);
                        return original;
                    }
                }
            }
        }
        return noOp;
    }

    private ReadOnlyFollowButton() {}
}
