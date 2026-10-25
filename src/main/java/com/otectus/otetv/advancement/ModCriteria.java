package com.otectus.otetv.advancement;

import com.google.gson.JsonObject;
import com.otectus.otetv.OteTV;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Advancement triggers for pairing a remote and playing the bundled demo (section 5.4). Nothing is ever awarded for
 * watching ads, signing in to a service or increasing view counts.
 */
public final class ModCriteria {
    public static final SimpleTrigger PAIRED_REMOTE = new SimpleTrigger(OteTV.id("paired_remote"));
    public static final SimpleTrigger PLAYED_DEMO = new SimpleTrigger(OteTV.id("played_demo"));
    private static boolean registered;

    private ModCriteria() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        CriteriaTriggers.register(PAIRED_REMOTE);
        CriteriaTriggers.register(PLAYED_DEMO);
    }

    public static final class SimpleTrigger extends SimpleCriterionTrigger<SimpleTrigger.Instance> {
        private final ResourceLocation id;

        public SimpleTrigger(ResourceLocation id) {
            this.id = id;
        }

        @Override
        public ResourceLocation getId() {
            return id;
        }

        @Override
        protected Instance createInstance(JsonObject json, ContextAwarePredicate player, DeserializationContext context) {
            return new Instance(id, player);
        }

        public void trigger(ServerPlayer player) {
            trigger(player, instance -> true);
        }

        public Instance instance() {
            return new Instance(id, ContextAwarePredicate.ANY);
        }

        public static final class Instance extends AbstractCriterionTriggerInstance {
            public Instance(ResourceLocation id, ContextAwarePredicate player) {
                super(id, player);
            }
        }
    }
}
