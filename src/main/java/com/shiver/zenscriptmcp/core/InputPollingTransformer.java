package com.shiver.zenscriptmcp.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** 保持静态调用的参数、返回类型和栈帧，仅替换两种原生轮询入口。 */
public final class InputPollingTransformer implements IClassTransformer {

    private static final String POLLING_CLASS = "com/shiver/zenscriptmcp/game/client/InputPolling";
    private static final String CORE_PACKAGE = "com/shiver/zenscriptmcp/core/";
    private static final String POLLING_DESCRIPTOR = "(I)Z";

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        ClassReader reader = new ClassReader(bytes);
        String className = reader.getClassName();
        // 钩子本身必须继续访问原生设备；core 包的排除同时避免转换器加载递归。
        if (POLLING_CLASS.equals(className) || className.startsWith(CORE_PACKAGE)) {
            return bytes;
        }
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode && instruction.getOpcode() == Opcodes.INVOKESTATIC) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (isPollingCall(call)) {
                        call.owner = POLLING_CLASS;
                        changed = true;
                    }
                }
            }
        }
        if (!changed) {
            return bytes;
        }
        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean isPollingCall(MethodInsnNode call) {
        if (!POLLING_DESCRIPTOR.equals(call.desc)) {
            return false;
        }
        // Foundation 可能已重映射类名；同时接受原名和兼容层名。
        if ("isKeyDown".equals(call.name)) {
            return "org/lwjgl/input/Keyboard".equals(call.owner) || "org/lwjglx/input/Keyboard".equals(call.owner);
        }
        if ("isButtonDown".equals(call.name)) {
            return "org/lwjgl/input/Mouse".equals(call.owner) || "org/lwjglx/input/Mouse".equals(call.owner);
        }
        return false;
    }
}
