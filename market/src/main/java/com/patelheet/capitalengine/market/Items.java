package com.patelheet.capitalengine.market;

import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

/**
 * What may be traded and how it is named (market/Plan.md §5.1). An item key is the material plus, only where it matters,
 * its stored enchantments or potion type in a stable order: {@code iron_ingot}, {@code enchanted_book{sharpness:5}},
 * {@code potion{strong_healing}}. A stack is tradable only if rebuilding it from its key gives an identical stack, which
 * rules out custom names, lore, model data, damage and non-empty shulker boxes in one check.
 * Keys rebuild into exact vanilla stacks, so nothing needs to be stored besides the key.
 */
public final class Items {
    private Items() {}

    public static String key(Material m) { return m.getKey().getKey(); }

    /** The key of a tradable stack, or null. */
    public static String keyOf(ItemStack s) {
        if (s == null || s.getType().isAir() || !s.getType().isItem()) return null;
        String k = key(s.getType());
        ItemMeta meta = s.hasItemMeta() ? s.getItemMeta() : null;
        if (meta instanceof EnchantmentStorageMeta e && !e.getStoredEnchants().isEmpty())
            k += "{" + e.getStoredEnchants().entrySet().stream().map(x -> x.getKey().getKey().getKey() + ":" + x.getValue()).sorted().collect(Collectors.joining(",")) + "}";
        else if (meta instanceof PotionMeta p && p.getBasePotionType() != null) k += "{" + p.getBasePotionType().getKey().getKey() + "}";
        // anything else is judged as the plain item: the check below rejects names, lore, damage, contents and so on
        ItemStack rebuilt = one(k);
        return rebuilt != null && rebuilt.isSimilar(s) ? k : null;
    }

    public static boolean tradable(ItemStack s, Set<String> blocked) {
        String k = keyOf(s);
        return k != null && !blocked.contains(k) && !blocked.contains(key(s.getType()));
    }

    /** One item for a key, or null if the key isn't valid. */
    public static ItemStack one(String key) {
        int i = key.indexOf('{');
        if (i >= 0 && !key.endsWith("}")) return null;
        Material m = Material.matchMaterial(i < 0 ? key : key.substring(0, i));
        if (m == null || !m.isItem() || m.isAir()) return null;
        ItemStack s = new ItemStack(m);
        if (i < 0) return s;
        String body = key.substring(i + 1, key.length() - 1);
        ItemMeta meta = s.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta e) {
            for (String part : body.split(",")) {
                String[] kv = part.split(":");
                Enchantment en = kv.length == 2 ? Registry.ENCHANTMENT.get(NamespacedKey.minecraft(kv[0])) : null;
                if (en == null) return null;
                try { e.addStoredEnchant(en, Integer.parseInt(kv[1]), true); } catch (NumberFormatException ex) { return null; }
            }
        } else if (meta instanceof PotionMeta p) {
            PotionType t = Registry.POTION.get(NamespacedKey.minecraft(body));
            if (t == null) return null;
            p.setBasePotionType(t);
        } else return null;
        s.setItemMeta(meta);
        return s;
    }

    /** {@code qty} items as full stacks. */
    public static ItemStack[] stacks(String key, int qty) {
        ItemStack one = one(key);
        int max = Math.max(1, one.getMaxStackSize());
        ItemStack[] out = new ItemStack[(qty + max - 1) / max];
        for (int i = 0; i < out.length; i++) out[i] = one.asQuantity(Math.min(max, qty - i * max));
        return out;
    }

    /** What a player typed ("iron ingot", "Iron_Ingot", "enchanted_book{sharpness:5}") as a key, or null. */
    public static String parse(String text) {
        int i = text.indexOf('{');
        String t = (i < 0 ? text.toLowerCase().replace(' ', '_') : text.substring(0, i).toLowerCase().replace(' ', '_') + text.substring(i)).replace("minecraft:", "");
        ItemStack s = one(t);
        return s == null ? null : keyOf(s);
    }

    public static String pretty(String key) {
        int i = key.indexOf('{');
        return (i < 0 ? key : key.substring(0, i) + " (" + key.substring(i + 1, key.length() - 1).replace(':', ' ') + ")").replace('_', ' ');
    }

    public static Material material(String key) { return Material.matchMaterial(key.contains("{") ? key.substring(0, key.indexOf('{')) : key); }

    public static int stackSize(String key) {
        Material m = material(key);
        return m == null ? 64 : Math.max(1, m.getMaxStackSize());
    }
}
