package dev.duo.harness.example;

/** 示例服务契约：问候。 */
public interface GreetingService {

    /** 服务名（provide/inject/视图方法名三处共用的身份约定）。 */
    String SERVICE_NAME = "greeting";

    /** 生成问候语。 */
    String greet(String name);
}
