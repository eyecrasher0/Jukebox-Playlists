package dev.eyecrasher.jukeboxplaylists;

import cpw.mods.fml.relauncher.FMLLaunchHandler;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions("dev.eyecrasher.jukeboxplaylists.JukeboxRemovalHook")
public final class JukeboxRemovalHook implements IFMLLoadingPlugin, IClassTransformer {
    @Override public String[] getASMTransformerClass() {
        return FMLLaunchHandler.side().isServer() ? new String[]{getClass().getName()} : null;
    }

    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public String getAccessTransformerClass() { return null; }
    @Override public void injectData(Map<String, Object> data) { }

    @Override
    public byte[] transform(String name, String transformedName, byte[] code) {
        if (code == null || !"net.minecraft.block.BlockJukebox".equals(transformedName)) return code;
        ClassReader reader = new ClassReader(code);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!("func_149749_a".equals(name) || "breakBlock".equals(name))
                        || !"(Lnet/minecraft/world/World;IIILnet/minecraft/block/Block;I)V".equals(descriptor)) return method;
                return new MethodVisitor(Opcodes.ASM5, method) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            visitVarInsn(Opcodes.ALOAD, 1);
                            visitVarInsn(Opcodes.ILOAD, 2);
                            visitVarInsn(Opcodes.ILOAD, 3);
                            visitVarInsn(Opcodes.ILOAD, 4);
                            visitMethodInsn(Opcodes.INVOKESTATIC, "dev/eyecrasher/jukeboxplaylists/PlaylistPositions",
                                    "removed", "(Lnet/minecraft/world/World;III)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
