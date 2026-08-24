package dev.duo.harness.core.internal;

/**
 * 服务注册表的二阶键：（服务名, 作用域）。
 *
 * <p>isolate 多作用域能力的地基预留：当前内核只存在 {@link Scope#DEFAULT}
 * 一个作用域，后续引入作用域隔离时不改变键结构、不动寻址路径。</p>
 *
 * @param name 服务名（全局扁平命名空间）
 * @param scope 作用域；当前恒为 {@link Scope#DEFAULT}
 */
record ServiceKey(String name, Scope scope) {

    /** 默认作用域内按服务名建键。 */
    static ServiceKey defaultScope(String name) {
        return new ServiceKey(name, Scope.DEFAULT);
    }
}

/** 作用域记号：当前仅默认全局作用域，isolate 扩展点。 */
enum Scope {
    DEFAULT
}
