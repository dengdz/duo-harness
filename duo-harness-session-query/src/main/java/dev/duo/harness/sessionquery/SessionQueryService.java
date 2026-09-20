package dev.duo.harness.sessionquery;

import java.util.List;

/**
 * 会话检索服务（后端无关，ADR-0022 决策 8）：接口语义对齐 FTS5 形态——
 * 查询分词 AND、按匹配强度排序、命中附 snippet——后期内存实现转 SQLite
 * FTS5 时上层（session_search 工具、Web 搜索框）零改动。
 *
 * <p>语义契约：</p>
 * <ul>
 *   <li>查询词经 {@link QueryTokenizer} 分词（英文整词 + 中文单字），所有词都
 *       出现的<b>事件</b>才是匹配文档（AND）；命中会话 = 该会话内最强匹配事件；</li>
 *   <li>每会话至多一条命中——最强匹配事件（词频 + 汉字原词整词加分，并列取较新事件）；</li>
 *   <li>排序：匹配强度降序，并列按会话最近修改时间降序；</li>
 *   <li>snippet 为事件文本的就近窗口，命中词以 {@code 【】} 包裹；</li>
 *   <li>实现必须线程安全（工具侧并发安全声明依赖此点）。</li>
 * </ul>
 */
public interface SessionQueryService {

    /** 服务名（harness 保留裸名；视图接口方法名即服务名，故为合法 Java 标识符）。 */
    String SERVICE_NAME = "sessionQuery";

    /**
     * 全文检索会话历史。
     *
     * @param query 查询字符串（分词后为空——无英文词且无中文字——返回空列表）
     * @param limit 命中数上限（须为正）
     * @return 命中列表（可能为空，不为 null）
     * @throws IllegalArgumentException limit 非正
     */
    List<SessionHit> search(String query, int limit);
}
