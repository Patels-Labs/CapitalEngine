package com.patelheet.capitalengine.api.service;

import java.util.List;

/** Implemented by add-ons and registered with Bukkit's ServicesManager. Many may coexist. Core implements none. */
public interface IndicatorProvider {
    /** Short id, for example {@code "capitalmarket"}. */
    String id();

    /** Human name for dashboards. */
    String displayName();

    /** Current numbers. Must be cheap and thread-safe: return cached values. */
    List<Indicator> indicators();
}
