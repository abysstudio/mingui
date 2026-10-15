package io.github.abysstudio.mingui;

import android.content.Context;

import java.util.Set;

/** Lite (no-root) build: no root operations exist. Blocked apps are covered by UnlockWatcher instead. */
final class RootOps {
    private RootOps() {}

    static boolean apply(Context c, Set<String> hide, Set<String> show) {
        return false;
    }
}
