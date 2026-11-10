package com.craftysearch.client.storage;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * ChestTracker-style search: space-separated terms that must all match.
 * <ul>
 *   <li>{@code #term} matches an item tag, {@code @term} a mod id.</li>
 *   <li>A plain word matches the item name or an enchantment name on it ({@code mending}).</li>
 *   <li>A number right after a word means that enchantment at exactly that level:
 *       {@code sharpness 1}, {@code fire aspect 2}. On its own, a number matches any
 *       enchantment at that level. Roman numerals are plain letters.</li>
 * </ul>
 */
public final class SearchFilter {
    private record Ench(String name, String id, int level) {
        boolean matches(String term) {
            return name.contains(term) || id.contains(term);
        }
    }

    private SearchFilter() {
    }

    public static Predicate<ItemStack> parse(String query) {
        List<Predicate<ItemStack>> terms = new ArrayList<>();
        // The last plain word, which a level typed right after it binds to.
        String word = null;
        for (String raw : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (raw.isEmpty()) continue;
            int level = parseLevel(raw);
            if (word != null && level > 0) {
                terms.removeLast();
                terms.add(wordAtLevel(word, level));
                word = null;
                continue;
            }
            word = null;
            if (raw.startsWith("#")) {
                String term = raw.substring(1);
                terms.add(stack -> stack.typeHolder().tags().anyMatch(tag -> tag.location().toString().contains(term)));
            } else if (raw.startsWith("@")) {
                String term = raw.substring(1);
                terms.add(stack -> id(stack).getNamespace().contains(term));
            } else if (level > 0) {
                terms.add(stack -> enchantments(stack).stream().anyMatch(e -> e.level() == level));
            } else {
                terms.add(stack -> matchesName(stack, raw) || enchantments(stack).stream().anyMatch(e -> e.matches(raw)));
                word = raw;
            }
        }
        return stack -> {
            for (Predicate<ItemStack> term : terms) {
                if (!term.test(stack)) return false;
            }
            return true;
        };
    }

    private static Predicate<ItemStack> wordAtLevel(String word, int level) {
        return stack -> {
            List<Ench> enchantments = enchantments(stack);
            boolean wordIsEnchantment = false;
            for (Ench e : enchantments) {
                if (!e.matches(word)) continue;
                if (e.level() == level) return true;
                wordIsEnchantment = true;
            }
            // The word isn't an enchantment on this item ("book 3"): match it against the
            // name, and the level against any enchantment.
            return !wordIsEnchantment && matchesName(stack, word) && enchantments.stream().anyMatch(e -> e.level() == level);
        };
    }

    /** 1 to 255 for a number term, otherwise 0. */
    private static int parseLevel(String term) {
        if (term.length() > 3 || !term.chars().allMatch(Character::isDigit)) return 0;
        int n = Integer.parseInt(term);
        return n >= 1 && n <= 255 ? n : 0;
    }

    private static boolean matchesName(ItemStack stack, String term) {
        return stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(term) || id(stack).getPath().contains(term);
    }

    /** Enchantments on the item, including those stored on enchanted books. */
    private static List<Ench> enchantments(ItemStack stack) {
        List<Ench> list = new ArrayList<>();
        addEnchantments(list, stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
        addEnchantments(list, stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
        return list;
    }

    private static void addEnchantments(List<Ench> list, ItemEnchantments enchantments) {
        for (Object2IntMap.Entry<Holder<Enchantment>> e : enchantments.entrySet()) {
            Holder<Enchantment> holder = e.getKey();
            // The description is the name without the level numeral.
            String name = holder.value().description().getString().toLowerCase(Locale.ROOT);
            String id = holder.unwrapKey().map(key -> key.identifier().getPath()).orElse("");
            list.add(new Ench(name, id, e.getIntValue()));
        }
    }

    /** Sorts enchanted copies of one item by enchantment, then level. "" when there are none. */
    public static String sortKey(ItemStack stack) {
        return enchantments(stack).stream()
                .map(e -> String.format(Locale.ROOT, "%s %03d", e.name(), e.level()))
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static Identifier id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }
}
