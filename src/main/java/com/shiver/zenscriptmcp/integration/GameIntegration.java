package com.shiver.zenscriptmcp.integration;

/** 可选实现仅在依赖与版本检查通过后加载，不在核心签名中引用可选模组类型。 */
public interface GameIntegration extends AutoCloseable {

    void register(IntegrationContext context);

    @Override
    default void close() {
    }
}
