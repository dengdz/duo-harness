package dev.duo.harness.core.api;

/**
 * 可逆副作用：注册时给出，作用域销毁时执行。
 *
 * <p>dispose 抛出的异常不会中断兄弟副作用的回滚（错误聚合后统一抛出），
 * 因此实现应尽量快速、幂等。</p>
 */
@FunctionalInterface
public interface Disposable {

    void dispose() throws Exception;
}
