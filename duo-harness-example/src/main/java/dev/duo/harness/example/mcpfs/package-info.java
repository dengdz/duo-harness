/**
 * 迷你 filesystem MCP 服务器：SDK server 侧构建（真实 stdio 协议），
 * 提供 read_file / write_file 两工具，根目录锁定在启动参数指定的目录。
 * 由 demo 的 MCP 连接作为子进程拉起——见
 * {@link dev.duo.harness.example.mcpfs.MiniFileSystemServer}。
 */
package dev.duo.harness.example.mcpfs;
