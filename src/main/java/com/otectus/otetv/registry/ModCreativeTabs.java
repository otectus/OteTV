package com.otectus.otetv.registry;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvModel;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, OteTV.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.otetv"))
            .icon(() -> new ItemStack(ModItems.tv(TvModel.CRT)))
            .displayItems((params, output) -> {
                for (TvModel model : TvModel.values()) {
                    output.accept(ModItems.tv(model));
                }
                output.accept(ModItems.REMOTE_CONTROL.get());
            })
            .build());

    private ModCreativeTabs() {
    }
}
