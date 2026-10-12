package com.patelheet.capitalengine.api.service;

/**
 * A named number an add-on publishes for dashboards.
 *
 * @param key   stable machine key
 * @param label plain-language label
 * @param value current value
 * @param unit  how to display it
 */
public record Indicator(String key, String label, double value, Unit unit) {}
