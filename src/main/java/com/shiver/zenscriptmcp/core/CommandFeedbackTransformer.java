package com.shiver.zenscriptmcp.core;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** 仅钩住支持的真实发送者与命令管理器，不改变命令路由、返回值或异常处理。 */
public final class CommandFeedbackTransformer implements IClassTransformer {

    private static final String HOOK = "com/shiver/zenscriptmcp/game/client/CommandFeedback";

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        boolean sender = "net.minecraft.client.entity.EntityPlayerSP".equals(transformedName)
                || "net.minecraft.entity.player.EntityPlayerMP".equals(transformedName)
                || "net.minecraft.server.MinecraftServer".equals(transformedName);
        boolean manager = "net.minecraft.command.CommandHandler".equals(transformedName)
                || "net.minecraftforge.client.ClientCommandHandler".equals(transformedName);
        if (bytes == null || !sender && !manager) {
            return bytes;
        }
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        for (MethodNode method : node.methods) {
            if (sender && messageMethod(method)) {
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "message",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                method.instructions.insert(hook);
            }
            if (manager) {
                hookExceptions(method);
            }
        }
        // 注入不改变分支或局部变量，只增加瞬时栈深度，保留原有栈帧。
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean messageMethod(MethodNode method) {
        return ("sendMessage".equals(method.name) || "func_145747_a".equals(method.name))
                && "(Lnet/minecraft/util/text/ITextComponent;)V".equals(method.desc)
                || "a".equals(method.name) && "(Lhh;)V".equals(method.desc);
    }

    private static void hookExceptions(MethodNode method) {
        Set<LabelNode> visited = new HashSet<>();
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            if (block.type == null || !visited.add(block.handler)) {
                continue;
            }
            AbstractInsnNode instruction = block.handler.getNext();
            while (instruction != null && instruction.getOpcode() < 0) {
                instruction = instruction.getNext();
            }
            if (instruction != null && instruction.getOpcode() == Opcodes.ASTORE) {
                InsnList hook = new InsnList();
                hook.add(new InsnNode(Opcodes.DUP));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "exception",
                        "(Ljava/lang/Throwable;)V", false));
                method.instructions.insertBefore(instruction, hook);
            }
        }
    }
}
