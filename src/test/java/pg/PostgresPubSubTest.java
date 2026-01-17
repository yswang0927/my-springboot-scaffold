package pg;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.*;

/**
 * 使用Postgresql的Pub/Sub实现消息发布和订阅
 */
public class PostgresPubSubTest {

    static String jdbcUrl = "jdbc:postgresql://localhost:5432/postgres";
    static String user = "postgres";
    static String password = "postgres";

    /**
     * 发送消息
     */
    static void pgNotify() throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            System.out.println(">> Send notification");
            /*try (Statement stmt = conn.createStatement()) {
                stmt.execute("NOTIFY my_channel, 'Hello, pg_notify!'");
            }*/

            // 或者
            // 在使用 pg_notify 时，通知的 Payload 长度不能超过 8000 字节
            try (PreparedStatement statement = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
                statement.setString(1, "my_channel");
                statement.setString(2, "Hello, pg_notify! "+ System.currentTimeMillis());
                statement.execute();
            }
        }
    }

    /**
     * 接收消息
     */
    static void pgListen() throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            // 1. 强制转换为 PostgreSQL 的原生连接
            PGConnection pgConn = conn.unwrap(PGConnection.class);

            // 2. 运行 LISTEN 命令开启监听
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("LISTEN my_channel");
            }

            System.out.println("Waiting for notifications...");

            // 3. 进入持续监听循环
            while (!Thread.currentThread().isInterrupted()) {
                // Wait for notifications
                PGNotification[] notifications = pgConn.getNotifications(5000); // 5 seconds timeout
                if (notifications != null) {
                    for (PGNotification notification : notifications) {
                        System.out.printf(
                                "Received on channel<%s>: %s%n",
                                notification.getName(),
                                notification.getParameter()
                        );
                    }
                }
            }
        }
    }

    /**
     * 使用 https://impossibl.github.io/pgjdbc-ng/ 驱动来监听
     */
    static void pgListen2() throws Exception {
        /*String url = "jdbc:pgsql://localhost:5432/mydb";
        String user = "myuser";
        String password = "mypassword";

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            com.impossibl.postgres.api.jdbc.PGConnection pgConn = conn.unwrap(PGConnection.class);
            // Register a listener for notifications
            pgConn.addNotificationListener(new com.impossibl.postgres.api.jdbc.PGNotificationListener() {
                @Override
                public void notification(int processId, String channelName, String payload) {
                    System.out.printf("Process %d sent %s: %s%n", processId, channelName, payload);
                }
            });

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("LISTEN my_channel");
            }

            System.out.println("Listening for notifications...");

            // Keep the application alive
            Thread.sleep(Long.MAX_VALUE);
        }*/
    }

    public static void main(String[] args) throws Exception {
        new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                try {
                    Thread.sleep(2000);
                    pgNotify();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }).start();

        //pgListen();

        new Thread(new RobustPgListener(jdbcUrl, user, password, "my_channel")).start();

    }

    /**
     * 应该使用数据表来持久化消息，防止消息丢失
     * <pre>
     * CREATE TABLE event_notifications (
     *     id SERIAL PRIMARY KEY,
     *     channel VARCHAR(128) NOT NULL,
     *     payload TEXT,
     *     status VARCHAR(32) DEFAULT 'PENDING', -- PENDING, PROCESSING, COMPLETED
     *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
     * );
     *
     * -- 索引非常重要，保证监听端扫描表的效率
     * CREATE INDEX idx_event_channel ON event_notifications(channel)
     * CREATE INDEX idx_event_pending ON event_notifications(status) WHERE status = 'PENDING';
     * </pre>
     */
    public static class RobustPgListener implements Runnable {
        private final String url;
        private final String user;
        private final String password;
        private final String channel;

        public RobustPgListener(String url, String user, String password, String channel) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.channel = channel;
        }

        public void sendReliableNotify(String channel, String message) throws SQLException {
            String insertSql = "INSERT INTO event_notifications(channel, payload) VALUES (?, ?)";

            // 确保“写表”和“发通知”在同一个数据库事务中
            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                conn.setAutoCommit(false); // 开启事务
                try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                    pstmt.setString(1, channel);
                    pstmt.setString(2, message);
                    pstmt.executeUpdate();
                }

                // 发送通知，告诉监听者“有新活了”，负载可以只传 ID 或者为空
                try (PreparedStatement pstmt = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
                    pstmt.setString(1, channel);
                    pstmt.setString(2, "new_event");
                    pstmt.execute();
                }
                conn.commit(); // 提交事务，此时数据可见且通知发出
            }
        }

        private void handleNotification(String channel) {
            // 1. 去数据库捞取所有待处理的任务
            String fetchSql = "SELECT id, payload FROM event_notifications WHERE channel = ? AND status = 'PENDING' FOR UPDATE SKIP LOCKED";

            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                conn.setAutoCommit(false);

                try (PreparedStatement pstmt = conn.prepareStatement(fetchSql)) {
                    pstmt.setString(1, channel);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            long id = rs.getLong("id");
                            String payload = rs.getString("payload");
                            try {
                                // 2. 处理业务逻辑 TODO...
                                //processBusinessLogic(payload);
                                // 3. 标记为已完成（或直接删除）TODO...
                                //updateStatus(id, "COMPLETED", conn);
                            } catch (Exception e) {
                                // TODO...
                                //updateStatus(id, "FAILED", conn);
                            }
                        }
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                // 处理数据库异常
            }
        }

        @Override
        public void run() {
            int retryDelay = 1000; // 初始重连延迟 1s

            while (!Thread.currentThread().isInterrupted()) {
                try (Connection conn = DriverManager.getConnection(url, user, password)) {
                    System.out.println("连接已建立，正在订阅通道: " + channel);

                    // 1. 重新订阅（每次重连都必须重新执行 LISTEN）
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("LISTEN " + channel);
                    }

                    // 【关键点 A】重连成功后，先执行一次 handleNotification()
                    // 确保在断线期间存入表中的数据即使没有收到 NOTIFY 也能被处理
                    //handleNotification(channel);

                    PGConnection pgConn = conn.unwrap(PGConnection.class);
                    retryDelay = 1000; // 重连成功，重置延迟

                    while (!Thread.currentThread().isInterrupted()) {
                        // 2. 发送心跳查询，强制同步网络状态并检测连接有效性
                        try (Statement stmt = conn.createStatement()) {
                            stmt.executeQuery("SELECT 1").close();
                        }

                        // 3. 获取通知
                        PGNotification[] notifications = pgConn.getNotifications(5000);
                        if (notifications != null) {
                            // 【关键点 B】收到信号，立即处理
                            /*if (notifications.length > 0) {
                                System.out.println("收到通知信号，开始扫表...");
                                handleNotification(channel);
                            }*/

                            // 测试打印
                            for (PGNotification n : notifications) {
                                processNotification(n);
                            }
                        }
                    }

                } catch (SQLException e) {
                    System.err.println("连接丢失或发生错误: " + e.getMessage());
                    // 4. 指数退避策略，防止崩溃时高频冲击数据库
                    try {
                        Thread.sleep(retryDelay);
                        retryDelay = Math.min(retryDelay * 2, 60000); // 最大延迟 1 分钟
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        private void processNotification(PGNotification n) {
            System.out.printf("[%d] 收到来自 %s 的消息: %s%n",
                    System.currentTimeMillis(), n.getName(), n.getParameter());
        }
    }

}
