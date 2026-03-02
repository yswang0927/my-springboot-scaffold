kafka的分层TimingWheel

```java
// 使用方式：

// 1. 初始化时间轮 Timer: tick=200ms, wheelSize=20
Timer timer = new SystemTimer("name1", 200, 20);
// 2. 必须启动一个线程定期推进时间轮运作, 示例: 每200ms推进一次
// 不要用 scheduleAtFixedRate，而是用 while 循环配合 advanceClock 的阻塞特性
Thread timerDriver = new Thread(() -> {
    while (true) {
        try {
            // poll 200ms, 如果有任务到期会立即返回，没任务最多等200ms
            timer.advanceClock(200);
        } catch (InterruptedException e) {
            break;
        }
    }
}, "timer-driver");
timerDriver.setDaemon(true); // 设置为守护线程，主程序结束它自动结束
timerDriver.start();


// 3. 后面可以向里面添加延迟任务
timer.add(new TimerTask(10000) {
    @Override
    public void run() {
        System.out.println(">>> hello "+ this.delayMs);
    }
});

timer.add(new TimerTask(3000) {
    @Override
    public void run() {
        System.out.println(">>> hello "+ this.delayMs);
    }
});

timer.add(new TimerTask(2000) {
    @Override
    public void run() {
        System.out.println(">>> hello "+ this.delayMs);
    }
});

// 4. 可以主动停止时间轮
timer.stop();


// 带有重试能力的推送任务示例
// 推送任务，持有重试状态
public class PushTask extends TimerTask {
    private final String userId;
    private final String message;
    private final int retryCount;
    private final PushService pushService;
    private final Timer timer;

    public PushTask(String userId, String message, int retryCount,
                    PushService pushService, Timer timer) {
        super(30_000); // 30秒超时
        this.userId = userId;
        this.message = message;
        this.retryCount = retryCount;
        this.pushService = pushService;
        this.timer = timer;
    }

    @Override
    public void run() {
        // 走到这里说明30秒内没收到回调，触发重试
        if (retryCount < 3) {
            System.out.println("推送超时，第" + retryCount + "次重试: " + userId);
            PushTask retryTask = new PushTask(userId, message, retryCount + 1, pushService, timer);
            pushService.sendAsync(userId, message, retryTask); // 重新发送
            timer.add(retryTask); // 重新加入时间轮，等待下次超时
        } else {
            System.out.println("推送失败，放弃: " + userId);
            // 记录失败日志、告警等
        }
    }
}
// 推送服务，异步发送
public class PushService {
    public void sendAsync(String userId, String message, PushTask task) {
        apnsClient.sendAsync(userId, message).thenAccept(response -> {
            if (response.isSuccess()) {
                // 成功回调: 取消时间轮里的超时任务
                task.cancel();
            }
            // 失败不处理，让时间轮超时后自动重试
        });
    }
}
// Quartz Job: 9:00触发
public class MorningPushJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {
        List<String> users = userService.getAllUsers(); // 10万用户
        for (String userId : users) {
            PushTask task = new PushTask(userId, "早上好！", 1, pushService, timer);
            pushService.sendAsync(userId, "早上好！", task);
            timer.add(task); // 加入时间轮，30秒后若未cancel则触发重试
        }
    }
}
 


```