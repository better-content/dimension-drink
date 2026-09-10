package com.bettercontent.dimensiondrink.mixin;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(StructureTemplatePool.class)
public interface StructureTemplatePoolAccessor {
    @Accessor("rawTemplates")
    List<Pair<StructurePoolElement, Integer>> getDimensionDrinkRawTemplates();

    @Mutable
    @Accessor("rawTemplates")
    void setDimensionDrinkRawTemplates(List<Pair<StructurePoolElement, Integer>> templates);

    @Accessor("templates")
    ObjectArrayList<StructurePoolElement> getDimensionDrinkTemplates();

    @Accessor("maxSize")
    void setDimensionDrinkMaxSize(int maxSize);
}
