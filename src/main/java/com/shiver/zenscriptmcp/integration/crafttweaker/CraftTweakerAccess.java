package com.shiver.zenscriptmcp.integration.crafttweaker;

import com.google.gson.JsonArray;
import com.shiver.zenscriptmcp.model.Json;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/** 仅适配核对过的 CT 4.1.20.698 / ZenUtils 1.27.1；反射边界不向核心泄露可选类型。 */
final class CraftTweakerAccess {

    private final Class<?> api;
    private final Object logger;
    private final Object receiver;
    private final Class<?> loggerInterface;

    CraftTweakerAccess(CtDiagnostics diagnostics) {
        try {
            api = Class.forName("crafttweaker.CraftTweakerAPI");
            loggerInterface = Class.forName("crafttweaker.runtime.ILogger");
            logger = invoke(api.getMethod("getLogger"), null);
            receiver = Proxy.newProxyInstance(loggerInterface.getClassLoader(), new Class<?>[] {loggerInterface},
                    (proxy, method, arguments) -> receive(diagnostics, proxy, method.getName(), arguments));
            invoke(logger.getClass().getMethod("addLogger", loggerInterface), logger, receiver);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("CT 日志接收接口不匹配", exception);
        }
    }

    JsonArray loaders() {
        try {
            Object tweaker = api.getField("tweaker").get(null);
            Class<?> tweakerType = Class.forName("crafttweaker.runtime.ITweaker");
            Class<?> loaderType = Class.forName("crafttweaker.runtime.ScriptLoader");
            List<?> loaders = (List<?>) invoke(tweakerType.getMethod("getLoaders"), tweaker);
            JsonArray result = new JsonArray();
            for (Object loader : loaders) {
                String name = String.valueOf(invoke(loaderType.getMethod("getMainName"), loader));
                Object stage = invoke(loaderType.getMethod("getLoaderStage"), loader);
                result.add(Json.object("name", name, "stage", String.valueOf(stage)));
            }
            return result;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("CT 加载器读取失败", exception);
        }
    }

    boolean hasCommand(String name) {
        try {
            Class<?> commands = Class.forName("crafttweaker.mc1120.commands.CTChatCommand");
            Map<?, ?> registered = (Map<?, ?>) invoke(commands.getMethod("getCraftTweakerCommands"), null);
            return registered.containsKey(name);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("CT 子命令目录读取失败", exception);
        }
    }

    void detach() {
        try {
            invoke(logger.getClass().getMethod("removeLogger", loggerInterface), logger, receiver);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("CT 日志接收器清理失败", exception);
        }
    }

    private static Object receive(CtDiagnostics diagnostics, Object proxy, String method, Object[] arguments) {
        switch (method) {
            case "equals":
                return proxy == arguments[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "ZenScriptMCPDiagnosticReceiver";
            case "isLogDisabled":
                return false;
            case "logError":
                Throwable exception = arguments.length > 1 ? (Throwable) arguments[1] : null;
                diagnostics.record("error", (String) arguments[0], exception, "receiver");
                break;
            case "logWarning":
                diagnostics.record("warning", (String) arguments[0], null, "receiver");
                break;
            case "logInfo":
            case "logDefault":
            case "logCommand":
                diagnostics.record("info", (String) arguments[0], null, "receiver");
                break;
            default:
                // logPlayer/setLogDisabled 不改变外部 logger、玩家列表或过滤规则。
                break;
        }
        return null;
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("CT 接口调用失败", exception.getCause());
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("CT 接口不可访问", exception);
        }
    }
}
