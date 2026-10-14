package com.patelheet.capitalengine.market.maker;

import com.patelheet.capitalengine.market.Items;
import com.patelheet.capitalengine.market.maker.RecipeValuer.Recipe;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

/** Reads the server's crafting and cooking recipes into {@link Recipe}s. Call on the main thread. */
public final class BukkitRecipes {
    private BukkitRecipes() {}

    public static List<Recipe> load() {
        List<Recipe> out = new ArrayList<>();
        for (Iterator<org.bukkit.inventory.Recipe> it = Bukkit.recipeIterator(); it.hasNext(); ) {
            org.bukkit.inventory.Recipe r = it.next();
            String result = Items.key(r.getResult().getType());
            int count = Math.max(1, r.getResult().getAmount());
            if (r instanceof ShapedRecipe s) {
                List<List<String>> slots = new ArrayList<>();
                for (String row : s.getShape()) for (char ch : row.toCharArray()) {
                    RecipeChoice c = s.getChoiceMap().get(ch);
                    if (c != null) slots.add(alternatives(c));
                }
                out.add(new Recipe(result, count, slots, false));
            } else if (r instanceof ShapelessRecipe s) {
                out.add(new Recipe(result, count, s.getChoiceList().stream().map(BukkitRecipes::alternatives).toList(), false));
            } else if (r instanceof CookingRecipe<?> c) {
                out.add(new Recipe(result, count, List.of(alternatives(c.getInputChoice())), true));
            }
        }
        return out;
    }

    private static List<String> alternatives(RecipeChoice c) {
        if (c instanceof RecipeChoice.MaterialChoice m) return m.getChoices().stream().map(Items::key).toList();
        if (c instanceof RecipeChoice.ExactChoice e) return e.getChoices().stream().map(s -> Items.key(s.getType())).toList();
        return List.of();
    }
}
