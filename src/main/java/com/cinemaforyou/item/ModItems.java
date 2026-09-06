package com.cinemaforyou.item;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

import java.util.function.Function;

/**
 * 模组物品注册。
 *
 * <p>26.2 API 变更：{@link Item} 构造时必须已在 {@link Item.Properties} 上调用
 * {@code setId(ResourceKey&lt;Item&gt;}，否则会抛出 "Item id not set"。
 * 因此采用工厂模式：先构造 {@link ResourceKey}，再注入到 Properties，最后构造 Item。
 */
public final class ModItems {

    private ModItems() {}

    /** 影院选择器物品实例。 */
    public static final Item CINEMA_SELECTOR = register(
            "cinema_selector",
            id -> new CinemaSelectorItem(new Item.Properties()
                    .rarity(Rarity.RARE)
                    .stacksTo(1)
                    .setId(id))
    );

    /**
     * 注册物品：先根据 name 构造 {@link ResourceKey}，
     * 传给工厂函数让其完成 {@code setId(...)}，再注册到 ITEM registry。
     *
     * @param name    物品短名（不含命名空间）
     * @param factory 接收 item id 的工厂；必须在内部调用 {@code Properties.setId(id)}
     */
    private static Item register(String name, Function<ResourceKey<Item>, Item> factory) {
        ResourceKey<Item> itemId = ResourceKey.create(
                Registries.ITEM,
                Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, name));
        Item item = factory.apply(itemId);
        return Registry.register(BuiltInRegistries.ITEM, itemId, item);
    }

    /** 在 {@link CinemaForYou#onInitialize()} 中调用。 */
    public static void register() {
        CinemaForYou.LOGGER.info("[CinemaForYou] 物品已注册: cinema_selector");
    }
}
