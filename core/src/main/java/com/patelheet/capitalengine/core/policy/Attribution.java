package com.patelheet.capitalengine.core.policy;

import java.util.Optional;
import java.util.function.Function;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Finds which plugin made a legacy Vault call by walking the stack: skip our own adapter and Vault frames, take the
 * first class loaded by a plugin class loader. Cached per calling class ({@code ClassValue} lets plugin classes unload).
 */
public final class Attribution {
    public static final String UNKNOWN = "unknown";
    private static final String ADAPTERS = "com.patelheet.capitalengine.core.vault.";

    private final ClassValue<Optional<String>> owners;

    /** Production: resolve with {@link JavaPlugin#getProvidingPlugin}. */
    public Attribution() {
        this(cls -> {
            try { return Optional.of(JavaPlugin.getProvidingPlugin(cls).getName()); }
            catch (IllegalArgumentException | IllegalStateException notAPlugin) { return Optional.empty(); }
        });
    }

    /** Tests: resolve however you like. */
    public Attribution(Function<Class<?>, Optional<String>> resolver) {
        owners = new ClassValue<>() { @Override protected Optional<String> computeValue(Class<?> type) { return resolver.apply(type); } };
    }

    public String caller() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> !c.getName().startsWith("net.milkbowl.vault") && !c.getName().startsWith(ADAPTERS) && !c.equals(Attribution.class))
                .map(owners::get)
                .flatMap(Optional::stream)
                .findFirst().orElse(UNKNOWN));
    }
}
