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
```