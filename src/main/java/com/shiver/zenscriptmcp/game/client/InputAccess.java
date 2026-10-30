package com.shiver.zenscriptmcp.game.client;

import com.shiver.zenscriptmcp.model.DomainException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/** 只用于没有公开入口的少量游戏字段；同时指定开发名和运行时 SRG 名。 */
final class InputAccess {

    private InputAccess() {
    }

    static Field field(Class<?> type, String... names) {
        return ReflectionHelper.findField(type, names);
    }

    static boolean hasMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            type.getMethod(name, parameters);
            return true;
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    static Object get(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException exception) {
            throw new DomainException("unsupported_capability", exception.toString());
        }
    }

    static void set(Field field, Object owner, Object value) {
        try {
            field.set(owner, value);
        } catch (IllegalAccessException exception) {
            throw new DomainException("unsupported_capability", exception.toString());
        }
    }

    static void invoke(Object owner, Class<?> type, String name, String srg, Class<?>[] types, Object... values) {
        Method method = ReflectionHelper.findMethod(type, name, srg, types);
        try {
            method.invoke(owner, values);
        } catch (IllegalAccessException exception) {
            throw new DomainException("unsupported_capability", exception.toString());
        } catch (InvocationTargetException exception) {
            throw new DomainException("action_rejected", exception.getCause().toString());
        }
    }
}
