package dev.eyecrasher.jukeboxplaylists.mixin;

import net.minecraft.world.LockCode;
import net.minecraft.tileentity.LockableTileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LockableTileEntity.class)
public interface ContainerLockAccessor {
    @Accessor("lockKey")
    LockCode jukeboxPlaylists$getLock();
}
