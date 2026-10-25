package com.otectus.otetv.registry;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.item.RemoteControlItem;
import com.otectus.otetv.item.TvBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, OteTV.MOD_ID);

    public static final Map<TvModel, RegistryObject<TvBlockItem>> TVS = new EnumMap<>(TvModel.class);

    static {
        for (TvModel model : TvModel.values()) {
            TVS.put(model, ITEMS.register(model.registryName(),
                    () -> new TvBlockItem(ModBlocks.tv(model), new Item.Properties().stacksTo(16).rarity(model.ordinal() >= 4 ? Rarity.UNCOMMON : Rarity.COMMON))));
        }
    }

    public static final RegistryObject<RemoteControlItem> REMOTE_CONTROL = ITEMS.register("remote_control",
            () -> new RemoteControlItem(new Item.Properties().stacksTo(1)));

    public static TvBlockItem tv(TvModel model) {
        return TVS.get(model).get();
    }

    private ModItems() {
    }
}
