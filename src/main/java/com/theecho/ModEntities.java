package com.theecho;

import com.theecho.entity.EchoEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class ModEntities {
    /** Nunca aparece por spawn natural (SpawnGroup.MISC): solo el mod lo crea, y nunca se guarda en el mundo. */
    public static final EntityType<EchoEntity> ECHO = Registry.register(Registries.ENTITY_TYPE, TheEcho.id("echo"),
            EntityType.Builder.create(EchoEntity::new, SpawnGroup.MISC)
                    .dimensions(0.6f, 1.8f)
                    .eyeHeight(1.62f)
                    .maxTrackingRange(10)
                    .build("echo"));

    public static void register() {
        FabricDefaultAttributeRegistry.register(ECHO, EchoEntity.createEchoAttributes());
    }
}
